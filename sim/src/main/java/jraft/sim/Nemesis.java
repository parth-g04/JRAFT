package jraft.sim;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/** Reproducibly schedules partitions, crash/restarts, message-duplication storms and membership changes. */
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
        int action = random.nextInt(5);
        if (action == 0 && !partitioned) {
            // Isolate one node; half the time deliberately the current leader. Clients can still reach
            // the isolated old leader while the majority elects a new one and commits new writes:
            // that is exactly where a read without ReadIndex would return stale data.
            Integer leader = cluster.leaderId();
            int isolated = leader != null && random.nextBoolean() ? leader : randomMember(cluster);
            Set<Integer> majority = SimCluster.ALL_NODE_IDS.stream().filter(id -> id != isolated)
                    .collect(Collectors.toUnmodifiableSet());
            cluster.network().partition(java.util.List.of(Set.of(isolated), majority));
            partitioned = true;
            // Long enough for the majority to elect a new leader and commit writes.
            nextChange = tick + 10 + random.nextInt(31);
        } else if (action == 1 && crashedNode == 0) {
            crashedNode = randomMember(cluster);
            cluster.crashNode(crashedNode);
            restartAt = tick + 3 + random.nextInt(5);
            nextChange = restartAt;
        } else if (action == 2 && !storm) {
            // Duplicate many Raft and client messages for a while.
            cluster.setDuplicateStorm(true);
            storm = true;
            nextChange = tick + 5 + random.nextInt(10);
        } else if (action == 3) {
            changeMembership(cluster);
            nextChange = tick + 5 + random.nextInt(10);
        } else {
            cluster.network().heal();
            partitioned = false;
            if (storm) cluster.setDuplicateStorm(false);
            storm = false;
            nextChange = tick + 8 + random.nextInt(13);
        }
    }

    /**
     * Add a server that is not in the configuration, or remove a voter (possibly the leader) while
     * keeping at least three voters. The leader may refuse (change in progress); that is fine.
     */
    private void changeMembership(SimCluster cluster) {
        var configuration = cluster.configuration();
        if (configuration == null) return;
        List<Integer> outsiders = SimCluster.ALL_NODE_IDS.stream().filter(id -> !configuration.isMember(id)).toList();
        boolean add = !outsiders.isEmpty() && (configuration.voters().size() <= 3 || random.nextBoolean());
        if (add) {
            cluster.addServer(outsiders.get(random.nextInt(outsiders.size())));
        } else if (configuration.voters().size() > 3) {
            List<Integer> voters = configuration.voters();
            cluster.removeServer(voters.get(random.nextInt(voters.size())));
        }
    }

    private int randomMember(SimCluster cluster) {
        var configuration = cluster.configuration();
        List<Integer> members = configuration == null ? SimCluster.NODE_IDS : configuration.members();
        return members.get(random.nextInt(members.size()));
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
