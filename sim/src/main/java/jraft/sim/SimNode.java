package jraft.sim;

import jraft.raftcore.LogEntry;
import jraft.raftcore.Message;
import jraft.raftcore.Output;
import jraft.raftcore.RaftCore;

import java.util.ArrayList;
import java.util.List;

/** Single-threaded Raft node whose side effects stay inside SimCluster. */
final class SimNode {
    private final int id;
    private final SimCluster cluster;
    private final RaftCore core;
    private final KvStateMachine stateMachine = new KvStateMachine();
    private final List<LogEntry> memoryLog = new ArrayList<>();
    private long persistedTerm;
    private Integer persistedVote;

    SimNode(int id, SimCluster cluster, List<Integer> peers) {
        this.id = id;
        this.cluster = cluster;
        this.core = new RaftCore(id, peers, 9 + id * 2, 2);
    }

    void tick() { execute(core.tick()); }
    void receive(int from, Message message) { execute(core.step(from, message)); }
    void propose(byte[] command) { execute(core.propose(command)); }
    RaftCore core() { return core; }
    KvStateMachine stateMachine() { return stateMachine; }

    private void execute(List<Output> outputs) {
        for (Output output : outputs) {
            if (output instanceof Output.PersistHardState state) {
                persistedTerm = state.term();
                persistedVote = state.votedFor();
            } else if (output instanceof Output.AppendToLog append) {
                for (LogEntry entry : append.entries()) {
                    if (entry.index() != memoryLog.size() + 1) throw new IllegalStateException("non-contiguous simulated log");
                    memoryLog.add(entry);
                }
            } else if (output instanceof Output.TruncateLog truncate) {
                while (memoryLog.size() >= truncate.fromIndex()) memoryLog.remove(memoryLog.size() - 1);
            } else if (output instanceof Output.ApplyEntries apply) {
                apply.entries().forEach(stateMachine::apply);
            } else if (output instanceof Output.Send send) {
                cluster.network().send(cluster.clock().now(), id, send.peerId(), send.message());
            }
        }
    }

    long persistedTerm() { return persistedTerm; }
    Integer persistedVote() { return persistedVote; }
}
