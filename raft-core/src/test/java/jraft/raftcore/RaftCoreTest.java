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
        List<Output> election = node.step(2, new Message.PreVoteReply(0, 1, true));
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
        // Index 1 is the leader's no-op; the proposed command is index 2.
        List<Output> ack = node.step(2, new Message.AppendReply(1, true, 2, 0, 0));

        assertThat(node.commitIndex()).isEqualTo(2);
        assertThat(node.lastApplied()).isEqualTo(2);
        assertThat(ack).anyMatch(Output.ApplyEntries.class::isInstance);
    }

    @Test
    void prevoteDoesNotIncreaseTermWhileRecentLeaderContactExists() {
        RaftCore node = new RaftCore(2, List.of(1, 3), 3, 1);
        node.step(1, new Message.AppendRequest(4, 1, 0, 0, List.of(), 0));

        List<Output> response = node.step(3, new Message.PreVoteRequest(5, 3, 0, 0));

        assertThat(response).contains(new Output.Send(3, new Message.PreVoteReply(4, 5, false)));
        assertThat(node.currentTerm()).isEqualTo(4);
    }

    @Test
    void appendConflictReportsFirstIndexOfConflictingTerm() {
        RaftCore node = new RaftCore(2, List.of(1, 3), 3, 1);
        node.step(1, new Message.AppendRequest(1, 1, 0, 0,
                List.of(new LogEntry(1, 1, new byte[]{1}), new LogEntry(2, 1, new byte[]{2})), 0));

        // Leader claims index 2 has term 2; follower holds term 1 there. The prefix
        // check must fail and hint the first index of the follower's conflicting term.
        List<Output> conflict = node.step(1, new Message.AppendRequest(2, 1, 2, 2,
                List.of(new LogEntry(3, 2, new byte[]{9})), 0));

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
        node.step(2, new Message.PreVoteReply(1, 2, true));
        node.step(2, new Message.VoteReply(2, true));

        node.step(2, new Message.AppendReply(2, true, 1, 0, 0));
        assertThat(node.commitIndex()).isZero();

        // Replicating the term-2 no-op (index 2) commits the term-1 entry indirectly.
        node.propose(new byte[]{2});
        node.step(2, new Message.AppendReply(2, true, 3, 0, 0));
        assertThat(node.commitIndex()).isEqualTo(3);
        assertThat(node.lastApplied()).isEqualTo(3);
    }

    @Test
    void conflictHintMovesLeaderBackToTheReportedIndex() {
        RaftCore node = electLeader();
        node.propose(new byte[]{1});
        node.propose(new byte[]{2});

        List<Output> retry = node.step(2, new Message.AppendReply(1, false, 0, 1, -1));

        assertThat(retry).anyMatch(output -> output instanceof Output.Send send
                && send.message() instanceof Message.AppendRequest request
                && request.previousIndex() == 0 && request.entries().size() == 3);
    }

    private static RaftCore electLeader() {
        RaftCore node = new RaftCore(1, List.of(2, 3), 3, 1);
        node.tick();
        node.tick();
        node.tick();
        node.step(2, new Message.PreVoteReply(0, 1, true));
        node.step(2, new Message.VoteReply(1, true));
        return node;
    }

    @Test
    void prevoteReplyFromPeerOnAnOlderTermCountsForMatchingRound() {
        RaftCore node = new RaftCore(1, List.of(2, 3), 3, 1, 2, null, 0, List.of());
        node.tick(); node.tick(); node.tick();

        List<Output> election = node.step(2, new Message.PreVoteReply(1, 3, true));

        assertThat(election.getFirst()).isEqualTo(new Output.PersistHardState(3, 1, 0));
        assertThat(node.currentTerm()).isEqualTo(3);
    }

    // ---------------- Phase 4: leader no-op and ReadIndex ----------------

    @Test
    void newLeaderAppendsANoOpFromItsOwnTerm() {
        RaftCore node = electLeader();
        assertThat(node.logEntries()).hasSize(1);
        assertThat(node.logEntries().getFirst().term()).isEqualTo(1);
        assertThat(node.logEntries().getFirst().command()).isEmpty();
        assertThat(node.leaderId()).isEqualTo(1);
    }

    @Test
    void readIsReadyOnlyAfterAMajorityAcksTheReadRound() {
        RaftCore node = electLeader();
        node.step(2, new Message.AppendReply(1, true, 1, 0, 0)); // commits the no-op

        List<Output> started = node.readIndex(7);
        assertThat(started).noneMatch(Output.ReadReady.class::isInstance);
        long round = started.stream().filter(Output.Send.class::isInstance).map(Output.Send.class::cast)
                .map(send -> ((Message.AppendRequest) send.message()).readRound()).findFirst().orElseThrow();

        // An ack for an older round does not prove leadership for this read.
        assertThat(node.step(2, new Message.AppendReply(1, true, 1, 0, 0, round - 1)))
                .noneMatch(Output.ReadReady.class::isInstance);
        // Even a log-mismatch reply in our term confirms the peer still follows us.
        assertThat(node.step(3, new Message.AppendReply(1, false, 0, 1, -1, round)))
                .contains(new Output.ReadReady(7, 1));
    }

    @Test
    void readWaitsForTheCurrentTermNoOpToCommit() {
        RaftCore node = electLeader();          // no-op at index 1 is not yet committed
        List<Output> started = node.readIndex(1);
        long round = ((Message.AppendRequest) started.stream().filter(Output.Send.class::isInstance)
                .map(Output.Send.class::cast).findFirst().orElseThrow().message()).readRound();

        // Quorum confirmed, but the read index (1) is not applied yet.
        assertThat(node.step(2, new Message.AppendReply(1, false, 0, 1, 0, round)))
                .noneMatch(Output.ReadReady.class::isInstance);
        List<Output> committed = node.step(2, new Message.AppendReply(1, true, 1, 0, 0, round));
        assertThat(committed).contains(new Output.ReadReady(1, 1));
        // ApplyEntries must come before ReadReady so the driver reads applied state.
        int apply = indexOf(committed, Output.ApplyEntries.class);
        int ready = indexOf(committed, Output.ReadReady.class);
        assertThat(apply).isLessThan(ready);
    }

    @Test
    void partitionedOldLeaderNeverServesARead() {
        RaftCore node = electLeader();
        node.step(2, new Message.AppendReply(1, true, 1, 0, 0));
        List<Output> outputs = new java.util.ArrayList<>(node.readIndex(3));
        // No peer answers (partitioned). Heartbeats keep going out, but no read becomes ready.
        for (int i = 0; i < 50; i++) outputs.addAll(node.tick());
        assertThat(outputs).noneMatch(Output.ReadReady.class::isInstance);
        // When it finally hears of a newer term, the pending read fails so the client retries.
        assertThat(node.step(2, new Message.AppendRequest(5, 2, 0, 0, List.of(), 0)))
                .contains(new Output.ReadFailed(3));
        assertThat(node.leaderId()).isEqualTo(2);
    }

    @Test
    void followerRejectsReadsAndKnowsTheLeader() {
        RaftCore node = new RaftCore(2, List.of(1, 3), 3, 1);
        node.step(1, new Message.AppendRequest(1, 1, 0, 0, List.of(), 0));
        assertThat(node.readIndex(9)).containsExactly(new Output.ReadFailed(9));
        assertThat(node.leaderId()).isEqualTo(1);
    }

    @Test
    void followerEchoesTheReadRound() {
        RaftCore node = new RaftCore(2, List.of(1, 3), 3, 1);
        List<Output> reply = node.step(1, new Message.AppendRequest(1, 1, 0, 0, List.of(), 0, 42));
        assertThat(reply).anyMatch(output -> output instanceof Output.Send send
                && send.message() instanceof Message.AppendReply append && append.readRound() == 42);
    }

    private static int indexOf(List<Output> outputs, Class<?> type) {
        for (int i = 0; i < outputs.size(); i++) if (type.isInstance(outputs.get(i))) return i;
        return -1;
    }
}
