package jraft.raftcore;

/** Replicated state machine (ARCHITECTURE.md section 1). Must be deterministic. */
public interface StateMachine {
    void apply(LogEntry entry);
    /** Serializes the full state, including client sessions. Same state => same bytes. */
    byte[] snapshot();
    /** Replaces the full state with a snapshot produced by {@link #snapshot()}. */
    void restore(byte[] snapshot);
}
