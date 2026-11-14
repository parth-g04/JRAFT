package jraft.sim;

/** Monotonic logical clock measured in scheduler ticks. */
public final class SimClock {
    private long ticks;

    public long now() { return ticks; }
    public long advance() { return ++ticks; }
}
