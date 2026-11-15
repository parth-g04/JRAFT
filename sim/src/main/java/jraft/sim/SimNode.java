package jraft.sim;

import jraft.raftcore.LogEntry;
import jraft.raftcore.Message;
import jraft.raftcore.NodeRole;
import jraft.raftcore.Output;
import jraft.raftcore.RaftCore;
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

    SimNode(int id, SimCluster cluster, List<Integer> peers, Storage storage) {
        this.id = id;
        this.cluster = cluster;
        this.storage = storage;
        try {
            HardState hardState = storage.loadHardState();
            List<LogEntry> recovered = storage.loadLog();
            this.core = new RaftCore(id, peers, 9 + id * 2, 2, hardState.currentTerm(),
                    hardState.votedFor(), hardState.commitIndex(), recovered);
            // Replaying committed entries also rebuilds the client session table.
            for (int i = 0; i < hardState.commitIndex(); i++) stateMachine.apply(recovered.get(i));
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
            + "/commit=" + core.commitIndex() + "/last=" + core.logEntries().size()
            + "/disk=" + ((SimDisk) storage).describe(); }

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
            }
        }
    }

    /** Reply to the client whose write was proposed at this index, if this node proposed it. */
    private void answerWrite(LogEntry entry) {
        ClientRequest request = pendingWrites.remove(entry.index());
        if (request == null) return;
        KvCommand applied = entry.command().length == 0 ? null : KvCommandCodec.decode(entry.command());
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
