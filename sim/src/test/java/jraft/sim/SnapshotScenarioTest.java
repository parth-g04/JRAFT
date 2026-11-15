package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import jraft.raftcore.LogEntry;
import jraft.raftcore.Snapshot;
import org.junit.jupiter.api.Test;

class SnapshotScenarioTest {
    private static final int THRESHOLD = 1_000;

    private static SimCluster clusterWithLeader(int threshold, int chunkBytes) {
        SimCluster cluster = SimCluster.quiet(5, threshold, chunkBytes);
        for (int t = 0; t < 200 && cluster.leaderId() == null; t++) cluster.tick();
        cluster.runTicks(10);
        assertThat(cluster.leaderId()).isNotNull();
        return cluster;
    }

    private static void runUntilCommitted(SimCluster cluster, int leader) {
        var core = cluster.node(leader).core();
        for (int t = 0; t < 2_000 && core.commitIndex() < core.lastIndex(); t++) cluster.tick();
    }

    private static byte[] put(long sequence, String key, String value) {
        return KvCommandCodec.encode(new KvCommand(1, sequence, KvCommand.Operation.PUT, key, value));
    }

    @Test
    void followerDownFor10kEntriesCatchesUpViaSnapshot() {
        SimCluster cluster = clusterWithLeader(THRESHOLD, 1_024);
        int leader = cluster.leaderId();
        int lagging = SimCluster.NODE_IDS.stream().filter(id -> id != leader).findFirst().orElseThrow();
        cluster.crashNode(lagging);

        // 10,000 writes while the follower is down (the remaining two nodes are a majority).
        for (int sequence = 1; sequence <= 10_000; sequence++) {
            cluster.node(leader).propose(put(sequence, "k" + (sequence % 50), "v" + sequence));
            if (sequence % 100 == 0) runUntilCommitted(cluster, leader);
        }
        cluster.runTicks(10);
        assertThat(cluster.node(leader).core().commitIndex()).isGreaterThan(10_000);
        Snapshot leaderSnapshot = cluster.node(leader).core().snapshot();
        assertThat(leaderSnapshot.index()).as("leader compacted its log").isGreaterThan(9_000);

        long installsBefore = cluster.snapshotsInstalled();
        cluster.restartNode(lagging);
        cluster.runTicks(200);

        SimNode follower = cluster.node(lagging);
        assertThat(cluster.snapshotsInstalled()).as("caught up by InstallSnapshot").isGreaterThan(installsBefore);
        assertThat(follower.core().snapshot().index()).isGreaterThanOrEqualTo(leaderSnapshot.index());
        assertThat(follower.core().commitIndex()).isEqualTo(cluster.node(leader).core().commitIndex());
        assertThat(follower.core().logSize()).as("never replayed 10k entries").isLessThan(THRESHOLD);
        assertThat(follower.stateMachine().snapshotView()).isEqualTo(cluster.node(leader).stateMachine().snapshotView());
        assertThat(follower.stateMachine().snapshotView()).containsEntry("k0", "v10000");
        // The installed snapshot is durable: a restart recovers the same state without the leader.
        cluster.crashNode(lagging);
        cluster.network().partition(List.of(java.util.Set.of(lagging)));
        cluster.restartNode(lagging);
        assertThat(cluster.node(lagging).stateMachine().snapshotView()).containsEntry("k0", "v10000");
    }

    @Test
    void crashBetweenSnapshotAndCompactionRecoversTheSameState() {
        SimCluster cluster = clusterWithLeader(20, 64);
        int leader = cluster.leaderId();
        for (int sequence = 1; sequence <= 60; sequence++) {
            cluster.node(leader).propose(put(sequence, "k" + (sequence % 5), "v" + sequence));
            cluster.tick();
        }
        cluster.runTicks(20);
        var before = cluster.node(leader).stateMachine().snapshotView();
        SimDisk disk = cluster.disk(leader);
        assertThat(disk.snapshotsSaved()).isPositive();

        // Snapshots are durable at once but compaction waits for the next flush; crash without one.
        cluster.crashNode(leader);
        cluster.restartNode(leader);
        assertThat(cluster.node(leader).stateMachine().snapshotView()).isEqualTo(before);
    }

    @Test
    void sessionsSurviveSnapshotInstallSoRetriesStayExactlyOnce() {
        KvStateMachine source = new KvStateMachine();
        byte[] append = KvCommandCodec.encode(new KvCommand(7, 3, KvCommand.Operation.APPEND, "x", "a;"));
        source.apply(new LogEntry(1, 1, append));

        KvStateMachine restored = new KvStateMachine();
        restored.restore(source.snapshot());
        restored.apply(new LogEntry(2, 1, append));       // the client's retry arrives after the install

        assertThat(restored.snapshotView()).containsEntry("x", "a;");
        assertThat(restored.cachedResult(7, 3)).isNotNull();
        assertThat(restored.snapshot()).isEqualTo(source.snapshot()); // deterministic bytes
    }

    @Test
    void kvSnapshotRoundTripsLargeValuesAndAbsentKeys() {
        KvStateMachine machine = new KvStateMachine();
        String big = "x".repeat(100_000);                 // larger than DataOutput.writeUTF allows
        machine.apply(new LogEntry(1, 1, KvCommandCodec.encode(new KvCommand(1, 1, KvCommand.Operation.PUT, "big", big))));
        machine.apply(new LogEntry(2, 1, KvCommandCodec.encode(new KvCommand(2, 1, KvCommand.Operation.DELETE, "missing", ""))));
        KvStateMachine copy = new KvStateMachine();
        copy.restore(machine.snapshot());

        assertThat(copy.snapshotView()).isEqualTo(machine.snapshotView());
        assertThat(copy.sessionsView()).isEqualTo(machine.sessionsView());
    }
}
