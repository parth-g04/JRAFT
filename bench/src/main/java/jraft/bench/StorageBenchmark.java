package jraft.bench;

import jraft.raftcore.LogEntry;
import jraft.storage.FileStorage;
import jraft.storage.GroupCommitStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Group-commit microbenchmark. WRITERS threads each append one 100-byte entry and then wait
 * for it to be durable (append + flush), like concurrent client writes on a leader. A longer
 * window lets more writers share one fsync. Output is CSV on stdout.
 */
public final class StorageBenchmark {
    private static final int WRITERS = 16;
    private static final int WRITES_PER_WRITER = 64;

    private StorageBenchmark() { }

    public static void main(String[] args) throws Exception {
        System.out.println("writers,entries,window_ms,elapsed_ms,ops_per_sec,fsync_count,entries_per_fsync,avg_fsync_us");
        for (int windowMillis : List.of(0, 1, 2, 5)) runWindow(windowMillis);
    }

    private static void runWindow(int windowMillis) throws Exception {
        Files.createDirectories(Path.of("build"));
        Path directory = Files.createTempDirectory(Path.of("build"), "group-commit-");
        Object indexLock = new Object();
        long[] nextIndex = {1};
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        long fsyncs;
        long fsyncNanos;
        long started;
        try (GroupCommitStorage storage = new GroupCommitStorage(new FileStorage(directory),
                Duration.ofMillis(windowMillis))) {
            for (int w = 0; w < WRITERS; w++) {
                Thread writer = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < WRITES_PER_WRITER; i++) {
                            // Index allocation and append must happen together so the WAL stays contiguous.
                            synchronized (indexLock) {
                                storage.append(List.of(new LogEntry(nextIndex[0]++, 1, new byte[100])));
                            }
                            storage.flush();
                        }
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
                writer.start();
                writers.add(writer);
            }
            started = System.nanoTime();
            start.countDown();
            for (Thread writer : writers) writer.join();
            fsyncs = storage.fsyncCount();
            fsyncNanos = storage.fsyncNanos();
        }
        long elapsedNanos = System.nanoTime() - started;
        int entries = WRITERS * WRITES_PER_WRITER;
        System.out.printf("%d,%d,%d,%.1f,%.0f,%d,%.1f,%d%n", WRITERS, entries, windowMillis,
                elapsedNanos / 1e6, entries / (elapsedNanos / 1e9), fsyncs,
                fsyncs == 0 ? 0.0 : (double) entries / fsyncs, fsyncs == 0 ? 0 : fsyncNanos / fsyncs / 1000);
    }
}
