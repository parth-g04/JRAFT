package jraft.sim;

import jraft.raftcore.LogEntry;
import jraft.raftcore.Snapshot;
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
 *
 * Snapshots are written with temp+fsync+rename, so saveSnapshot is durable and atomic at once.
 * Compaction (deleting old WAL segments) only becomes durable at the next flush, so a crash in
 * between leaves "new snapshot + old WAL"; recovery then finishes the compaction, like FileStorage.
 */
public final class SimDisk implements Storage {
    private HardState durableHardState = HardState.empty();
    private HardState pendingHardState;
    private Snapshot snapshot = Snapshot.EMPTY;
    /** Log entries after durableBase / workingBase. */
    private List<LogEntry> durableLog = new ArrayList<>();
    private List<LogEntry> workingLog = new ArrayList<>();
    private long durableBase;
    private long workingBase;
    private boolean dirty;
    /** True when a truncation or compaction happened since the last flush, so the unsynced tail is not a pure append. */
    private boolean rewrittenSinceFlush;
    private long fsyncCount;
    private long snapshotsSaved;

    @Override public HardState loadHardState() { return durableHardState; }
    @Override public Snapshot loadSnapshot() { return snapshot; }
    @Override public List<LogEntry> loadLog() { return List.copyOf(durableLog); }

    @Override
    public void persistHardState(HardState hardState) {
        pendingHardState = hardState;
        dirty = true;
    }

    @Override
    public void append(List<LogEntry> entries) throws IOException {
        for (LogEntry entry : entries) {
            long expected = workingBase + workingLog.size() + 1L;
            if (entry.index() != expected) throw new IOException("expected simulated WAL index " + expected + " but got " + entry.index());
            workingLog.add(entry);
        }
        if (!entries.isEmpty()) dirty = true;
    }

    @Override
    public void truncateFrom(long index) {
        if (index <= workingBase) throw new IllegalArgumentException("cannot truncate compacted entries at " + index);
        if (index > workingBase + workingLog.size()) return;
        while (workingBase + workingLog.size() >= index) workingLog.remove(workingLog.size() - 1);
        dirty = true;
        rewrittenSinceFlush = true;
    }

    @Override
    public void saveSnapshot(Snapshot newSnapshot) {
        snapshot = newSnapshot;
        fsyncCount++;
        snapshotsSaved++;
    }

    @Override
    public void compactThrough(long index) {
        if (index <= workingBase) return;
        int drop = (int) Math.min(workingLog.size(), index - workingBase);
        workingLog.subList(0, drop).clear();
        workingBase = index;
        dirty = true;
        rewrittenSinceFlush = true;
    }

    @Override
    public void flush() {
        if (!dirty) return;
        if (pendingHardState != null) {
            durableHardState = pendingHardState;
            pendingHardState = null;
        }
        if (!rewrittenSinceFlush && workingBase == durableBase) {
            // Pure appends: copy only the new tail (copying the whole log each flush is O(n^2)).
            durableLog.addAll(workingLog.subList(durableLog.size(), workingLog.size()));
        } else {
            durableLog = new ArrayList<>(workingLog);
        }
        durableBase = workingBase;
        fsyncCount++;
        dirty = false;
        rewrittenSinceFlush = false;
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
        if (random != null && !rewrittenSinceFlush && workingBase == durableBase && workingLog.size() > durableLog.size()) {
            int unsynced = workingLog.size() - durableLog.size();
            int kept = random.nextInt(unsynced + 1);
            survivors.addAll(workingLog.subList(durableLog.size(), durableLog.size() + kept));
        }
        durableLog = survivors;
        pendingHardState = null;
        // Recovery: finish a compaction interrupted by the crash (snapshot newer than the WAL base).
        if (snapshot.index() > durableBase) {
            int drop = (int) Math.min(durableLog.size(), snapshot.index() - durableBase);
            durableLog = new ArrayList<>(durableLog.subList(drop, durableLog.size()));
            durableBase = snapshot.index();
        }
        workingLog = new ArrayList<>(durableLog);
        workingBase = durableBase;
        dirty = false;
        rewrittenSinceFlush = false;
    }

    @Override public long fsyncCount() { return fsyncCount; }
    @Override public long fsyncNanos() { return 0; }
    public long snapshotsSaved() { return snapshotsSaved; }
    public String describe() { return "hard=" + durableHardState + ",snap=" + snapshot.index() + "/" + snapshot.term()
            + ",log=" + (durableLog.isEmpty() ? "[]" : durableLog.getFirst().index() + ".." + durableLog.getLast().index()); }
}
