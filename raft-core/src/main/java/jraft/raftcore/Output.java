package jraft.raftcore;

import java.util.List;

public sealed interface Output permits Output.Send, Output.PersistHardState,
        Output.AppendToLog, Output.TruncateLog, Output.ApplyEntries,
        Output.ReadReady, Output.ReadFailed, Output.SaveSnapshot, Output.ApplySnapshot {
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
    /**
     * Make this locally taken snapshot durable, then discard WAL entries up to its index.
     * The state machine already reflects it.
     */
    record SaveSnapshot(Snapshot snapshot) implements Output { }
    /**
     * A snapshot received from the leader: make it durable, discard WAL entries up to its index,
     * then replace the state machine with it. Any conflicting log suffix was already removed by a
     * preceding TruncateLog.
     */
    record ApplySnapshot(Snapshot snapshot) implements Output { }
}
