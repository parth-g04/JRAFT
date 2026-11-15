package jraft.raftcore;

import java.util.List;

public sealed interface Message permits Message.PreVoteRequest, Message.PreVoteReply,
        Message.VoteRequest, Message.VoteReply, Message.AppendRequest, Message.AppendReply {
    long term();

    record PreVoteRequest(long term, int candidateId, long lastLogIndex, long lastLogTerm) implements Message { }
    record PreVoteReply(long term, long prospectiveTerm, boolean granted) implements Message { }
    record VoteRequest(long term, int candidateId, long lastLogIndex, long lastLogTerm) implements Message { }
    record VoteReply(long term, boolean granted) implements Message { }

    /**
     * AppendEntries / heartbeat. {@code readRound} is the leader's ReadIndex round at send time;
     * the follower echoes it so the leader knows which rounds a majority has confirmed.
     */
    record AppendRequest(long term, int leaderId, long previousIndex, long previousTerm,
                         List<LogEntry> entries, long leaderCommit, long readRound) implements Message {
        public AppendRequest { entries = List.copyOf(entries); }

        public AppendRequest(long term, int leaderId, long previousIndex, long previousTerm,
                             List<LogEntry> entries, long leaderCommit) {
            this(term, leaderId, previousIndex, previousTerm, entries, leaderCommit, 0);
        }
    }

    record AppendReply(long term, boolean success, long matchIndex,
                       long conflictIndex, long conflictTerm, long readRound) implements Message {
        public AppendReply(long term, boolean success, long matchIndex, long conflictIndex, long conflictTerm) {
            this(term, success, matchIndex, conflictIndex, conflictTerm, 0);
        }
    }
}
