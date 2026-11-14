package jraft.sim;

import java.util.List;
import java.util.Random;
import java.util.Set;

/** Reproducibly schedules short network partitions and heals. */
public final class Nemesis {
    private final Random random;
    private long nextChange;
    private boolean partitioned;

    public Nemesis(long seed) {
        random = new Random(seed);
        nextChange = 20 + random.nextInt(11);
    }

    public void onTick(long tick, SimNetwork network) {
        if (tick < nextChange) return;
        if (partitioned) {
            network.heal();
            partitioned = false;
            nextChange = tick + 8 + random.nextInt(13);
        } else {
            int isolated = 1 + random.nextInt(3);
            Set<Integer> majority = Set.of(1, 2, 3).stream().filter(id -> id != isolated)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            network.partition(List.of(Set.of(isolated), majority));
            partitioned = true;
            nextChange = tick + 3 + random.nextInt(5);
        }
    }

    public void heal(SimNetwork network) { network.heal(); partitioned = false; }
}
