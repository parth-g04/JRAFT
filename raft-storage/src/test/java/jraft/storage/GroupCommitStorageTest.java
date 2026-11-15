package jraft.storage;

import static org.assertj.core.api.Assertions.assertThat;

import jraft.raftcore.LogEntry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class GroupCommitStorageTest {
    @TempDir Path directory;

    @Test
    void forcesAWholeAppendBatchOnce() throws Exception {
        FileStorage fileStorage = new FileStorage(directory);
        try (GroupCommitStorage storage = new GroupCommitStorage(fileStorage, Duration.ZERO)) {
            storage.append(List.of(new LogEntry(1, 1, new byte[]{1}), new LogEntry(2, 1, new byte[]{2})));
            storage.flush();
            assertThat(storage.fsyncCount()).isEqualTo(1);
            assertThat(storage.loadLog()).hasSize(2);
        }
    }

    @Test
    void appendsInsideTheSameWindowShareOneFsync() throws Exception {
        // Regression: the batch used to be captured when the window opened, so the second
        // append missed it and paid for its own fsync after a second full window.
        FileStorage fileStorage = new FileStorage(directory);
        try (GroupCommitStorage storage = new GroupCommitStorage(fileStorage, Duration.ofMillis(200))) {
            storage.append(List.of(new LogEntry(1, 1, new byte[]{1})));
            storage.append(List.of(new LogEntry(2, 1, new byte[]{2})));
            storage.flush();
            assertThat(storage.fsyncCount()).isEqualTo(1);
            assertThat(storage.loadLog()).hasSize(2);
        }
    }
}
