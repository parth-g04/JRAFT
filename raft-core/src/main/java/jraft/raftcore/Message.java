package jraft.raftcore;

import java.util.List;

public sealed interface Message permits Message.PreVoteRequest, Message.PreVoteReply,
        Message.VoteRequest, Message.VoteReply, Message.AppendRequest, Message.AppendReply {
    long term();

    record PreVoteRequest(long term, int candidateId, long lastLogIndex, long lastLogTerm) implements Message { }
    record PreVoteReply(long term, boolean granted) implements Message { }
    record VoteRequest(long term, int candidateId, long lastLogIndex, long lastLogTerm) implements Message { }
    record VoteReply(long term, boolean granted) implements Message { }
    record AppendRequest(long term, int leaderId, long previousIndex, long previousTerm,
                         List<LogEntry> entries, long leaderCommit) implements Message {
        public AppendRequest { entries = List.copyOf(entries); }
    }
    record AppendReply(long term, boolean success, long matchIndex,
                       long conflictIndex, long conflictTerm) implements Message { }
}
