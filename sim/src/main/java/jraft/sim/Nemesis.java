package jraft.sim;

import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/** Reproducibly schedules partitions, crash/restarts and message-duplication storms. */
public final class Nemesis {
    private final Random random;
    private long nextChange;
    private boolean partitioned;
    private boolean storm;
    private int crashedNode;
    private long restartAt = Long.MAX_VALUE;
    private boolean stopped;

    public Nemesis(long seed) {
        random = new Random(seed);
        nextChange = 20 + random.nextInt(11);
    }

    public void onTick(long tick, SimCluster cluster) {
        if (stopped) return;
        if (crashedNode != 0 && tick >= restartAt) {
            cluster.restartNode(crashedNode);
            crashedNode = 0;
            restartAt = Long.MAX_VALUE;
        }
        if (tick < nextChange) return;
        int action = random.nextInt(4);
        if (action == 0 && !partitioned) {
            // Isolate one node; half the time deliberately the current leader. Clients can still reach
            // the isolated old leader while the majority elects a new one and commits new writes:
            // that is exactly where a read without ReadIndex would return stale data.
            Integer leader = cluster.leaderId();
            int isolated = leader != null && random.nextBoolean() ? leader : 1 + random.nextInt(3);
            Set<Integer> majority = SimCluster.NODE_IDS.stream().filter(id -> id != isolated)
                    .collect(Collectors.toUnmodifiableSet());
            cluster.network().partition(java.util.List.of(Set.of(isolated), majority));
            partitioned = true;
            // Long enough for the majority to elect a new leader and commit writes.
            nextChange = tick + 10 + random.nextInt(31);
        } else if (action == 1 && crashedNode == 0) {
            crashedNode = 1 + random.nextInt(3);
            cluster.crashNode(crashedNode);
            restartAt = tick + 3 + random.nextInt(5);
            nextChange = restartAt;
        } else if (action == 2 && !storm) {
            // Duplicate many Raft and client messages for a while.
            cluster.setDuplicateStorm(true);
            storm = true;
            nextChange = tick + 5 + random.nextInt(10);
        } else {
            cluster.network().heal();
            partitioned = false;
            if (storm) cluster.setDuplicateStorm(false);
            storm = false;
            nextChange = tick + 8 + random.nextInt(13);
        }
    }

    public void heal(SimNetwork network) { network.heal(); partitioned = false; }

    public void finish(SimCluster cluster) {
        stopped = true;
        cluster.network().heal();
        if (crashedNode != 0) cluster.restartNode(crashedNode);
        if (storm) cluster.setDuplicateStorm(false);
        crashedNode = 0;
        partitioned = false;
        storm = false;
    }
}
