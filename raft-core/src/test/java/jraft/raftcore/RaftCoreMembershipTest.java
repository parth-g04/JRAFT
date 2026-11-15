package jraft.raftcore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import jraft.raftcore.RaftCore.ConfigChangeStatus;
import org.junit.jupiter.api.Test;

class RaftCoreMembershipTest {
    private static final Configuration VOTERS_123 = Configuration.ofVoters(List.of(1, 2, 3));

    /** Leader 1 of {1,2,3} whose term-1 no-op (index 1) is committed. */
    private static RaftCore committedLeader() {
        RaftCore node = new RaftCore(1, List.of(2, 3), 3, 1);
        node.tick(); node.tick(); node.tick();
        node.step(2, new Message.PreVoteReply(0, 1, true));
        node.step(2, new Message.VoteReply(1, true));
        node.step(2, new Message.AppendReply(1, true, 1, 0, 0));
        assertThat(node.commitIndex()).isEqualTo(1);
        return node;
    }

    @Test
    void addServerAppendsALearnerThatTakesEffectImmediately() {
        RaftCore leader = committedLeader();
        RaftCore.ConfigChangeResult result = leader.addServer(4);

        assertThat(result.status()).isEqualTo(ConfigChangeStatus.ACCEPTED);
        assertThat(leader.configuration()).isEqualTo(new Configuration(List.of(1, 2, 3), List.of(4)));
        assertThat(leader.committedConfiguration()).isEqualTo(VOTERS_123);   // not committed yet
        assertThat(result.outputs()).anyMatch(o -> o instanceof Output.AppendToLog append
                && append.entries().getFirst().isConfig());
        assertThat(sentTo(result.outputs())).contains(2, 3, 4);              // learners get the log too
    }

    @Test
    void learnerAcksDoNotCountTowardsCommit() {
        RaftCore leader = committedLeader();
        leader.addServer(4);                                                  // index 2
        leader.propose(new byte[]{7});                                        // index 3

        leader.step(4, new Message.AppendReply(1, true, 3, 0, 0));
        assertThat(leader.commitIndex()).as("leader + learner is not a majority of {1,2,3}").isEqualTo(1);
        leader.step(2, new Message.AppendReply(1, true, 3, 0, 0));
        assertThat(leader.commitIndex()).isEqualTo(3);
    }

    @Test
    void learnerIsPromotedOnceItHasCaughtUp() {
        RaftCore leader = committedLeader();
        leader.addServer(4);
        leader.step(2, new Message.AppendReply(1, true, 2, 0, 0));          // learner entry committed
        assertThat(leader.configuration().isLearner(4)).isTrue();

        List<Output> outputs = leader.step(4, new Message.AppendReply(1, true, 2, 0, 0));

        assertThat(outputs).anyMatch(o -> o instanceof Output.AppendToLog append
                && append.entries().getFirst().isConfig()
                && append.entries().getFirst().configuration().isVoter(4));
        assertThat(leader.configuration()).isEqualTo(Configuration.ofVoters(List.of(1, 2, 3, 4)));
    }

    @Test
    void onlyOneChangeAtATime() {
        RaftCore leader = committedLeader();
        assertThat(leader.addServer(4).status()).isEqualTo(ConfigChangeStatus.ACCEPTED);
        assertThat(leader.addServer(5).status()).isEqualTo(ConfigChangeStatus.IN_PROGRESS);
        assertThat(leader.removeServer(3).status()).isEqualTo(ConfigChangeStatus.IN_PROGRESS);

        leader.step(2, new Message.AppendReply(1, true, 2, 0, 0));
        assertThat(leader.addServer(5).status()).isEqualTo(ConfigChangeStatus.ACCEPTED);
    }

    @Test
    void newLeaderMustCommitInItsOwnTermBeforeChangingMembership() {
        RaftCore node = new RaftCore(1, List.of(2, 3), 3, 1);
        node.tick(); node.tick(); node.tick();
        node.step(2, new Message.PreVoteReply(0, 1, true));
        node.step(2, new Message.VoteReply(1, true));                       // no-op not committed yet

        assertThat(node.addServer(4).status()).isEqualTo(ConfigChangeStatus.IN_PROGRESS);
        assertThat(new RaftCore(2, List.of(1, 3), 3, 1).addServer(4).status()).isEqualTo(ConfigChangeStatus.NOT_LEADER);
    }

    @Test
    void leaderThatRemovesItselfStepsDownOnlyAfterTheChangeCommits() {
        RaftCore leader = committedLeader();
        assertThat(leader.removeServer(1).status()).isEqualTo(ConfigChangeStatus.ACCEPTED); // index 2
        assertThat(leader.configuration()).isEqualTo(Configuration.ofVoters(List.of(2, 3)));

        // New configuration {2,3}: the leader no longer counts itself, so one ack is not enough.
        leader.step(2, new Message.AppendReply(1, true, 2, 0, 0));
        assertThat(leader.commitIndex()).isEqualTo(1);
        assertThat(leader.role()).isEqualTo(NodeRole.LEADER);

        leader.step(3, new Message.AppendReply(1, true, 2, 0, 0));
        assertThat(leader.commitIndex()).isEqualTo(2);
        assertThat(leader.role()).isEqualTo(NodeRole.FOLLOWER);

        // A removed server never campaigns.
        List<Output> outputs = new ArrayList<>();
        for (int i = 0; i < 50; i++) outputs.addAll(leader.tick());
        assertThat(outputs).isEmpty();
    }

    @Test
    void serverOutsideTheConfigurationNeverCampaignsButFollowsTheLeader() {
        RaftCore newcomer = new RaftCore(4, VOTERS_123, 3, 1);
        List<Output> outputs = new ArrayList<>();
        for (int i = 0; i < 50; i++) outputs.addAll(newcomer.tick());
        assertThat(outputs).isEmpty();

        List<Output> reply = newcomer.step(1, new Message.AppendRequest(1, 1, 0, 0, List.of(), 0));
        assertThat(reply).anyMatch(o -> o instanceof Output.Send send && send.message() instanceof Message.AppendReply r && r.success());
        assertThat(newcomer.leaderId()).isEqualTo(1);
    }

    @Test
    void truncatingAConfigurationEntryRevertsToThePreviousConfiguration() {
        RaftCore follower = new RaftCore(2, List.of(1, 3), 3, 1);
        Configuration withLearner = VOTERS_123.withLearner(4);
        follower.step(1, new Message.AppendRequest(1, 1, 0, 0,
                List.of(new LogEntry(1, 1, new byte[0]), LogEntry.config(2, 1, withLearner)), 1));
        assertThat(follower.configuration()).isEqualTo(withLearner);   // effective when appended

        // A new leader of term 2 overwrites the uncommitted entry at index 2.
        follower.step(3, new Message.AppendRequest(2, 3, 1, 1, List.of(new LogEntry(2, 2, new byte[0])), 1));
        assertThat(follower.configuration()).isEqualTo(VOTERS_123);
    }

    @Test
    void snapshotCarriesTheConfigurationToANewServer() {
        RaftCore leader = committedLeader();
        leader.addServer(4);
        leader.step(2, new Message.AppendReply(1, true, 2, 0, 0));
        List<Output> compacted = leader.compact(2, new byte[]{42});
        Snapshot snapshot = ((Output.SaveSnapshot) compacted.getFirst()).snapshot();
        assertThat(snapshot.configuration()).isEqualTo(VOTERS_123.withLearner(4));

        RaftCore newcomer = new RaftCore(4, VOTERS_123, 3, 1);
        newcomer.step(1, new Message.InstallSnapshotRequest(1, 1, 2, 1, 0, new byte[]{42}, true, 0,
                snapshot.configuration()));
        assertThat(newcomer.configuration()).isEqualTo(VOTERS_123.withLearner(4));
    }

    @Test
    void recoveryRebuildsTheConfigurationFromTheLog() {
        Configuration withLearner = VOTERS_123.withLearner(4);
        RaftCore recovered = new RaftCore(1, VOTERS_123, 3, 1, 1, 1, 1, Snapshot.EMPTY,
                List.of(new LogEntry(1, 1, new byte[0]), LogEntry.config(2, 1, withLearner)));
        assertThat(recovered.configuration()).isEqualTo(withLearner);
        assertThat(recovered.committedConfiguration()).isEqualTo(VOTERS_123);
    }

    @Test
    void cannotRemoveTheLastVoter() {
        RaftCore single = new RaftCore(1, List.of(), 3, 1);
        single.tick(); single.tick(); single.tick();                        // elects itself, commits no-op
        assertThat(single.role()).isEqualTo(NodeRole.LEADER);
        assertThat(single.removeServer(1).status()).isEqualTo(ConfigChangeStatus.INVALID);
        assertThat(single.removeServer(9).status()).isEqualTo(ConfigChangeStatus.NO_CHANGE);
    }

    @Test
    void configurationEncodingRoundTrips() {
        Configuration configuration = new Configuration(List.of(3, 1, 2), List.of(5));
        assertThat(Configuration.decode(configuration.encode())).isEqualTo(configuration);
        assertThat(configuration.voters()).containsExactly(1, 2, 3);
        assertThat(configuration.isQuorum(List.of(1, 5))).isFalse();
        assertThat(configuration.isQuorum(List.of(1, 3, 5))).isTrue();
    }

    private static List<Integer> sentTo(List<Output> outputs) {
        return outputs.stream().filter(Output.Send.class::isInstance).map(o -> ((Output.Send) o).peerId()).toList();
    }
}
