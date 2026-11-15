package jraft.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;

/**
 * Faults for the sharded world: partitions (often isolating a group leader), crash/restart,
 * duplication storms, and configuration changes (Join/Leave/Move) while clients run.
 */
public final class ShardedNemesis {
    private final Random random;
    private final ShardedWorld world;
    private final ShardedAdmin admin;
    private long nextChange;
    private boolean partitioned;
    private boolean storm;
    private int crashed;
    private long restartAt = Long.MAX_VALUE;
    private boolean stopped;
    private int reconfigurations;

    public ShardedNemesis(long seed, ShardedWorld world, ShardedAdmin admin) {
        this.random = new Random(seed ^ 0x5DEECE66DL);
        this.world = world;
        this.admin = admin;
        this.nextChange = 30 + random.nextInt(20);
    }

    public void onTick(long tick) {
        if (stopped) return;
        if (crashed != 0 && tick >= restartAt) {
            world.restartNode(crashed);
            crashed = 0;
            restartAt = Long.MAX_VALUE;
        }
        if (tick < nextChange) return;
        int action = random.nextInt(5);
        if (action == 0 && !partitioned) {
            int isolated = pickNode();
            List<Integer> others = new ArrayList<>(ShardedWorld.allNodeIds());
            others.remove(Integer.valueOf(isolated));
            world.network().partition(List.of(Set.of(isolated), Set.copyOf(others)));
            partitioned = true;
            nextChange = tick + 10 + random.nextInt(31);
        } else if (action == 1 && crashed == 0) {
            crashed = pickNode();
            world.crashNode(crashed);
            restartAt = tick + 3 + random.nextInt(8);
            nextChange = restartAt;
        } else if (action == 2 && !storm) {
            world.setDuplicateStorm(true);
            storm = true;
            nextChange = tick + 5 + random.nextInt(10);
        } else if (action == 3 && admin.idle()) {
            reconfigure();
            nextChange = tick + 20 + random.nextInt(40);
        } else {
            world.network().heal();
            partitioned = false;
            if (storm) world.setDuplicateStorm(false);
            storm = false;
            nextChange = tick + 8 + random.nextInt(13);
        }
    }

    /** Join a group that is out, make a group leave (keeping at least one), or move one shard. */
    private void reconfigure() {
        SortedSet<Integer> joined = admin.joined();
        List<Integer> out = ShardedWorld.GROUP_IDS.stream().filter(gid -> !joined.contains(gid)).toList();
        int choice = random.nextInt(3);
        if (choice == 0 && !out.isEmpty()) {
            admin.join(out.get(random.nextInt(out.size())));
        } else if (choice == 1 && joined.size() > 1) {
            List<Integer> in = new ArrayList<>(joined);
            admin.leave(in.get(random.nextInt(in.size())));
        } else {
            List<Integer> in = new ArrayList<>(joined);
            admin.move(random.nextInt(jraft.shardctrler.ShardConfig.NSHARDS), in.get(random.nextInt(in.size())));
        }
        reconfigurations++;
    }

    /** Half of the time the leader of a random group (controller included), otherwise any node. */
    private int pickNode() {
        List<Integer> all = ShardedWorld.allNodeIds();
        if (random.nextBoolean()) {
            List<Integer> groupLeaders = new ArrayList<>();
            Integer controller = world.leaderOf(ShardedWorld.CONTROLLERS);
            if (controller != null) groupLeaders.add(controller);
            for (int gid : ShardedWorld.GROUP_IDS) {
                Integer leader = world.leaderOf(ShardedWorld.serversOf(gid));
                if (leader != null) groupLeaders.add(leader);
            }
            if (!groupLeaders.isEmpty()) return groupLeaders.get(random.nextInt(groupLeaders.size()));
        }
        return all.get(random.nextInt(all.size()));
    }

    public void stop() {
        stopped = true;
        world.healAll();
        crashed = 0;
        partitioned = false;
        storm = false;
    }

    public int reconfigurations() { return reconfigurations; }
}
