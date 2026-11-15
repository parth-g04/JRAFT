package jraft.sim;

import jraft.kv.ShardKvStateMachine;
import jraft.shardctrler.ShardConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Seed-reproducible chaos for the sharded store (Phase 7): one controller group, three shard
 * groups, three clients, while the nemesis partitions, crashes, duplicates and reconfigures
 * (Join/Leave/Move). Use --seed N --seeds COUNT.
 */
public final class ShardedChaosTest {
    static final int CLIENTS = 3;
    static final int OPERATIONS_PER_CLIENT = 40;
    static final int SNAPSHOT_THRESHOLD = 16;
    static final int SNAPSHOT_CHUNK_BYTES = 256;
    private static final int FAULT_TICKS = 4_000;
    private static final int RECOVERY_TICKS = 4_000;

    /** {@code shardMoves}: shards that changed owner between consecutive configurations (excluding first assignment). */
    public record Outcome(ShardedWorld world, int reconfigurations, int finalConfigNum, int shardMoves) { }

    private ShardedChaosTest() { }

    public static void main(String[] args) throws Exception {
        long firstSeed = 1;
        int seeds = 1;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--seed")) firstSeed = Long.parseLong(args[++i]);
            else if (args[i].equals("--seeds")) seeds = Integer.parseInt(args[++i]);
        }
        long reconfigurations = 0;
        long configs = 0;
        long installs = 0;
        long moves = 0;
        for (int i = 0; i < seeds; i++) {
            Outcome outcome = runSeed(firstSeed + i);
            reconfigurations += outcome.reconfigurations();
            configs += outcome.finalConfigNum();
            installs += outcome.world().snapshotsInstalled();
            moves += outcome.shardMoves();
        }
        System.out.println("Sharded chaos passed for " + seeds + " seed(s); reconfiguration requests " + reconfigurations
                + ", configurations reached " + configs + ", shard migrations " + moves + ", snapshot installs " + installs);
    }

    public static Outcome runSeed(long seed) { return runSeed(seed, false); }

    /** @param unsafeShardReads test-only negative control (see ShardedWorld#setUnsafeShardReads). */
    public static Outcome runSeed(long seed, boolean unsafeShardReads) {
        ShardedWorld world = new ShardedWorld(seed, true, SNAPSHOT_THRESHOLD, SNAPSHOT_CHUNK_BYTES);
        world.setUnsafeShardReads(unsafeShardReads);
        world.setHistory(Path.of("build", "histories", "sharded-" + seed + ".jsonl"));
        ShardedAdmin admin = new ShardedAdmin(world);
        admin.join(ShardedWorld.GROUP_IDS.getFirst());
        List<ShardedClerk> clerks = new ArrayList<>();
        for (int c = 1; c <= CLIENTS; c++) {
            clerks.add(new ShardedClerk(1_000 + c, world, seed * 1_000 + c, OPERATIONS_PER_CLIENT));
        }
        ShardedNemesis nemesis = new ShardedNemesis(seed, world, admin);
        try {
            for (int t = 0; t < FAULT_TICKS && !allDone(clerks); t++) {
                world.tick();
                nemesis.onTick(world.clock().now());
            }
            nemesis.stop();
            for (int t = 0; t < RECOVERY_TICKS && !(allDone(clerks) && admin.idle()); t++) world.tick();
            if (!allDone(clerks) || !admin.idle()) {
                throw new IllegalStateException("clients or admin did not finish after healing (liveness) at tick "
                        + world.clock().now() + " nodes=" + world.describe());
            }
            ShardConfig latest = settle(world);
            checkInvariants(world, latest);
            LinearizabilityChecker.Result result = LinearizabilityChecker.check(world.history().operations());
            if (!result.linearizable()) {
                throw new IllegalStateException("history is NOT linearizable for key " + result.key()
                        + ": " + result.keyHistory() + "; history file " + world.history().path());
            }
            return new Outcome(world, nemesis.reconfigurations(), latest.num(), shardMoves(world, latest));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("sharded seed " + seed + " failed. Reproduce: make shard-chaos-one SEED="
                    + seed, failure);
        }
    }

    private static int shardMoves(ShardedWorld world, ShardConfig latest) {
        var controller = world.controllerService(world.leaderOf(ShardedWorld.CONTROLLERS)).machine();
        int moves = 0;
        for (int num = 1; num <= latest.num(); num++) {
            ShardConfig before = controller.query(num - 1);
            ShardConfig after = controller.query(num);
            for (int shard = 0; shard < ShardConfig.NSHARDS; shard++) {
                if (before.owner(shard) != 0 && before.owner(shard) != after.owner(shard)) moves++;
            }
        }
        return moves;
    }

    private static boolean allDone(List<ShardedClerk> clerks) { return clerks.stream().allMatch(ShardedClerk::done); }

    /**
     * Runs until every group's leader has applied the controller's latest configuration and no
     * migration is in progress, then lets followers catch up. Returns that configuration.
     */
    public static ShardConfig settle(ShardedWorld world) {
        ShardConfig latest = null;
        for (int t = 0; t < 4_000; t++) {
            Integer controllerLeader = world.leaderOf(ShardedWorld.CONTROLLERS);
            if (controllerLeader != null) {
                latest = world.controllerService(controllerLeader).machine().latest();
                if (allGroupsAt(world, latest)) break;
            }
            world.tick();
        }
        if (latest == null || !allGroupsAt(world, latest)) {
            throw new IllegalStateException("groups did not settle on the latest configuration " + latest
                    + " nodes=" + world.describe());
        }
        world.runTicks(60);
        return latest;
    }

    private static boolean allGroupsAt(ShardedWorld world, ShardConfig latest) {
        for (int gid : ShardedWorld.GROUP_IDS) {
            Integer leader = world.leaderOf(ShardedWorld.serversOf(gid));
            if (leader == null) return false;
            ShardKvStateMachine machine = world.shardService(leader).machine();
            if (machine.currentConfig().num() != latest.num() || !machine.stable()) return false;
        }
        return true;
    }

    /**
     * After settling: the replicas of each group are identical, and every shard is held (SERVING)
     * by exactly the group that owns it in the latest configuration, and by nobody else.
     */
    public static void checkInvariants(ShardedWorld world, ShardConfig latest) {
        for (int gid : ShardedWorld.GROUP_IDS) {
            byte[] first = null;
            for (int server : ShardedWorld.serversOf(gid)) {
                byte[] state = world.shardService(server).machine().snapshot();
                if (first == null) first = state;
                else if (!Arrays.equals(first, state)) {
                    throw new IllegalStateException("replicas of group " + gid + " diverged; nodes=" + world.describe());
                }
            }
        }
        for (int shard = 0; shard < ShardConfig.NSHARDS; shard++) {
            for (int gid : ShardedWorld.GROUP_IDS) {
                ShardKvStateMachine machine = world.shardService(ShardedWorld.serversOf(gid).getFirst()).machine();
                ShardKvStateMachine.ShardStatus status = machine.status(shard);
                boolean owner = latest.owner(shard) == gid;
                if (owner && status != ShardKvStateMachine.ShardStatus.SERVING) {
                    throw new IllegalStateException("shard " + shard + " should be SERVING at " + gid + " but is " + status);
                }
                if (!owner && status != null) {
                    throw new IllegalStateException("shard " + shard + " is still held by " + gid + " (" + status + ")");
                }
            }
        }
    }
}
