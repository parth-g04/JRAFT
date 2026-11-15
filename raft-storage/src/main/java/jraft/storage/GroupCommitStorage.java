package jraft.storage;

import jraft.raftcore.LogEntry;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Batches concurrent appends and forces them once per window or 256 entries. */
public final class GroupCommitStorage implements Storage {
    private static final int MAX_BATCH_ENTRIES = 256;
    private final Storage delegate;
    private final long windowNanos;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jraft-group-commit");
        thread.setDaemon(true);
        return thread;
    });
    private final List<LogEntry> pending = new ArrayList<>();
    private CompletableFuture<Void> activeBatch;
    private IOException failure;

    public GroupCommitStorage(Storage delegate, Duration window) {
        if (window.isNegative()) throw new IllegalArgumentException("group commit window cannot be negative");
        this.delegate = delegate;
        this.windowNanos = window.toNanos();
    }

    @Override public HardState loadHardState() throws IOException { flush(); return delegate.loadHardState(); }
    @Override public List<LogEntry> loadLog() throws IOException { flush(); return delegate.loadLog(); }

    @Override
    public void persistHardState(HardState hardState) throws IOException {
        flush();
        delegate.persistHardState(hardState);
        delegate.flush();
    }

    @Override
    public synchronized void append(List<LogEntry> entries) throws IOException {
        checkFailure();
        pending.addAll(entries);
        if (!entries.isEmpty()) scheduleIfNeeded(pending.size() >= MAX_BATCH_ENTRIES);
    }

    @Override
    public void truncateFrom(long index) throws IOException {
        flush();
        delegate.truncateFrom(index);
    }

    @Override
    public void flush() throws IOException {
        while (true) {
            CompletableFuture<Void> waitFor;
            synchronized (this) {
                checkFailure();
                if (pending.isEmpty() && activeBatch == null) {
                    delegate.flush();
                    return;
                }
                if (activeBatch == null) scheduleIfNeeded(true);
                waitFor = activeBatch;
            }
            try {
                waitFor.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for group commit", interrupted);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException("group commit failed", cause);
            }
        }
    }

    private synchronized void scheduleIfNeeded(boolean immediate) {
        if (activeBatch != null || pending.isEmpty()) return;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        activeBatch = completion;
        long delay = immediate ? 0 : windowNanos;
        worker.schedule(() -> commit(completion), delay, TimeUnit.NANOSECONDS);
    }

    private void commit(CompletableFuture<Void> completion) {
        // Drain when the window closes, not when it opens: entries appended while the
        // window was open join this batch and share its single fsync.
        List<LogEntry> batch;
        synchronized (this) {
            batch = List.copyOf(pending);
            pending.clear();
        }
        IOException commitFailure = null;
        try {
            delegate.append(batch);
            delegate.flush();
        } catch (IOException error) {
            commitFailure = error;
        }
        synchronized (this) {
            if (commitFailure != null) failure = commitFailure;
            if (activeBatch == completion) activeBatch = null;
            if (commitFailure == null && !pending.isEmpty()) {
                scheduleIfNeeded(pending.size() >= MAX_BATCH_ENTRIES);
            }
        }
        if (commitFailure == null) completion.complete(null);
        else completion.completeExceptionally(commitFailure);
    }

    private void checkFailure() throws IOException {
        if (failure != null) throw new IOException("storage is failed after a group commit error", failure);
    }

    @Override public long fsyncCount() { return delegate.fsyncCount(); }
    @Override public long fsyncNanos() { return delegate.fsyncNanos(); }

    @Override
    public void close() throws IOException {
        flush();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while closing group commit storage", interrupted);
        }
        delegate.close();
    }
}
