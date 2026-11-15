package jraft.sim;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntConsumer;

/** Reproducibly schedules short network partitions and heals. */
public final class Nemesis {
    private final Random random;
    private long nextChange;
    private boolean partitioned;
    private int crashedNode;
    private long restartAt = Long.MAX_VALUE;
    private boolean stopped;

    public Nemesis(long seed) {
        random = new Random(seed);
        nextChange = 20 + random.nextInt(11);
    }

    public void onTick(long tick, SimNetwork network, IntConsumer crash, IntConsumer restart) {
        if (stopped) return;
        if (crashedNode != 0 && tick >= restartAt) {
            restart.accept(crashedNode);
            crashedNode = 0;
            restartAt = Long.MAX_VALUE;
        }
        if (tick < nextChange) return;
        int action = random.nextInt(3);
        if (action == 0 && !partitioned) {
            int isolated = 1 + random.nextInt(3);
            Set<Integer> majority = Set.of(1, 2, 3).stream().filter(id -> id != isolated)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            network.partition(List.of(Set.of(isolated), majority));
            partitioned = true;
            nextChange = tick + 3 + random.nextInt(5);
        } else if (action == 1 && crashedNode == 0) {
            crashedNode = 1 + random.nextInt(3);
            crash.accept(crashedNode);
            restartAt = tick + 3 + random.nextInt(5);
            nextChange = restartAt;
        } else {
            network.heal();
            partitioned = false;
            nextChange = tick + 8 + random.nextInt(13);
        }
    }

    public void heal(SimNetwork network) { network.heal(); partitioned = false; }

    public void finish(SimNetwork network, IntConsumer restart) {
        stopped = true;
        network.heal();
        if (crashedNode != 0) restart.accept(crashedNode);
        crashedNode = 0;
        partitioned = false;
    }
}
