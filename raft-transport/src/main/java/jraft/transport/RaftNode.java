package jraft.transport;

import jraft.raftcore.Message;
import jraft.raftcore.Output;
import jraft.raftcore.RaftCore;

/** Executes core outputs in order; persistence outputs therefore precede their replies. */
public final class RaftNode {
    private final RaftCore core;
    private final Transport transport;
    private final OutputHandler outputHandler;

    public RaftNode(RaftCore core, Transport transport, OutputHandler outputHandler) {
        this.core = core;
        this.transport = transport;
        this.outputHandler = outputHandler;
    }

    public void tick() { execute(core.tick()); }
    public void receive(int from, Message message) { execute(core.step(from, message)); }
    public void propose(byte[] command) { execute(core.propose(command)); }

    private void execute(Iterable<Output> outputs) {
        for (Output output : outputs) {
            if (output instanceof Output.PersistHardState persist) {
                outputHandler.persistHardState(persist.term(), persist.votedFor(), persist.commitIndex());
            } else if (output instanceof Output.AppendToLog append) {
                outputHandler.appendToLog(append.entries());
            } else if (output instanceof Output.TruncateLog truncate) {
                outputHandler.truncateLog(truncate.fromIndex());
            } else if (output instanceof Output.ApplyEntries apply) {
                outputHandler.flush();
                outputHandler.applyEntries(apply.entries());
            } else if (output instanceof Output.Send send) {
                outputHandler.flush();
                transport.send(send.peerId(), send.message());
            }
        }
    }
}
