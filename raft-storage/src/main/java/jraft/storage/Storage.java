package jraft.storage;

import jraft.raftcore.LogEntry;

import java.io.IOException;
import java.util.List;

/** Durable Raft state boundary. append/truncate are buffered until flush. */
public interface Storage extends AutoCloseable {
    HardState loadHardState() throws IOException;
    List<LogEntry> loadLog() throws IOException;
    void persistHardState(HardState hardState) throws IOException;
    void append(List<LogEntry> entries) throws IOException;
    void truncateFrom(long index) throws IOException;
    void flush() throws IOException;
    long fsyncCount();
    long fsyncNanos();
    @Override default void close() throws IOException { flush(); }
}
