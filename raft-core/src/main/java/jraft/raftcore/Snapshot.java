package jraft.raftcore;

import java.util.Arrays;

/**
 * State machine image covering every entry up to and including {@code index} (Raft's
 * lastIncludedIndex), whose entry had {@code term} (lastIncludedTerm).
 */
public record Snapshot(long index, long term, byte[] data) {
    public static final Snapshot EMPTY = new Snapshot(0, 0, new byte[0]);

    public Snapshot {
        if (index < 0 || term < 0) throw new IllegalArgumentException("snapshot index and term cannot be negative");
        data = Arrays.copyOf(data, data.length);
    }

    @Override public byte[] data() { return Arrays.copyOf(data, data.length); }
    public int size() { return data.length; }

    @Override
    public boolean equals(Object other) {
        return other instanceof Snapshot that && index == that.index && term == that.term
                && Arrays.equals(data, that.data);
    }

    @Override public int hashCode() { return 31 * Long.hashCode(index) + Arrays.hashCode(data); }
    @Override public String toString() { return "Snapshot[index=" + index + ", term=" + term + ", bytes=" + data.length + "]"; }
}
