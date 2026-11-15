package jraft.storage;

import jraft.raftcore.LogEntry;
import jraft.raftcore.Snapshot;

import java.io.IOException;
import java.util.List;

/**
 * Durable Raft state boundary. append/truncate/compact are buffered until flush; persistHardState
 * and saveSnapshot are durable when they return.
 *
 * After a snapshot at index i, the log holds only entries i+1 ..; {@link #loadLog()} returns them.
 * Recovery finishes a compaction that a crash interrupted, so loadLog never returns entries
 * already covered by {@link #loadSnapshot()}.
 */
public interface Storage extends AutoCloseable {
    HardState loadHardState() throws IOException;
    List<LogEntry> loadLog() throws IOException;
    /** Latest durable snapshot, or {@link Snapshot#EMPTY}. */
    Snapshot loadSnapshot() throws IOException;
    void persistHardState(HardState hardState) throws IOException;
    void append(List<LogEntry> entries) throws IOException;
    void truncateFrom(long index) throws IOException;
    /** Atomically and durably replaces the snapshot (write temp, fsync, rename). */
    void saveSnapshot(Snapshot snapshot) throws IOException;
    /** Discards log entries up to and including {@code index}; the next append is index+1 or later. */
    void compactThrough(long index) throws IOException;
    void flush() throws IOException;
    long fsyncCount();
    long fsyncNanos();
    @Override default void close() throws IOException { flush(); }
}
