package jraft.sim;

import jraft.raftcore.Configuration;
import jraft.raftcore.LogEntry;
import jraft.raftcore.Message;
import jraft.raftcore.NodeRole;
import jraft.raftcore.Output;
import jraft.raftcore.RaftCore;
import jraft.raftcore.Snapshot;
import jraft.storage.HardState;
import jraft.storage.Storage;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Single-threaded Raft node plus KV server; all side effects stay inside SimCluster. */
final class SimNode {
    private final int id;
    private final SimCluster cluster;
    private final RaftCore core;
    private final KvStateMachine stateMachine = new KvStateMachine();
    private final Storage storage;
    /** Writes waiting for their log index to be applied: index -> request. */
    private final Map<Long, ClientRequest> pendingWrites = new TreeMap<>();
    /** GETs waiting for ReadIndex: readId -> request. */
    private final Map<Long, ClientRequest> pendingReads = new TreeMap<>();
    private long nextReadId;
    private boolean executing;

    SimNode(int id, SimCluster cluster, Configuration bootstrap, Storage storage) {
        this.id = id;
        this.cluster = cluster;
        this.storage = storage;
        try {
            // Recovery: restore the latest snapshot, then replay committed WAL entries after it.
            HardState hardState = storage.loadHardState();
            Snapshot snapshot = storage.loadSnapshot();
            List<LogEntry> recovered = RaftCore.entriesAfter(snapshot, storage.loadLog());
            this.core = new RaftCore(id, bootstrap, 9 + id * 2, 2, hardState.currentTerm(),
                    hardState.votedFor(), hardState.commitIndex(), snapshot, recovered)
                    .configure(cluster.maxEntriesPerAppend(), cluster.snapshotChunkBytes());
            stateMachine.restore(snapshot.data());
            // Replaying committed entries also rebuilds the client session table.
            for (LogEntry entry : recovered) {
                if (entry.index() > core.commitIndex()) break;
                stateMachine.apply(entry);
            }
        } catch (IOException error) {
            throw new IllegalStateException("could not recover simulated node " + id, error);
        }
    }

    void tick() { execute(core.tick()); }
    void receive(int from, Message message) { execute(core.step(from, message)); }
    void propose(byte[] command) { execute(core.propose(command)); }
    RaftCore core() { return core; }
    KvStateMachine stateMachine() { return stateMachine; }
    String describe() { return id + ":" + core.role() + "/term=" + core.currentTerm()
            + "/commit=" + core.commitIndex() + "/last=" + core.lastIndex() + "/snap=" + core.snapshot().index()
            + "/disk=" + ((SimDisk) storage).describe(); }

    /** Admin API (membership); only the leader accepts changes. */
    RaftCore.ConfigChangeStatus addServer(int server) {
        RaftCore.ConfigChangeResult result = core.addServer(server);
        execute(result.outputs());
        return result.status();
    }

    RaftCore.ConfigChangeStatus removeServer(int server) {
        RaftCore.ConfigChangeResult result = core.removeServer(server);
        execute(result.outputs());
        return result.status();
    }

    /** Entry point for client requests delivered by SimCluster. */
    void onClientRequest(ClientRequest request) {
        if (core.role() != NodeRole.LEADER) {
            reply(request, ClientReply.Status.WRONG_LEADER, null);
            return;
        }
        if (request.operation() == KvCommand.Operation.GET) {
            if (cluster.unsafeLocalReads()) {   // test-only negative control: read without ReadIndex
                replyWithRead(request);
                return;
            }
            long readId = ++nextReadId;
            pendingReads.put(readId, request);
            execute(core.readIndex(readId));
            return;
        }
        // Exactly-once: a retry of an already applied write gets the cached reply, not a re-apply.
        KvStateMachine.Result cached = stateMachine.cachedResult(request.clientId(), request.sequence());
        if (cached != null) {
            reply(request, ClientReply.Status.OK, cached.value());
            return;
        }
        if (stateMachine.alreadyApplied(request.clientId(), request.sequence())) return; // client moved on
        List<Output> outputs = core.propose(KvCommandCodec.encode(request.toCommand()));
        for (Output output : outputs) {
            if (output instanceof Output.AppendToLog append) {
                pendingWrites.put(append.entries().getLast().index(), request);
            }
        }
        execute(outputs);
    }

    private void execute(List<Output> outputs) {
        boolean outermost = !executing;
        executing = true;
        try {
            executeOutputs(outputs);
            if (outermost) maybeCompact();
        } finally {
            if (outermost) executing = false;
        }
    }

    /** Take a snapshot once the in-memory log grows past the threshold (Raft section 7). */
    private void maybeCompact() {
        if (core.logSize() < cluster.snapshotThreshold() || core.lastApplied() <= core.snapshot().index()) return;
        executeOutputs(core.compact(core.lastApplied(), stateMachine.snapshot()));
    }

    private void executeOutputs(List<Output> outputs) {
        for (Output output : outputs) {
            if (output instanceof Output.PersistHardState state) {
                try {
                    storage.persistHardState(new HardState(state.term(), state.votedFor(), state.commitIndex()));
                    storage.flush();
                } catch (IOException error) {
                    throw new IllegalStateException("simulated hard state persistence failed", error);
                }
            } else if (output instanceof Output.AppendToLog append) {
                try {
                    storage.append(append.entries());
                } catch (IOException error) {
                    throw new IllegalStateException("simulated WAL append failed", error);
                }
            } else if (output instanceof Output.TruncateLog truncate) {
                try {
                    storage.truncateFrom(truncate.fromIndex());
                } catch (IOException error) {
                    throw new IllegalStateException("simulated WAL truncation failed", error);
                }
            } else if (output instanceof Output.ApplyEntries apply) {
                flushStorage();
                for (LogEntry entry : apply.entries()) {
                    stateMachine.apply(entry);
                    answerWrite(entry);
                }
            } else if (output instanceof Output.Send send) {
                flushStorage();
                cluster.network().send(cluster.clock().now(), id, send.peerId(), send.message());
            } else if (output instanceof Output.ReadReady ready) {
                ClientRequest request = pendingReads.remove(ready.readId());
                if (request != null) replyWithRead(request);
            } else if (output instanceof Output.ReadFailed failed) {
                ClientRequest request = pendingReads.remove(failed.readId());
                if (request != null) reply(request, ClientReply.Status.WRONG_LEADER, null);
            } else if (output instanceof Output.SaveSnapshot save) {
                // Snapshot durable first, only then drop the WAL prefix it replaces.
                persistSnapshot(save.snapshot());
                cluster.countSnapshotTaken();
            } else if (output instanceof Output.ApplySnapshot install) {
                persistSnapshot(install.snapshot());
                stateMachine.restore(install.snapshot().data());
                answerWritesCoveredBy(install.snapshot().index());
                cluster.countSnapshotInstalled();
            }
        }
    }

    private void persistSnapshot(Snapshot snapshot) {
        try {
            storage.flush();                        // e.g. a TruncateLog that precedes ApplySnapshot
            storage.saveSnapshot(snapshot);
            storage.compactThrough(snapshot.index());
        } catch (IOException error) {
            throw new IllegalStateException("simulated snapshot persistence failed", error);
        }
    }

    /** Writes this node proposed whose index is now inside an installed snapshot: answer from sessions. */
    private void answerWritesCoveredBy(long snapshotIndex) {
        var iterator = pendingWrites.entrySet().iterator();
        while (iterator.hasNext()) {
            var pending = iterator.next();
            if (pending.getKey() > snapshotIndex) break;
            iterator.remove();
            ClientRequest request = pending.getValue();
            KvStateMachine.Result result = stateMachine.cachedResult(request.clientId(), request.sequence());
            if (result != null) reply(request, ClientReply.Status.OK, result.value());
            else reply(request, ClientReply.Status.WRONG_LEADER, null);
        }
    }

    /** Reply to the client whose write was proposed at this index, if this node proposed it. */
    private void answerWrite(LogEntry entry) {
        ClientRequest request = pendingWrites.remove(entry.index());
        if (request == null) return;
        KvCommand applied = entry.isConfig() || entry.command().length == 0
                ? null : KvCommandCodec.decode(entry.command());
        if (applied == null || applied.clientId() != request.clientId() || applied.sequence() != request.sequence()) {
            // A different leader's entry won this index; our proposal was lost. The client retries.
            reply(request, ClientReply.Status.WRONG_LEADER, null);
            return;
        }
        KvStateMachine.Result result = stateMachine.cachedResult(request.clientId(), request.sequence());
        if (result != null) reply(request, ClientReply.Status.OK, result.value());
    }

    private void replyWithRead(ClientRequest request) {
        KvStateMachine.Result result = stateMachine.read(request.key());
        reply(request, result.found() ? ClientReply.Status.OK : ClientReply.Status.NO_KEY, result.value());
    }

    private void reply(ClientRequest request, ClientReply.Status status, String value) {
        cluster.replyToClient(id, new ClientReply(request.clientId(), request.sequence(), status, value,
                core.leaderId()));
    }

    private void flushStorage() {
        try { storage.flush(); }
        catch (IOException error) { throw new IllegalStateException("simulated fsync failed", error); }
    }
}
