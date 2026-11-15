package jraft.raftcore;

import java.util.Arrays;
import java.util.List;

public sealed interface Message permits Message.PreVoteRequest, Message.PreVoteReply,
        Message.VoteRequest, Message.VoteReply, Message.AppendRequest, Message.AppendReply,
        Message.InstallSnapshotRequest, Message.InstallSnapshotReply {
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

    /**
     * One chunk of the leader's snapshot (ARCHITECTURE.md section 3). Chunks are sent in order;
     * {@code done} marks the last one.
     */
    record InstallSnapshotRequest(long term, int leaderId, long lastIncludedIndex, long lastIncludedTerm,
                                  long offset, byte[] data, boolean done, long readRound) implements Message {
        public InstallSnapshotRequest { data = Arrays.copyOf(data, data.length); }
        @Override public byte[] data() { return Arrays.copyOf(data, data.length); }

        @Override
        public boolean equals(Object other) {
            return other instanceof InstallSnapshotRequest that && term == that.term && leaderId == that.leaderId
                    && lastIncludedIndex == that.lastIncludedIndex && lastIncludedTerm == that.lastIncludedTerm
                    && offset == that.offset && done == that.done && readRound == that.readRound
                    && Arrays.equals(data, that.data);
        }

        @Override public int hashCode() { return Long.hashCode(lastIncludedIndex * 31 + offset) + Arrays.hashCode(data); }

        @Override
        public String toString() {
            return "InstallSnapshotRequest[term=" + term + ", leaderId=" + leaderId + ", lastIncludedIndex="
                    + lastIncludedIndex + ", lastIncludedTerm=" + lastIncludedTerm + ", offset=" + offset
                    + ", bytes=" + data.length + ", done=" + done + ", readRound=" + readRound + "]";
        }
    }

    /**
     * {@code nextOffset}: the byte offset the follower expects next (it asks for a resend from there).
     * {@code installed}: the whole snapshot was received and the follower's state now covers
     * {@code lastIncludedIndex}.
     */
    record InstallSnapshotReply(long term, long lastIncludedIndex, long nextOffset, boolean installed,
                                long readRound) implements Message { }
}
