package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;

import jraft.raftcore.LogEntry;
import jraft.storage.HardState;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SimDiskTest {
    @Test
    void crashKeepsOnlyWritesThatCrossedFlush() throws Exception {
        for (int durableEntries = 0; durableEntries <= 3; durableEntries++) {
            SimDisk disk = new SimDisk();
            for (int index = 1; index <= durableEntries; index++) {
                disk.append(List.of(new LogEntry(index, 1, new byte[]{(byte) index})));
                disk.flush();
            }
            disk.persistHardState(new HardState(1, 1, durableEntries));
            disk.flush();
            for (int index = durableEntries + 1; index <= 3; index++) {
                disk.append(List.of(new LogEntry(index, 1, new byte[]{(byte) index})));
            }
            disk.crash();

            assertThat(disk.loadLog()).hasSize(durableEntries);
            assertThat(disk.loadHardState().commitIndex()).isEqualTo(durableEntries);
        }
    }

    @Test
    void tornCrashKeepsSyncedEntriesPlusAContiguousPrefixOfUnsyncedOnes() throws Exception {
        Random random = new Random(42);
        for (int trial = 0; trial < 200; trial++) {
            SimDisk disk = new SimDisk();
            disk.append(List.of(entry(1), entry(2)));
            disk.persistHardState(new HardState(1, 1, 2));
            disk.flush();
            disk.append(List.of(entry(3), entry(4), entry(5)));
            disk.crash(random);

            List<LogEntry> log = disk.loadLog();
            assertThat(log.size()).isBetween(2, 5);
            for (int i = 0; i < log.size(); i++) assertThat(log.get(i)).isEqualTo(entry(i + 1));
            assertThat(disk.loadHardState()).isEqualTo(new HardState(1, 1, 2));
        }
    }

    @Test
    void tornCrashNeverResurrectsEntriesRemovedByAnUnsyncedTruncation() throws Exception {
        SimDisk disk = new SimDisk();
        disk.append(List.of(entry(1), entry(2)));
        disk.flush();
        disk.truncateFrom(2);
        disk.append(List.of(new LogEntry(2, 2, new byte[]{9}), new LogEntry(3, 2, new byte[]{9})));
        disk.crash(new Random(1));

        assertThat(disk.loadLog()).containsExactly(entry(1), entry(2));
    }

    private static LogEntry entry(int index) { return new LogEntry(index, 1, new byte[]{(byte) index}); }
}
