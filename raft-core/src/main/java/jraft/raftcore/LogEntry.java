package jraft.raftcore;

import java.util.Arrays;

/**
 * One Raft log entry. {@link Type#NORMAL} entries carry a state-machine command (an empty command
 * is the leader's no-op); {@link Type#CONFIG} entries carry an encoded {@link Configuration}.
 */
public record LogEntry(long index, long term, byte[] command, Type type) {
    public enum Type { NORMAL, CONFIG }

    public LogEntry {
        if (index < 1 || term < 0) {
            throw new IllegalArgumentException("log index must be positive and term non-negative");
        }
        if (type == null) throw new IllegalArgumentException("entry type is required");
        command = Arrays.copyOf(command, command.length);
    }

    public LogEntry(long index, long term, byte[] command) { this(index, term, command, Type.NORMAL); }

    public static LogEntry config(long index, long term, Configuration configuration) {
        return new LogEntry(index, term, configuration.encode(), Type.CONFIG);
    }

    @Override
    public byte[] command() {
        return Arrays.copyOf(command, command.length);
    }

    public boolean isConfig() { return type == Type.CONFIG; }

    /** The configuration carried by a CONFIG entry. */
    public Configuration configuration() {
        if (type != Type.CONFIG) throw new IllegalStateException("not a configuration entry: " + this);
        return Configuration.decode(command);
    }

    // A record's generated equals/hashCode compare arrays by reference, so two entries
    // with the same bytes would never be equal (e.g. an entry recovered from the WAL vs
    // the one that was written). Compare the command contents instead.
    @Override
    public boolean equals(Object other) {
        return other instanceof LogEntry that
                && index == that.index
                && term == that.term
                && type == that.type
                && Arrays.equals(command, that.command);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * Long.hashCode(index) + Long.hashCode(term)) + type.ordinal()) + Arrays.hashCode(command);
    }

    @Override
    public String toString() {
        return "LogEntry[index=" + index + ", term=" + term
                + (type == Type.CONFIG ? ", config=" + configuration() : ", command=" + Arrays.toString(command)) + "]";
    }
}
