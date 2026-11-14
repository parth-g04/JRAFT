package jraft.transport;

import jraft.raftcore.LogEntry;

import java.util.List;

/** Side effects provided by storage and the replicated state machine. */
public interface OutputHandler {
    void persistHardState(long term, Integer votedFor, long commitIndex);
    void appendToLog(List<LogEntry> entries);
    void truncateLog(long fromIndex);
    void applyEntries(List<LogEntry> entries);
}
