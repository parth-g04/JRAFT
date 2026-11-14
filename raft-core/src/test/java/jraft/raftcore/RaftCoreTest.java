package jraft.raftcore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RaftCoreTest {
    @Test
    void persistsSelfVoteBeforeRequestingVotesAndBecomesLeaderWithMajority() {
        RaftCore node = new RaftCore(1, List.of(2, 3), 3, 1);
        node.tick();
        node.tick();
        List<Output> preVote = node.tick();

        assertThat(preVote).contains(new Output.Send(2,
                new Message.PreVoteRequest(1, 1, 0, 0)));
        List<Output> election = node.step(2, new Message.PreVoteReply(0, true));
        assertThat(election.getFirst()).isEqualTo(new Output.PersistHardState(1, 1, 0));
        assertThat(node.role()).isEqualTo(NodeRole.CANDIDATE);

        node.step(2, new Message.VoteReply(1, true));
        assertThat(node.role()).isEqualTo(NodeRole.LEADER);
        assertThat(node.currentTerm()).isEqualTo(1);
    }

    @Test
    void replicationCommitsOnlyAfterMajorityAndAppliesInOrder() {
        RaftCore node = electLeader();
        List<Output> proposed = node.propose(new byte[]{7});

        assertThat(node.commitIndex()).isZero();
        assertThat(proposed).anyMatch(Output.AppendToLog.class::isInstance);
        List<Output> ack = node.step(2, new Message.AppendReply(1, true, 1, 0, 0));

        assertThat(node.commitIndex()).isEqualTo(1);
        assertThat(node.lastApplied()).isEqualTo(1);
        assertThat(ack).anyMatch(Output.ApplyEntries.class::isInstance);
    }

    @Test
    void prevoteDoesNotIncreaseTermWhileRecentLeaderContactExists() {
        RaftCore node = new RaftCore(2, List.of(1, 3), 3, 1);
        node.step(1, new Message.AppendRequest(4, 1, 0, 0, List.of(), 0));

        List<Output> response = node.step(3, new Message.PreVoteRequest(5, 3, 0, 0));

        assertThat(response).contains(new Output.Send(3, new Message.PreVoteReply(4, false)));
        assertThat(node.currentTerm()).isEqualTo(4);
    }

    @Test
    void appendConflictReportsFirstIndexOfConflictingTerm() {
        RaftCore node = new RaftCore(2, List.of(1, 3), 3, 1);
        node.step(1, new Message.AppendRequest(1, 1, 0, 0,
                List.of(new LogEntry(1, 1, new byte[]{1}), new LogEntry(2, 1, new byte[]{2})), 0));

        List<Output> conflict = node.step(1, new Message.AppendRequest(2, 1, 0, 0,
                List.of(new LogEntry(1, 2, new byte[]{9})), 0));

        assertThat(conflict).anyMatch(output -> output instanceof Output.Send send
                && send.message() instanceof Message.AppendReply reply
                && !reply.success() && reply.conflictIndex() == 1 && reply.conflictTerm() == 1);
    }

    @Test
    void heartbeatAcknowledgesOnlyThePrefixItMatched() {
        RaftCore node = new RaftCore(2, List.of(1, 3), 3, 1);
        node.step(1, new Message.AppendRequest(1, 1, 0, 0,
                List.of(new LogEntry(1, 1, new byte[]{1}), new LogEntry(2, 1, new byte[]{2})), 0));

        List<Output> heartbeat = node.step(1,
                new Message.AppendRequest(1, 1, 0, 0, List.of(), 0));

        assertThat(heartbeat).anyMatch(output -> output instanceof Output.Send send
                && send.message() instanceof Message.AppendReply reply
                && reply.success() && reply.matchIndex() == 0);
    }

    @Test
    void previousTermEntryCommitsOnlyAfterCurrentTermEntryReachesMajority() {
        RaftCore node = new RaftCore(1, List.of(2, 3), 3, 1);
        node.step(2, new Message.AppendRequest(1, 2, 0, 0,
                List.of(new LogEntry(1, 1, new byte[]{1})), 0));
        node.tick();
        node.tick();
        node.tick();
        node.step(2, new Message.PreVoteReply(1, true));
        node.step(2, new Message.VoteReply(2, true));

        node.step(2, new Message.AppendReply(2, true, 1, 0, 0));
        assertThat(node.commitIndex()).isZero();

        node.propose(new byte[]{2});
        node.step(2, new Message.AppendReply(2, true, 2, 0, 0));
        assertThat(node.commitIndex()).isEqualTo(2);
        assertThat(node.lastApplied()).isEqualTo(2);
    }

    @Test
    void conflictHintMovesLeaderBackToTheReportedIndex() {
        RaftCore node = electLeader();
        node.propose(new byte[]{1});
        node.propose(new byte[]{2});

        List<Output> retry = node.step(2, new Message.AppendReply(1, false, 0, 1, -1));

        assertThat(retry).anyMatch(output -> output instanceof Output.Send send
                && send.message() instanceof Message.AppendRequest request
                && request.previousIndex() == 0 && request.entries().size() == 2);
    }

    private static RaftCore electLeader() {
        RaftCore node = new RaftCore(1, List.of(2, 3), 3, 1);
        node.tick();
        node.tick();
        node.tick();
        node.step(2, new Message.PreVoteReply(0, true));
        node.step(2, new Message.VoteReply(1, true));
        return node;
    }
}
