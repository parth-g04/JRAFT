package jraft.raftcore;

import java.util.Arrays;

public record LogEntry(long index, long term, byte[] command) {
    public LogEntry {
        if (index < 1 || term < 0) {
            throw new IllegalArgumentException("log index must be positive and term non-negative");
        }
        command = Arrays.copyOf(command, command.length);
    }

    @Override
    public byte[] command() {
        return Arrays.copyOf(command, command.length);
    }

    // A record's generated equals/hashCode compare arrays by reference, so two entries
    // with the same bytes would never be equal (e.g. an entry recovered from the WAL vs
    // the one that was written). Compare the command contents instead.
    @Override
    public boolean equals(Object other) {
        return other instanceof LogEntry that
                && index == that.index
                && term == that.term
                && Arrays.equals(command, that.command);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Long.hashCode(index) + Long.hashCode(term)) + Arrays.hashCode(command);
    }

    @Override
    public String toString() {
        return "LogEntry[index=" + index + ", term=" + term + ", command=" + Arrays.toString(command) + "]";
    }
}
