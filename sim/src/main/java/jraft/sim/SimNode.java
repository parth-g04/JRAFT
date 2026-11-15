package jraft.sim;

import jraft.raftcore.LogEntry;
import jraft.raftcore.Message;
import jraft.raftcore.Output;
import jraft.raftcore.RaftCore;
import jraft.storage.HardState;
import jraft.storage.Storage;

import java.io.IOException;
import java.util.List;

/** Single-threaded Raft node whose side effects stay inside SimCluster. */
final class SimNode {
    private final int id;
    private final SimCluster cluster;
    private final RaftCore core;
    private final KvStateMachine stateMachine = new KvStateMachine();
    private final Storage storage;

    SimNode(int id, SimCluster cluster, List<Integer> peers, Storage storage) {
        this.id = id;
        this.cluster = cluster;
        this.storage = storage;
        try {
            HardState hardState = storage.loadHardState();
            List<LogEntry> recovered = storage.loadLog();
            this.core = new RaftCore(id, peers, 9 + id * 2, 2, hardState.currentTerm(),
                    hardState.votedFor(), hardState.commitIndex(), recovered);
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
                apply.entries().forEach(stateMachine::apply);
            } else if (output instanceof Output.Send send) {
                flushStorage();
                cluster.network().send(cluster.clock().now(), id, send.peerId(), send.message());
            }
        }
    }

    private void flushStorage() {
        try { storage.flush(); }
        catch (IOException error) { throw new IllegalStateException("simulated fsync failed", error); }
    }
}
