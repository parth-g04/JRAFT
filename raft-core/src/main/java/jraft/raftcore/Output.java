package jraft.raftcore;

import java.util.List;

public sealed interface Output permits Output.Send, Output.PersistHardState,
        Output.AppendToLog, Output.TruncateLog, Output.ApplyEntries {
    record Send(int peerId, Message message) implements Output { }
    record PersistHardState(long term, Integer votedFor, long commitIndex) implements Output { }
    record AppendToLog(List<LogEntry> entries) implements Output {
        public AppendToLog { entries = List.copyOf(entries); }
    }
    record TruncateLog(long fromIndex) implements Output { }
    record ApplyEntries(List<LogEntry> entries) implements Output {
        public ApplyEntries { entries = List.copyOf(entries); }
    }
}
