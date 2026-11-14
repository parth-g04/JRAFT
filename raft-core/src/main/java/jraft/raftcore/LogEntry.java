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
}
