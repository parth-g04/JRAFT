package jraft.transport;

import jraft.raftcore.LogEntry;
import jraft.storage.HardState;
import jraft.storage.Storage;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Bridges ordered core outputs to durable storage and the state machine. */
public final class StorageOutputHandler implements OutputHandler {
    private final Storage storage;
    private final Consumer<List<LogEntry>> apply;

    public StorageOutputHandler(Storage storage, Consumer<List<LogEntry>> apply) {
        this.storage = storage;
        this.apply = apply;
    }

    @Override
    public void persistHardState(long term, Integer votedFor, long commitIndex) {
        try {
            storage.persistHardState(new HardState(term, votedFor, commitIndex));
            storage.flush();
        } catch (IOException error) {
            throw new IllegalStateException("hard state persistence failed", error);
        }
    }

    @Override
    public void appendToLog(List<LogEntry> entries) {
        try { storage.append(entries); }
        catch (IOException error) { throw new IllegalStateException("WAL append failed", error); }
    }

    @Override
    public void truncateLog(long fromIndex) {
        try { storage.truncateFrom(fromIndex); }
        catch (IOException error) { throw new IllegalStateException("WAL truncation failed", error); }
    }

    @Override public void applyEntries(List<LogEntry> entries) { apply.accept(entries); }

    @Override
    public void flush() {
        try { storage.flush(); }
        catch (IOException error) { throw new IllegalStateException("WAL fsync failed", error); }
    }
}
