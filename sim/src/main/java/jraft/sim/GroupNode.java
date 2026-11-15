package jraft.sim;

import jraft.raftcore.Configuration;
import jraft.raftcore.LogEntry;
import jraft.raftcore.Message;
import jraft.raftcore.NodeRole;
import jraft.raftcore.Output;
import jraft.raftcore.RaftCore;
import jraft.raftcore.Snapshot;
import jraft.raftcore.StateMachine;
import jraft.storage.HardState;
import jraft.storage.Storage;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Generic simulated Raft server for the sharded world: RaftCore + durable SimDisk + a pluggable
 * {@link Service} (shard controller or shard KV group). It executes RaftCore outputs in order
 * (persist before send), snapshots at a log-size threshold, and offers the service two primitives:
 * propose a command and get its apply result, and run a linearizable read via ReadIndex.
 */
final class GroupNode {
    /** The application running on top of Raft in one group. */
    interface Service {
        StateMachine stateMachine();
        /** Apply a committed entry; the return value is handed to whoever proposed it. */
        Object apply(LogEntry entry);
        void onMessage(GroupNode node, int from, Object message);
        default void leaderTick(GroupNode node, long now) { }
        default void onBecameLeader() { }
    }

    private record PendingProposal(byte[] command, Consumer<Object> onApplied, Runnable onLost) { }
    private record PendingRead(Runnable onReady, Runnable onFailed) { }

    private final int id;
    private final ShardedWorld world;
    private final Storage storage;
    private final Service service;
    private final RaftCore core;
    private final int snapshotThreshold;
    private final Map<Long, PendingProposal> proposals = new TreeMap<>();
    private final Map<Long, PendingRead> reads = new TreeMap<>();
    private long nextReadId;
    private boolean executing;
    private boolean wasLeader;

    GroupNode(int id, ShardedWorld world, Configuration members, Storage storage, Service service,
              int snapshotThreshold, int snapshotChunkBytes) {
        this.id = id;
        this.world = world;
        this.storage = storage;
        this.service = service;
        this.snapshotThreshold = snapshotThreshold;
        try {
            HardState hardState = storage.loadHardState();
            Snapshot snapshot = storage.loadSnapshot();
            List<LogEntry> recovered = RaftCore.entriesAfter(snapshot, storage.loadLog());
            core = new RaftCore(id, members, 9 + (id % 10) * 2, 2, hardState.currentTerm(), hardState.votedFor(),
                    hardState.commitIndex(), snapshot, recovered).configure(8, snapshotChunkBytes);
            service.stateMachine().restore(snapshot.data());
            for (LogEntry entry : recovered) {
                if (entry.index() > core.commitIndex()) break;
                service.apply(entry);
            }
        } catch (IOException error) {
            throw new IllegalStateException("could not recover node " + id, error);
        }
    }

    // ---------------- driven by the world ----------------

    void tick() {
        execute(core.tick());
        if (isLeader()) service.leaderTick(this, world.clock().now());
    }

    void receive(int from, Message message) { execute(core.step(from, message)); }
    void onAppMessage(int from, Object message) { service.onMessage(this, from, message); }

    // ---------------- primitives for services ----------------

    boolean isLeader() { return core.role() == NodeRole.LEADER; }
    int id() { return id; }
    long now() { return world.clock().now(); }
    RaftCore core() { return core; }
    Service service() { return service; }
    void send(int to, Object message) { world.sendApp(id, to, message); }

    /** Proposes a command; exactly one callback runs once its log index is applied (or lost). */
    boolean propose(byte[] command, Consumer<Object> onApplied, Runnable onLost) {
        List<Output> outputs = core.propose(command);
        for (Output output : outputs) {
            if (output instanceof Output.AppendToLog append) {
                proposals.put(append.entries().getLast().index(), new PendingProposal(command, onApplied, onLost));
            }
        }
        execute(outputs);
        return !outputs.isEmpty();
    }

    /** Runs {@code onReady} once a ReadIndex read is safe, or {@code onFailed} if leadership is lost. */
    void read(Runnable onReady, Runnable onFailed) {
        long readId = ++nextReadId;
        reads.put(readId, new PendingRead(onReady, onFailed));
        execute(core.readIndex(readId));
    }

    // ---------------- output execution (persist before send) ----------------

    private void execute(List<Output> outputs) {
        boolean outermost = !executing;
        executing = true;
        try {
            executeOutputs(outputs);
            if (outermost && core.logSize() >= snapshotThreshold && core.lastApplied() > core.snapshot().index()) {
                executeOutputs(core.compact(core.lastApplied(), service.stateMachine().snapshot()));
            }
        } finally {
            if (outermost) executing = false;
        }
        boolean leader = isLeader();
        if (leader && !wasLeader) service.onBecameLeader();
        wasLeader = leader;
    }

    private void executeOutputs(List<Output> outputs) {
        for (Output output : outputs) {
            try {
                switch (output) {
                    case Output.PersistHardState state -> {
                        storage.persistHardState(new HardState(state.term(), state.votedFor(), state.commitIndex()));
                        storage.flush();
                    }
                    case Output.AppendToLog append -> storage.append(append.entries());
                    case Output.TruncateLog truncate -> storage.truncateFrom(truncate.fromIndex());
                    case Output.ApplyEntries apply -> {
                        storage.flush();
                        for (LogEntry entry : apply.entries()) {
                            Object result = service.apply(entry);
                            PendingProposal pending = proposals.remove(entry.index());
                            if (pending == null) continue;
                            if (!entry.isConfig() && Arrays.equals(entry.command(), pending.command())) pending.onApplied().accept(result);
                            else pending.onLost().run();   // another leader's entry won this index
                        }
                    }
                    case Output.Send send -> {
                        storage.flush();
                        world.network().send(world.clock().now(), id, send.peerId(), send.message());
                    }
                    case Output.ReadReady ready -> {
                        PendingRead read = reads.remove(ready.readId());
                        if (read != null) read.onReady().run();
                    }
                    case Output.ReadFailed failed -> {
                        PendingRead read = reads.remove(failed.readId());
                        if (read != null) read.onFailed().run();
                    }
                    case Output.SaveSnapshot save -> persistSnapshot(save.snapshot());
                    case Output.ApplySnapshot install -> {
                        persistSnapshot(install.snapshot());
                        service.stateMachine().restore(install.snapshot().data());
                        // Proposals inside the snapshot: we cannot tell their result; the client retries.
                        var iterator = proposals.entrySet().iterator();
                        while (iterator.hasNext()) {
                            var pending = iterator.next();
                            if (pending.getKey() > install.snapshot().index()) break;
                            iterator.remove();
                            pending.getValue().onLost().run();
                        }
                        world.countSnapshotInstalled();
                    }
                }
            } catch (IOException error) {
                throw new IllegalStateException("simulated storage failed on node " + id, error);
            }
        }
    }

    private void persistSnapshot(Snapshot snapshot) throws IOException {
        storage.flush();
        storage.saveSnapshot(snapshot);
        storage.compactThrough(snapshot.index());
    }

    String describe() {
        return id + ":" + core.role() + "/t" + core.currentTerm() + "/c" + core.commitIndex() + "/s" + core.snapshot().index();
    }
}
