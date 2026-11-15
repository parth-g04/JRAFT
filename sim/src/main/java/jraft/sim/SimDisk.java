package jraft.sim;

import jraft.raftcore.LogEntry;
import jraft.storage.HardState;
import jraft.storage.Storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Stable simulated files: flush copies pending writes to durable state.
 * On crash, unsynced writes are lost; optionally a random prefix of unsynced appends
 * survives (the OS flushed some pages) and the torn record after it is discarded, which
 * is what FileStorage's CRC tail truncation produces on a real disk.
 */
public final class SimDisk implements Storage {
    private HardState durableHardState = HardState.empty();
    private HardState pendingHardState;
    private List<LogEntry> durableLog = new ArrayList<>();
    private List<LogEntry> workingLog = new ArrayList<>();
    private boolean dirty;
    /** True when a truncation happened since the last flush, so the unsynced tail is not a pure append. */
    private boolean truncatedSinceFlush;
    private long fsyncCount;

    @Override public HardState loadHardState() { return durableHardState; }
    @Override public List<LogEntry> loadLog() { return List.copyOf(durableLog); }

    @Override
    public void persistHardState(HardState hardState) {
        pendingHardState = hardState;
        dirty = true;
    }

    @Override
    public void append(List<LogEntry> entries) throws IOException {
        for (LogEntry entry : entries) {
            long expected = workingLog.size() + 1L;
            if (entry.index() != expected) throw new IOException("expected simulated WAL index " + expected);
            workingLog.add(entry);
        }
        if (!entries.isEmpty()) dirty = true;
    }

    @Override
    public void truncateFrom(long index) throws IOException {
        if (index < 1) throw new IllegalArgumentException("WAL indexes start at 1");
        if (index > workingLog.size()) return;
        while (workingLog.size() >= index) workingLog.remove(workingLog.size() - 1);
        dirty = true;
        truncatedSinceFlush = true;
    }

    @Override
    public void flush() {
        if (!dirty) return;
        if (pendingHardState != null) {
            durableHardState = pendingHardState;
            pendingHardState = null;
        }
        durableLog = new ArrayList<>(workingLog);
        fsyncCount++;
        dirty = false;
        truncatedSinceFlush = false;
    }

    /** Simulates a process/power loss: unflushed writes disappear, synced data remains. */
    public void crash() { crash(null); }

    /**
     * Simulates a crash with a torn tail. When the unsynced writes were pure appends, a random
     * number of them (possibly zero) reached the platter before the crash and are kept; the
     * record being written at the crash is torn and dropped. Hard state is replaced atomically
     * (write-temp-rename), so it is always either the old or the new value, never torn.
     */
    public void crash(Random random) {
        List<LogEntry> survivors = new ArrayList<>(durableLog);
        if (random != null && !truncatedSinceFlush && workingLog.size() > durableLog.size()) {
            int unsynced = workingLog.size() - durableLog.size();
            int kept = random.nextInt(unsynced + 1);
            survivors.addAll(workingLog.subList(durableLog.size(), durableLog.size() + kept));
        }
        durableLog = survivors;
        pendingHardState = null;
        workingLog = new ArrayList<>(durableLog);
        dirty = false;
        truncatedSinceFlush = false;
    }

    @Override public long fsyncCount() { return fsyncCount; }
    @Override public long fsyncNanos() { return 0; }
    public String describe() { return "hard=" + durableHardState + ",log=" + durableLog.stream()
            .map(entry -> entry.index() + "/" + entry.term()).toList(); }
}
