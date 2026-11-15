package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jraft.kv.KvResult;
import jraft.kv.ShardKvCommand;
import jraft.kv.ShardKvMessage;
import jraft.shardctrler.ShardConfig;
import org.junit.jupiter.api.Test;

class ShardedScenarioTest {

    private static ShardedWorld quietWorld() {
        ShardedWorld world = new ShardedWorld(1, false, 32, 256);
        world.setHistory(Path.of("build", "histories", "sharded-scenario.jsonl"));
        return world;
    }

    private static void runUntil(ShardedWorld world, java.util.function.BooleanSupplier condition, int ticks) {
        for (int t = 0; t < ticks && !condition.getAsBoolean(); t++) world.tick();
        assertThat(condition.getAsBoolean()).as("condition; nodes " + world.describe()).isTrue();
    }

    @Test
    void joinsAndLeavesMigrateEveryShardWithoutLosingData() {
        ShardedWorld world = quietWorld();
        ShardedAdmin admin = new ShardedAdmin(world);
        admin.join(100);
        List<ShardedClerk> clerks = new ArrayList<>();
        for (int c = 1; c <= 3; c++) clerks.add(new ShardedClerk(1_000 + c, world, 70 + c, 60));

        world.runTicks(150);
        admin.join(101);
        world.runTicks(150);
        admin.join(102);
        world.runTicks(150);
        admin.leave(100);                      // every shard of group 100 must move
        runUntil(world, () -> clerks.stream().allMatch(ShardedClerk::done) && admin.idle(), 6_000);

        ShardConfig latest = ShardedChaosTest.settle(world);
        assertThat(latest.groups()).containsOnlyKeys(101, 102);
        ShardedChaosTest.checkInvariants(world, latest);
        assertThat(LinearizabilityChecker.check(world.history().operations()).linearizable()).isTrue();
        assertThat(world.history().operations()).hasSize(180);
    }

    @Test
    void wrongGroupIsReturnedForShardsTheGroupDoesNotServe() {
        ShardedWorld world = quietWorld();
        ShardedAdmin admin = new ShardedAdmin(world);
        admin.join(100);
        runUntil(world, () -> {
            Integer leader = world.leaderOf(ShardedWorld.serversOf(100));
            return leader != null && world.shardService(leader).machine().currentConfig().num() == 1;
        }, 2_000);

        List<ShardKvMessage.KvReply> replies = new ArrayList<>();
        world.register(1_500, new ShardedWorld.Client() {
            @Override public void onMessage(int from, Object message) {
                if (message instanceof ShardKvMessage.KvReply reply) replies.add(reply);
            }
        });
        // Group 101 has not joined: it owns no shard, so its leader must refuse the key.
        runUntil(world, () -> world.leaderOf(ShardedWorld.serversOf(101)) != null, 500);
        int other = world.leaderOf(ShardedWorld.serversOf(101));
        world.sendApp(1_500, other, new ShardKvMessage.KvRequest(1_500, 1, ShardKvCommand.Op.PUT, "k1", "v"));
        world.runTicks(20);
        assertThat(replies).extracting(r -> r.result().status()).contains(KvResult.Status.WRONG_GROUP);
    }
}
