package jraft.transport;

import jraft.raftcore.LogEntry;

import java.util.List;

/** Side effects provided by storage and the replicated state machine. */
public interface OutputHandler {
    void persistHardState(long term, Integer votedFor, long commitIndex);
    void appendToLog(List<LogEntry> entries);
    void truncateLog(long fromIndex);
    void applyEntries(List<LogEntry> entries);
    default void flush() { }
    /** A ReadIndex read is safe to serve from the (already applied) state machine. */
    default void readReady(long readId, long readIndex) { }
    /** A ReadIndex read cannot be served here; reply WRONG_LEADER so the client retries. */
    default void readFailed(long readId) { }
}
