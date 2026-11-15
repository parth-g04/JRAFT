package jraft.kv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import jraft.kv.ShardKvStateMachine.ShardStatus;
import jraft.raftcore.LogEntry;
import jraft.shardctrler.Rebalancer;
import jraft.shardctrler.ShardConfig;
import org.junit.jupiter.api.Test;

class ShardKvStateMachineTest {
    private static final int A = 100;
    private static final int B = 101;
    private long index;

    private final ShardConfig config1 = config(1, Rebalancer.rebalance(Collections.nCopies(16, 0), List.of(A)), A);
    private final ShardConfig config2 = config(2, Rebalancer.rebalance(config1.shards(), List.of(A, B)), A, B);

    private static ShardConfig config(int num, List<Integer> shards, Integer... gids) {
        TreeMap<Integer, List<Integer>> groups = new TreeMap<>();
        for (int gid : gids) groups.put(gid, List.of(gid + 1, gid + 2, gid + 3));
        return new ShardConfig(num, shards, groups);
    }

    private KvResult apply(ShardKvStateMachine machine, ShardKvCommand command) {
        return machine.applyEntry(new LogEntry(++index, 1, ShardKvCommand.encode(command)));
    }

    private static ShardKvCommand.ClientOp append(long client, long sequence, String key, String value) {
        return new ShardKvCommand.ClientOp(client, sequence, ShardKvCommand.Op.APPEND, key, value);
    }

    /** A key that lives in a shard moving from A to B in config2. */
    private String movingKey() {
        for (int i = 0; ; i++) {
            String key = "key" + i;
            if (config2.owner(ShardConfig.shardOf(key)) == B) return key;
        }
    }

    private String stayingKey() {
        for (int i = 0; ; i++) {
            String key = "key" + i;
            if (config2.owner(ShardConfig.shardOf(key)) == A) return key;
        }
    }

    @Test
    void fullMigrationLifecycleKeepsDataSessionsAndUnaffectedShards() {
        ShardKvStateMachine groupA = new ShardKvStateMachine(A);
        ShardKvStateMachine groupB = new ShardKvStateMachine(B);
        String moving = movingKey();
        String staying = stayingKey();
        int movingShard = ShardConfig.shardOf(moving);

        apply(groupA, new ShardKvCommand.Reconfigure(config1));
        apply(groupB, new ShardKvCommand.Reconfigure(config1));
        assertThat(groupA.status(movingShard)).isEqualTo(ShardStatus.SERVING); // first owner starts empty
        assertThat(apply(groupA, append(7, 1, moving, "a;")).status()).isEqualTo(KvResult.Status.OK);
        assertThat(apply(groupB, append(7, 1, moving, "a;"))).isEqualTo(KvResult.WRONG_GROUP);

        // Configuration 2 moves half of the shards to B.
        apply(groupA, new ShardKvCommand.Reconfigure(config2));
        apply(groupB, new ShardKvCommand.Reconfigure(config2));
        assertThat(groupA.status(movingShard)).isEqualTo(ShardStatus.BEPULLING);
        assertThat(groupB.status(movingShard)).isEqualTo(ShardStatus.PULLING);
        assertThat(apply(groupA, append(8, 1, moving, "x;"))).isEqualTo(KvResult.WRONG_GROUP);  // frozen
        assertThat(groupB.read(moving)).isEqualTo(KvResult.WRONG_GROUP);                         // not there yet
        assertThat(apply(groupA, append(8, 1, staying, "s;")).status()).isEqualTo(KvResult.Status.OK); // unaffected
        assertThat(groupA.stable()).isFalse();
        assertThat(groupB.shardsByPreviousOwner(ShardStatus.PULLING)).containsOnlyKeys(A);

        // B pulls the frozen data (and sessions) from A.
        List<Integer> moved = config2.shardsOf(B);
        SortedMap<Integer, ShardData> pulled = groupA.pull(2, moved);
        assertThat(pulled).containsOnlyKeys(moved);
        assertThat(groupA.pull(3, moved)).isNull();                                               // wrong config
        apply(groupB, new ShardKvCommand.InsertShards(2, pulled));
        assertThat(groupB.status(movingShard)).isEqualTo(ShardStatus.GCING);
        assertThat(groupB.read(moving).value()).isEqualTo("a;");
        // The client retries its first APPEND at the new owner: recognized as a duplicate.
        assertThat(apply(groupB, append(7, 1, moving, "a;")).status()).isEqualTo(KvResult.Status.OK);
        assertThat(groupB.read(moving).value()).isEqualTo("a;");
        apply(groupB, new ShardKvCommand.InsertShards(2, pulled));                               // duplicate insert
        assertThat(groupB.read(moving).value()).isEqualTo("a;");

        // Garbage collection: A deletes its copy, then B marks the shards SERVING.
        assertThat(groupA.deleted(2, moved)).isFalse();
        apply(groupA, new ShardKvCommand.DeleteShards(2, moved));
        assertThat(groupA.deleted(2, moved)).isTrue();
        assertThat(groupA.status(movingShard)).isNull();
        apply(groupB, new ShardKvCommand.GcDone(2, moved));
        assertThat(groupB.status(movingShard)).isEqualTo(ShardStatus.SERVING);
        assertThat(groupA.stable()).isTrue();
        assertThat(groupB.stable()).isTrue();
        assertThat(groupA.read(staying).value()).isEqualTo("s;");
    }

    @Test
    void onlyTheNextConfigurationIsAppliedAndOnlyWhenStable() {
        ShardKvStateMachine groupA = new ShardKvStateMachine(A);
        apply(groupA, new ShardKvCommand.Reconfigure(config2));                // skips 1: ignored
        assertThat(groupA.currentConfig().num()).isZero();
        apply(groupA, new ShardKvCommand.Reconfigure(config1));
        apply(groupA, new ShardKvCommand.Reconfigure(config2));
        ShardConfig config3 = config(3, Rebalancer.rebalance(config2.shards(), List.of(A)), A);
        apply(groupA, new ShardKvCommand.Reconfigure(config3));                // migration in progress
        assertThat(groupA.currentConfig().num()).isEqualTo(2);
    }

    @Test
    void snapshotMidMigrationRoundTrips() {
        ShardKvStateMachine groupA = new ShardKvStateMachine(A);
        apply(groupA, new ShardKvCommand.Reconfigure(config1));
        apply(groupA, append(1, 1, movingKey(), "v;"));
        apply(groupA, new ShardKvCommand.Reconfigure(config2));
        ShardKvStateMachine restored = new ShardKvStateMachine(A);
        restored.restore(groupA.snapshot());

        assertThat(restored.snapshot()).isEqualTo(groupA.snapshot());
        assertThat(restored.currentConfig()).isEqualTo(config2);
        assertThat(restored.previousConfig()).isEqualTo(config1);
        assertThat(restored.status(ShardConfig.shardOf(movingKey()))).isEqualTo(ShardStatus.BEPULLING);
        assertThat(restored.pull(2, config2.shardsOf(B))).isEqualTo(groupA.pull(2, config2.shardsOf(B)));
    }

    @Test
    void serverPollsOnlyWhenStableAndProposesTheNextConfiguration() {
        ShardKvStateMachine groupB = new ShardKvStateMachine(B);
        ShardKvServer server = new ShardKvServer(B, 10, 10);
        assertThat(server.tick(0, groupB)).containsExactly(new ShardKvServer.Action.QueryController(1));
        assertThat(server.tick(5, groupB)).isEmpty();                               // poll interval
        assertThat(server.onConfig(config2, groupB)).isEmpty();                     // not the next one
        assertThat(server.onConfig(config1, groupB))
                .containsExactly(new ShardKvServer.Action.Propose(new ShardKvCommand.Reconfigure(config1)));

        apply(groupB, new ShardKvCommand.Reconfigure(config1));
        apply(groupB, new ShardKvCommand.Reconfigure(config2));
        List<ShardKvServer.Action> actions = server.tick(20, groupB);
        assertThat(actions).noneMatch(ShardKvServer.Action.QueryController.class::isInstance); // migrating
        assertThat(actions).containsExactly(new ShardKvServer.Action.SendToGroup(A, config1.serversOf(A),
                new ShardKvMessage.PullRequest(B, 2, config2.shardsOf(B))));
    }

    @Test
    void commandsRoundTripThroughTheirEncoding() {
        ShardKvCommand insert = new ShardKvCommand.InsertShards(3, new TreeMap<>(java.util.Map.of(4,
                new ShardData(new TreeMap<>(java.util.Map.of("k", "v")),
                        new TreeMap<>(java.util.Map.of(9L, new ShardData.Session(2, new KvResult(KvResult.Status.OK, null))))))));
        assertThat(ShardKvCommand.decode(ShardKvCommand.encode(insert))).isEqualTo(insert);
        ShardKvCommand op = new ShardKvCommand.ClientOp(1, 2, ShardKvCommand.Op.PUT, "k", "v");
        assertThat(ShardKvCommand.decode(ShardKvCommand.encode(op))).isEqualTo(op);
        ShardKvCommand reconfigure = new ShardKvCommand.Reconfigure(config2);
        assertThat(ShardKvCommand.decode(ShardKvCommand.encode(reconfigure))).isEqualTo(reconfigure);
    }
}
