package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jraft.sim.LinearizabilityChecker.Operation;
import org.junit.jupiter.api.Test;

/** Scripted, fault-free scenarios where the test injects exactly the faults it needs. */
class ClientScenarioTest {

    /** Collects replies and records completed operations into a history for the checker. */
    private static final class ScriptedClient implements SimCluster.Client {
        final long id;
        final SimCluster cluster;
        final List<ClientReply> replies = new ArrayList<>();
        long sequence;

        ScriptedClient(long id, SimCluster cluster) {
            this.id = id;
            this.cluster = cluster;
            cluster.register(id, this);
        }

        @Override public void onReply(ClientReply reply) { replies.add(reply); }

        ClientRequest request(KvCommand.Operation op, String key, String value) {
            return new ClientRequest(id, ++sequence, op, key, value);
        }

        /** Sends to {@code node} and runs until an OK/NO_KEY reply arrives, or {@code ticks} pass. */
        ClientReply call(int node, ClientRequest request, int ticks) {
            replies.clear();
            cluster.sendToServer(node, request);
            for (int t = 0; t < ticks; t++) {
                cluster.tick();
                for (ClientReply reply : replies) {
                    if (reply.sequence() == request.sequence() && reply.status() != ClientReply.Status.WRONG_LEADER) {
                        return reply;
                    }
                }
            }
            return null;
        }
    }

    private static SimCluster clusterWithLeader() {
        SimCluster cluster = SimCluster.quiet(1);
        for (int t = 0; t < 200 && cluster.leaderId() == null; t++) cluster.tick();
        cluster.runTicks(10); // let the leader's no-op commit
        assertThat(cluster.leaderId()).isNotNull();
        return cluster;
    }

    @Test
    void isolatedOldLeaderNeverServesAStaleRead() {
        StaleReadRun run = staleLeaderScenario(false);

        assertThat(run.staleReply).as("old leader must not answer while it cannot reach a majority").isNull();
        assertThat(run.retriedRead.value()).isEqualTo("new;");
        assertThat(LinearizabilityChecker.check(run.history).linearizable()).isTrue();
    }

    @Test
    void withoutReadIndexTheSameScenarioReturnsStaleDataAndTheCheckerCatchesIt() {
        // Negative control: proves the scenario and the checker can detect the bug ReadIndex prevents.
        StaleReadRun run = staleLeaderScenario(true);

        assertThat(run.staleReply).isNotNull();
        assertThat(run.staleReply.value()).isEqualTo("old;");
        LinearizabilityChecker.Result result = LinearizabilityChecker.check(run.history);
        assertThat(result.linearizable()).isFalse();
        assertThat(result.key()).isEqualTo("x");
    }

    private record StaleReadRun(ClientReply staleReply, ClientReply retriedRead, List<Operation> history) { }

    private static StaleReadRun staleLeaderScenario(boolean unsafeLocalReads) {
        SimCluster cluster = clusterWithLeader();
        cluster.setUnsafeLocalReads(unsafeLocalReads);
        ScriptedClient writer = new ScriptedClient(1, cluster);
        ScriptedClient reader = new ScriptedClient(2, cluster);
        List<Operation> history = new ArrayList<>();
        int oldLeader = cluster.leaderId();

        // 1. x = "old;" through the current leader.
        long call = cluster.eventTime();
        assertThat(writer.call(oldLeader, writer.request(KvCommand.Operation.PUT, "x", "old;"), 50)).isNotNull();
        history.add(new Operation(1, "put", "x", "old;", null, call, cluster.eventTime()));

        // 2. Isolate the old leader. It still believes it is leader; the majority elects a new one.
        List<Integer> majority = SimCluster.NODE_IDS.stream().filter(id -> id != oldLeader).toList();
        cluster.network().partition(List.of(Set.of(oldLeader), Set.copyOf(majority)));
        for (int t = 0; t < 200 && cluster.leaderAmong(majority) == null; t++) cluster.tick();
        int newLeader = cluster.leaderAmong(majority);

        // 3. x = "new;" through the new leader; it commits on the majority side.
        call = cluster.eventTime();
        assertThat(writer.call(newLeader, writer.request(KvCommand.Operation.PUT, "x", "new;"), 50)).isNotNull();
        history.add(new Operation(1, "put", "x", "new;", null, call, cluster.eventTime()));

        // 4. A client that still talks to the old leader reads x.
        ClientRequest read = reader.request(KvCommand.Operation.GET, "x", null);
        call = cluster.eventTime();
        ClientReply stale = reader.call(oldLeader, read, 60);
        if (stale != null) history.add(new Operation(2, "get", "x", null, stale.value(), call, cluster.eventTime()));

        // 5. Heal; the old leader learns the new term, fails the pending read, and the client retries.
        cluster.network().heal();
        cluster.runTicks(20);
        ClientRequest retry = reader.request(KvCommand.Operation.GET, "x", null);
        call = cluster.eventTime();
        ClientReply retried = reader.call(newLeader, retry, 50);
        assertThat(retried).isNotNull();
        history.add(new Operation(2, "get", "x", null, retried.value(), call, cluster.eventTime()));
        return new StaleReadRun(stale, retried, history);
    }

    @Test
    void pendingReadOnDeposedLeaderFailsWithWrongLeader() {
        SimCluster cluster = clusterWithLeader();
        ScriptedClient reader = new ScriptedClient(2, cluster);
        int oldLeader = cluster.leaderId();
        List<Integer> majority = SimCluster.NODE_IDS.stream().filter(id -> id != oldLeader).toList();
        cluster.network().partition(List.of(Set.of(oldLeader), Set.copyOf(majority)));
        for (int t = 0; t < 200 && cluster.leaderAmong(majority) == null; t++) cluster.tick();

        assertThat(reader.call(oldLeader, reader.request(KvCommand.Operation.GET, "x", null), 30)).isNull();
        cluster.network().heal();
        cluster.runTicks(20);
        assertThat(reader.replies).anyMatch(reply -> reply.status() == ClientReply.Status.WRONG_LEADER
                && reply.leaderHint() != null && reply.leaderHint() != oldLeader);
    }

    @Test
    void duplicatedAppendIsAppliedOnceEvenAcrossALeaderChange() {
        SimCluster cluster = clusterWithLeader();
        ScriptedClient client = new ScriptedClient(1, cluster);
        int leader = cluster.leaderId();

        // Same request delivered three times to the same leader before it commits.
        ClientRequest first = client.request(KvCommand.Operation.APPEND, "x", "a;");
        cluster.sendToServer(leader, first);
        cluster.sendToServer(leader, first);
        assertThat(client.call(leader, first, 50)).isNotNull();

        // Second append commits, then the leader crashes before the client sees the reply.
        ClientRequest second = client.request(KvCommand.Operation.APPEND, "x", "b;");
        cluster.sendToServer(leader, second);
        cluster.runTicks(10);
        cluster.crashNode(leader);
        List<Integer> survivors = SimCluster.NODE_IDS.stream().filter(id -> id != leader).toList();
        for (int t = 0; t < 200 && cluster.leaderAmong(survivors) == null; t++) cluster.tick();
        int newLeader = cluster.leaderAmong(survivors);

        // The client retries the same (clientId, sequence) at the new leader: cached reply, no re-apply.
        ClientReply retried = client.call(newLeader, second, 50);
        assertThat(retried).isNotNull();
        assertThat(retried.status()).isEqualTo(ClientReply.Status.OK);
        assertThat(cluster.node(newLeader).stateMachine().snapshotView()).containsEntry("x", "a;b;");

        cluster.restartNode(leader);
        cluster.runTicks(60);
        for (KvStateMachine machine : cluster.memberStateMachines()) {
            assertThat(machine.snapshotView()).containsEntry("x", "a;b;");
        }
    }

    @Test
    void followerRedirectsToTheLeader() {
        SimCluster cluster = clusterWithLeader();
        ScriptedClient client = new ScriptedClient(1, cluster);
        int leader = cluster.leaderId();
        int follower = SimCluster.NODE_IDS.stream().filter(id -> id != leader).findFirst().orElseThrow();

        cluster.sendToServer(follower, client.request(KvCommand.Operation.PUT, "x", "v"));
        cluster.runTicks(3);
        assertThat(client.replies).containsExactly(
                new ClientReply(1, 1, ClientReply.Status.WRONG_LEADER, null, leader));
    }
}
