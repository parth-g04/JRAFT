package jraft.storage;

import static org.assertj.core.api.Assertions.assertThat;

import jraft.raftcore.LogEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStorageTest {
    @TempDir Path directory;

    @Test
    void hardStateAndLogSurviveReopen() throws Exception {
        try (FileStorage storage = new FileStorage(directory)) {
            storage.append(List.of(entry(1), entry(2)));
            storage.flush();
            storage.persistHardState(new HardState(2, 3, 1));
        }

        try (FileStorage recovered = new FileStorage(directory)) {
            assertThat(recovered.loadHardState()).isEqualTo(new HardState(2, 3, 1));
            assertThat(recovered.loadLog()).containsExactly(entry(1), entry(2));
        }
    }

    @Test
    void corruptTailIsTruncatedAndLaterRecordsAreNotRecovered() throws Exception {
        Path segment;
        try (FileStorage storage = new FileStorage(directory)) {
            storage.append(List.of(entry(1), entry(2)));
            storage.flush();
            segment = directory.resolve("wal").resolve("00000000000000000001.seg");
        }
        byte[] bytes = Files.readAllBytes(segment);
        bytes[bytes.length - 1] ^= 0x55;
        Files.write(segment, bytes);

        try (FileStorage recovered = new FileStorage(directory)) {
            assertThat(recovered.loadLog()).containsExactly(entry(1));
        }
        assertThat(Files.size(segment)).isLessThan((long) bytes.length);
    }

    @Test
    void truncationRemovesOnlyTheUncommittedSuffix() throws Exception {
        try (FileStorage storage = new FileStorage(directory)) {
            storage.append(List.of(entry(1), entry(2), entry(3)));
            storage.flush();
            storage.truncateFrom(3);
            storage.flush();
        }
        try (FileStorage recovered = new FileStorage(directory)) {
            assertThat(recovered.loadLog()).containsExactly(entry(1), entry(2));
        }
    }

    @Test
    void rollsAndRecoversMultipleWALSegments() throws Exception {
        try (FileStorage storage = new FileStorage(directory, 40)) {
            storage.append(List.of(entry(1), entry(2), entry(3)));
            storage.flush();
        }

        try (FileStorage recovered = new FileStorage(directory, 40)) {
            assertThat(recovered.loadLog()).containsExactly(entry(1), entry(2), entry(3));
            try (var files = Files.list(directory.resolve("wal"))) {
                assertThat(files.filter(path -> path.toString().endsWith(".seg")).count()).isEqualTo(3);
            }
        }
    }

    @Test
    void crashAtAnyByteDuringAppendRecoversAPrefixOfCompleteRecords() throws Exception {
        List<LogEntry> written = List.of(entry(1), entry(2), entry(3), entry(4));
        Path source = directory.resolve("source");
        try (FileStorage storage = new FileStorage(source)) {
            storage.append(written);
            storage.flush();
        }
        byte[] full = Files.readAllBytes(source.resolve("wal").resolve("00000000000000000001.seg"));

        // Simulate a crash at every possible byte offset of the in-flight write.
        for (int cut = 0; cut <= full.length; cut++) {
            Path crashed = directory.resolve("crash-" + cut);
            Files.createDirectories(crashed.resolve("wal"));
            Files.write(crashed.resolve("wal").resolve("00000000000000000001.seg"),
                    java.util.Arrays.copyOf(full, cut));

            try (FileStorage recovered = new FileStorage(crashed)) {
                List<LogEntry> log = recovered.loadLog();
                assertThat(log.size()).isLessThanOrEqualTo(written.size());
                assertThat(log).isEqualTo(written.subList(0, log.size()));
                // Recovery must leave the WAL appendable at the next index.
                recovered.append(List.of(new LogEntry(log.size() + 1, 2, new byte[]{7})));
                recovered.flush();
            }
            try (FileStorage reopened = new FileStorage(crashed)) {
                assertThat(reopened.loadLog().getLast().term()).isEqualTo(2);
            }
        }
    }

    @Test
    void recordsEveryFsyncInTheRaftFsyncTimer() throws Exception {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try (FileStorage storage = new FileStorage(directory, 1024, registry)) {
            storage.append(List.of(entry(1)));
            storage.flush();
            storage.persistHardState(new HardState(1, 1, 1));
            assertThat(registry.get("raft.fsync").timer().count()).isEqualTo(storage.fsyncCount()).isEqualTo(2);
        }
    }

    @Test
    void hardStateChecksumRejectsCorruption() throws Exception {
        HardStateFile file = new HardStateFile(directory.resolve("hardstate.bin"));
        file.write(new HardState(3, 1, 2));
        byte[] bytes = Files.readAllBytes(directory.resolve("hardstate.bin"));
        bytes[6] ^= 0x01;
        Files.write(directory.resolve("hardstate.bin"), bytes);

        org.assertj.core.api.Assertions.assertThatThrownBy(file::read).isInstanceOf(IOException.class);
    }

    private static LogEntry entry(long index) { return new LogEntry(index, 1, new byte[]{(byte) index}); }
}
