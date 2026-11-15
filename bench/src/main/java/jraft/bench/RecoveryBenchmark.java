package jraft.bench;

import jraft.raftcore.LogEntry;
import jraft.raftcore.RaftCore;
import jraft.raftcore.Snapshot;
import jraft.sim.KvCommand;
import jraft.sim.KvCommandCodec;
import jraft.sim.KvStateMachine;
import jraft.storage.FileStorage;
import jraft.storage.HardState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Recovery time = open storage + rebuild the state machine, for N committed PUTs on 10,000 keys
 * with 100-byte values. Measured first with only the WAL (replay all N entries), then after a
 * snapshot at N - 1,000 and WAL compaction (restore snapshot + replay 1,000 entries).
 */
public final class RecoveryBenchmark {
    private static final int KEYS = 10_000;
    private static final int SUFFIX = 1_000;
    private static final int RUNS = 3;

    private RecoveryBenchmark() { }

    public static void main(String[] args) throws Exception {
        int entries = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        Path directory = Path.of("build", "recovery-bench");
        deleteRecursively(directory);
        Files.createDirectories(directory);

        long writeStart = System.nanoTime();
        try (FileStorage storage = new FileStorage(directory)) {
            List<LogEntry> batch = new ArrayList<>();
            for (int index = 1; index <= entries; index++) {
                batch.add(new LogEntry(index, 1, command(index)));
                if (batch.size() == 10_000 || index == entries) {
                    storage.append(batch);
                    storage.flush();
                    batch.clear();
                }
            }
            storage.persistHardState(new HardState(1, 1, entries));
        }
        double writeSeconds = (System.nanoTime() - writeStart) / 1e9;
        long walBytes = sizeOf(directory.resolve("wal"));

        System.out.println("mode,entries,replayed_entries,wal_mb,snapshot_kb,recovery_ms_median,recovery_ms_runs");
        long[] withoutSnapshot = new long[RUNS];
        byte[] snapshotState = null;
        for (int run = 0; run < RUNS; run++) {
            Recovery recovery = recover(directory, entries - SUFFIX);
            withoutSnapshot[run] = recovery.millis();
            snapshotState = recovery.stateAtSnapshotPoint();
        }
        report("wal-only", entries, entries, walBytes, 0, withoutSnapshot);

        // Take the snapshot at N - 1000 and compact, as a running node would.
        try (FileStorage storage = new FileStorage(directory)) {
            storage.saveSnapshot(new Snapshot(entries - SUFFIX, 1, snapshotState));
            storage.compactThrough(entries - SUFFIX);
            storage.flush();
        }
        long compactedWal = sizeOf(directory.resolve("wal"));
        long[] withSnapshot = new long[RUNS];
        for (int run = 0; run < RUNS; run++) withSnapshot[run] = recover(directory, -1).millis();
        report("snapshot+suffix", entries, SUFFIX, compactedWal, snapshotState.length, withSnapshot);
        System.out.printf("# wrote %d entries (%.1f MB) in %.1f s; compacted WAL keeps %.1f MB%n",
                entries, walBytes / 1e6, writeSeconds, compactedWal / 1e6);
        deleteRecursively(directory);
    }

    private record Recovery(long millis, byte[] stateAtSnapshotPoint) { }

    /** What a restarting node does: load snapshot, restore, replay committed entries after it. */
    private static Recovery recover(Path directory, long captureAt) throws IOException {
        long started = System.nanoTime();
        byte[] captured = null;
        KvStateMachine machine = new KvStateMachine();
        try (FileStorage storage = new FileStorage(directory)) {
            HardState hardState = storage.loadHardState();
            Snapshot snapshot = storage.loadSnapshot();
            machine.restore(snapshot.data());
            for (LogEntry entry : RaftCore.entriesAfter(snapshot, storage.loadLog())) {
                if (entry.index() > hardState.commitIndex()) break;
                machine.apply(entry);
                if (entry.index() == captureAt) {
                    long pause = System.nanoTime();
                    captured = machine.snapshot();              // not part of the measured time
                    started += System.nanoTime() - pause;
                }
            }
        }
        return new Recovery((System.nanoTime() - started) / 1_000_000, captured);
    }

    private static byte[] command(int index) {
        char[] value = new char[100];
        Arrays.fill(value, (char) ('a' + index % 26));
        return KvCommandCodec.encode(new KvCommand(1, index, KvCommand.Operation.PUT, "k" + (index % KEYS), new String(value)));
    }

    private static void report(String mode, int entries, int replayed, long walBytes, long snapshotBytes, long[] runs) {
        long[] sorted = runs.clone();
        Arrays.sort(sorted);
        System.out.printf("%s,%d,%d,%.1f,%.1f,%d,%s%n", mode, entries, replayed, walBytes / 1e6, snapshotBytes / 1e3,
                sorted[sorted.length / 2], Arrays.toString(runs).replace(", ", "/"));
    }

    private static long sizeOf(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(path -> path.toFile().length()).sum();
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
