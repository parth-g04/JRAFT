package jraft.storage;

public record HardState(long currentTerm, Integer votedFor, long commitIndex) {
    public HardState {
        if (currentTerm < 0 || commitIndex < 0) throw new IllegalArgumentException("hard state cannot be negative");
    }

    public static HardState empty() { return new HardState(0, null, 0); }
}
