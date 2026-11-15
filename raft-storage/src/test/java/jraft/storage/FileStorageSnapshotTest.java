package jraft.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jraft.raftcore.LogEntry;
import jraft.raftcore.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStorageSnapshotTest {
    @TempDir Path directory;

    private static final long SEGMENT_BYTES = 200; // ~6 records per segment

    @Test
    void snapshotThenCompactionSurvivesReopenAndDeletesCoveredSegments() throws Exception {
        try (FileStorage storage = new FileStorage(directory, SEGMENT_BYTES)) {
            storage.append(entries(1, 40));
            storage.flush();
            storage.saveSnapshot(snapshot(25));
            storage.compactThrough(25);
            storage.flush();
            assertThat(storage.loadLog()).extracting(LogEntry::index).first().isEqualTo(26L);
        }
        try (FileStorage reopened = new FileStorage(directory, SEGMENT_BYTES)) {
            assertThat(reopened.loadSnapshot()).isEqualTo(snapshot(25));
            assertThat(reopened.loadLog()).isEqualTo(entries(26, 40));
            // Appends continue at the right index after recovery.
            reopened.append(entries(41, 42));
            reopened.flush();
        }
        for (Path segment : segments()) {
            long first = Long.parseLong(segment.getFileName().toString().replace(".seg", ""));
            assertThat(first).as("segments wholly <= 25 are gone").isGreaterThan(20);
        }
        try (FileStorage reopened = new FileStorage(directory, SEGMENT_BYTES)) {
            assertThat(reopened.loadLog()).isEqualTo(entries(26, 42));
        }
    }

    @Test
    void crashWhileWritingTheSnapshotKeepsTheOldStateAndFullLog() throws Exception {
        try (FileStorage storage = new FileStorage(directory, SEGMENT_BYTES)) {
            storage.append(entries(1, 30));
            storage.flush();
            storage.saveSnapshot(snapshot(10));
            storage.compactThrough(10);
            storage.flush();
        }
        // Crash in the middle of writing the next snapshot: only a partial .tmp file exists.
        Path snapshots = directory.resolve("snapshots");
        Files.write(snapshots.resolve("snap-00000000000000000020-1.bin.tmp"), new byte[]{1, 2, 3, 4, 5});

        try (FileStorage recovered = new FileStorage(directory, SEGMENT_BYTES)) {
            assertThat(recovered.loadSnapshot()).isEqualTo(snapshot(10));
            assertThat(recovered.loadLog()).isEqualTo(entries(11, 30));
        }
    }

    @Test
    void crashAfterTheSnapshotButBeforeCompactionLosesNothing() throws Exception {
        try (FileStorage storage = new FileStorage(directory, SEGMENT_BYTES)) {
            storage.append(entries(1, 30));
            storage.flush();
            storage.saveSnapshot(snapshot(20));
            // crash: compactThrough(20) never ran, all WAL segments are still there
        }
        try (FileStorage recovered = new FileStorage(directory, SEGMENT_BYTES)) {
            assertThat(recovered.loadSnapshot()).isEqualTo(snapshot(20));
            assertThat(recovered.loadLog()).isEqualTo(entries(21, 30));
        }
        // Recovery finished the interrupted compaction: no segment ends at or before index 20.
        assertThat(segmentFirstIndexes()).allMatch(first -> first > 15);
    }

    @Test
    void snapshotBeyondTheWholeLogEmptiesTheWal() throws Exception {
        // InstallSnapshot on a short log: the snapshot covers more than the WAL ever had.
        try (FileStorage storage = new FileStorage(directory, SEGMENT_BYTES)) {
            storage.append(entries(1, 5));
            storage.flush();
            storage.saveSnapshot(snapshot(50));
        }
        try (FileStorage recovered = new FileStorage(directory, SEGMENT_BYTES)) {
            assertThat(recovered.loadLog()).isEmpty();
            recovered.append(entries(51, 52));
            recovered.flush();
        }
        try (FileStorage recovered = new FileStorage(directory, SEGMENT_BYTES)) {
            assertThat(recovered.loadLog()).isEqualTo(entries(51, 52));
        }
    }

    @Test
    void corruptNewestSnapshotFallsBackToAnOlderValidOne() throws Exception {
        Path snapshots = directory.resolve("snapshots");
        try (FileStorage storage = new FileStorage(directory, SEGMENT_BYTES)) {
            storage.append(entries(1, 30));
            storage.flush();
            storage.saveSnapshot(snapshot(10));
        }
        Path older = Files.list(snapshots).filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow();
        Path kept = snapshots.resolve("keep.bin.copy");
        Files.copy(older, kept);
        try (FileStorage storage = new FileStorage(directory, SEGMENT_BYTES)) {
            storage.saveSnapshot(snapshot(20)); // deletes the older file
        }
        Files.move(kept, older);              // restore it so both exist
        Path newest = snapshots.resolve("snap-00000000000000000020-1.bin");
        byte[] bytes = Files.readAllBytes(newest);
        bytes[bytes.length - 5] ^= 0x7F;      // bit rot inside the data
        Files.write(newest, bytes);

        try (FileStorage recovered = new FileStorage(directory, SEGMENT_BYTES)) {
            assertThat(recovered.loadSnapshot()).isEqualTo(snapshot(10));
            assertThat(recovered.loadLog()).isEqualTo(entries(11, 30));
        }
    }

    @Test
    void truncateAfterCompactionWorksAcrossSegments() throws Exception {
        try (FileStorage storage = new FileStorage(directory, SEGMENT_BYTES)) {
            storage.append(entries(1, 40));
            storage.saveSnapshot(snapshot(12));
            storage.compactThrough(12);
            storage.truncateFrom(31);
            storage.append(List.of(new LogEntry(31, 2, new byte[]{9})));
            storage.flush();
        }
        try (FileStorage recovered = new FileStorage(directory, SEGMENT_BYTES)) {
            List<LogEntry> log = recovered.loadLog();
            assertThat(log.getFirst().index()).isEqualTo(13);
            assertThat(log.getLast()).isEqualTo(new LogEntry(31, 2, new byte[]{9}));
            assertThat(log).hasSize(19);
        }
    }

    // ---------------- helpers ----------------

    private static Snapshot snapshot(long index) {
        return new Snapshot(index, 1, ("state@" + index).getBytes());
    }

    private static List<LogEntry> entries(long from, long to) {
        List<LogEntry> list = new ArrayList<>();
        for (long i = from; i <= to; i++) list.add(new LogEntry(i, 1, ("cmd" + i).getBytes()));
        return list;
    }

    private List<Path> segments() throws Exception {
        try (var files = Files.list(directory.resolve("wal"))) {
            return files.filter(p -> p.toString().endsWith(".seg")).sorted().toList();
        }
    }

    private List<Long> segmentFirstIndexes() throws Exception {
        List<Long> firsts = new ArrayList<>();
        for (Path segment : segments()) firsts.add(Long.parseLong(segment.getFileName().toString().replace(".seg", "")));
        return firsts;
    }
}
