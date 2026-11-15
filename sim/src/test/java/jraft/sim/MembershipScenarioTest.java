package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import jraft.raftcore.NodeRole;
import jraft.raftcore.RaftCore.ConfigChangeStatus;
import org.junit.jupiter.api.Test;

/** Membership changes while clients keep writing and reading; histories must stay linearizable. */
class MembershipScenarioTest {

    private static SimCluster clusterWithLeader() {
        SimCluster cluster = SimCluster.quiet(3, 16, 64);
        for (int t = 0; t < 200 && cluster.leaderId() == null; t++) cluster.tick();
        cluster.runTicks(10);
        assertThat(cluster.leaderId()).isNotNull();
        return cluster;
    }

    /** Retries a change until the leader accepts it (it refuses while another change is pending). */
    private static void change(SimCluster cluster, boolean add, int server) {
        for (int attempt = 0; attempt < 300; attempt++) {
            ConfigChangeStatus status = add ? cluster.addServer(server) : cluster.removeServer(server);
            if (status == ConfigChangeStatus.ACCEPTED || status == ConfigChangeStatus.NO_CHANGE) return;
            cluster.tick();
        }
        throw new AssertionError("change was never accepted: " + (add ? "add " : "remove ") + server);
    }

    private static void runUntil(SimCluster cluster, java.util.function.BooleanSupplier condition) {
        for (int t = 0; t < 500 && !condition.getAsBoolean(); t++) cluster.tick();
        assertThat(condition.getAsBoolean()).as("condition reached; nodes " + cluster.describeNodes()).isTrue();
    }

    @Test
    void replaceTheOriginalServersUnderLoad() {
        SimCluster cluster = clusterWithLeader();
        cluster.setHistorySeed(-6);
        List<Clerk> clerks = new ArrayList<>();
        for (int c = 1; c <= 3; c++) clerks.add(new Clerk(c, cluster, 600 + c, 60));

        // Grow to five voters (each new server joins as a learner and is promoted once caught up).
        change(cluster, true, 4);
        runUntil(cluster, () -> cluster.configuration() != null && cluster.configuration().isVoter(4));
        change(cluster, true, 5);
        runUntil(cluster, () -> cluster.configuration() != null && cluster.configuration().isVoter(5));

        // Remove two of the original servers, the current leader first.
        List<Integer> removed = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            runUntil(cluster, () -> cluster.leaderId() != null);
            int leader = cluster.leaderId();
            int victim = round == 0 ? leader
                    : cluster.configuration().voters().stream().filter(id -> id <= 3).findFirst().orElseThrow();
            change(cluster, false, victim);
            runUntil(cluster, () -> cluster.configuration() != null && !cluster.configuration().isMember(victim));
            removed.add(victim);
        }
        runUntil(cluster, () -> clerks.stream().allMatch(Clerk::done));
        cluster.runTicks(40);

        assertThat(cluster.configuration().voters()).hasSize(3).contains(4, 5).doesNotContainAnyElementsOf(removed);
        assertThat(cluster.memberStateMachines().stream().map(KvStateMachine::snapshotView).distinct()).hasSize(1);
        assertThat(LinearizabilityChecker.check(cluster.history().operations()).linearizable()).isTrue();
        // A removed server neither leads nor campaigns.
        for (int id : removed) assertThat(cluster.node(id).core().role()).isNotEqualTo(NodeRole.LEADER);
    }

    @Test
    void removedLeaderHandsOverAndANewLeaderIsElectedAmongTheRemainingVoters() {
        SimCluster cluster = clusterWithLeader();
        change(cluster, true, 4);
        runUntil(cluster, () -> cluster.configuration() != null && cluster.configuration().isVoter(4));
        int oldLeader = cluster.leaderId();

        change(cluster, false, oldLeader);
        runUntil(cluster, () -> cluster.leaderId() != null && cluster.leaderId() != oldLeader);

        assertThat(cluster.configuration().isMember(oldLeader)).isFalse();
        assertThat(cluster.node(oldLeader).core().role()).isEqualTo(NodeRole.FOLLOWER);
        // The group still commits writes.
        ScriptedWriter writer = new ScriptedWriter(cluster);
        assertThat(writer.put("after", "removal")).isTrue();
    }

    @Test
    void learnerThatIsFarBehindCatchesUpBySnapshotBeforePromotion() {
        SimCluster cluster = clusterWithLeader();
        int leader = cluster.leaderId();
        for (int sequence = 1; sequence <= 200; sequence++) {
            cluster.node(leader).propose(KvCommandCodec.encode(
                    new KvCommand(9, sequence, KvCommand.Operation.APPEND, "x", ".")));
            cluster.tick();
        }
        cluster.runTicks(20);
        long installsBefore = cluster.snapshotsInstalled();

        change(cluster, true, 5);
        runUntil(cluster, () -> cluster.configuration() != null && cluster.configuration().isVoter(5));

        assertThat(cluster.snapshotsInstalled()).as("server 5 caught up from a snapshot").isGreaterThan(installsBefore);
        assertThat(cluster.node(5).stateMachine().snapshotView().get("x")).hasSize(200);
    }

    /** Minimal client for one write. */
    private static final class ScriptedWriter implements SimCluster.Client {
        private final SimCluster cluster;
        private ClientReply reply;

        ScriptedWriter(SimCluster cluster) {
            this.cluster = cluster;
            cluster.register(77, this);
        }

        @Override public void onReply(ClientReply reply) {
            if (reply.status() == ClientReply.Status.OK) this.reply = reply;
        }

        boolean put(String key, String value) {
            for (int attempt = 0; attempt < 20 && reply == null; attempt++) {
                Integer leader = cluster.leaderId();
                if (leader != null) cluster.sendToServer(leader, new ClientRequest(77, 1, KvCommand.Operation.PUT, key, value));
                cluster.runTicks(10);
            }
            return reply != null;
        }
    }
}
