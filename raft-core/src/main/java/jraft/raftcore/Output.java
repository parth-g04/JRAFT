package jraft.raftcore;

import java.util.List;

public sealed interface Output permits Output.Send, Output.PersistHardState,
        Output.AppendToLog, Output.TruncateLog, Output.ApplyEntries,
        Output.ReadReady, Output.ReadFailed {
    record Send(int peerId, Message message) implements Output { }
    record PersistHardState(long term, Integer votedFor, long commitIndex) implements Output { }
    record AppendToLog(List<LogEntry> entries) implements Output {
        public AppendToLog { entries = List.copyOf(entries); }
    }
    record TruncateLog(long fromIndex) implements Output { }
    record ApplyEntries(List<LogEntry> entries) implements Output {
        public ApplyEntries { entries = List.copyOf(entries); }
    }
    /**
     * A linearizable read may now be served from the state machine. Emitted only after every
     * entry up to {@code readIndex} has been handed out in an earlier ApplyEntries output.
     */
    record ReadReady(long readId, long readIndex) implements Output { }
    /** The read cannot be served here (not leader, or leadership lost); the client should retry. */
    record ReadFailed(long readId) implements Output { }
}
