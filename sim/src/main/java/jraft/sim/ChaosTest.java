package jraft.sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Seed-reproducible workload runner. Use --seed N or --seeds COUNT. */
public final class ChaosTest {
    private ChaosTest() { }

    public static void main(String[] args) throws Exception {
        long firstSeed = 1;
        int seeds = 1;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--seed")) firstSeed = Long.parseLong(args[++i]);
            else if (args[i].equals("--seeds")) seeds = Integer.parseInt(args[++i]);
        }
        for (int i = 0; i < seeds; i++) runSeed(firstSeed + i);
        System.out.println("Chaos passed for " + seeds + " seed(s)");
    }

    public static void runSeed(long seed) throws Exception {
        Path history = Path.of("build", "histories", seed + ".jsonl");
        Files.createDirectories(history.getParent());
        Files.deleteIfExists(history);
        SimCluster cluster = new SimCluster(seed);
        cluster.setHistorySeed(seed);
        Clerk clerk = new Clerk(1, cluster);
        try {
            cluster.runTicks(20);
            for (int i = 0; i < 12; i++) {
                String key = "k" + (i % 4);
                String value = "v" + i;
                clerk.execute(KvCommand.Operation.PUT, key, value);
                clerk.execute(KvCommand.Operation.GET, key, null);
            }
            cluster.finishFaults();
            cluster.runTicks(30);
            List<java.util.Map<String, String>> states = new ArrayList<>();
            cluster.stateMachines().forEach(machine -> states.add(machine.snapshotView()));
            if (!states.get(0).equals(states.get(1)) || !states.get(1).equals(states.get(2))) {
                throw new IllegalStateException("replicas diverged: " + states);
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("seed " + seed + " failed. Reproduce: make chaos-one SEED=" + seed,
                    failure);
        }
    }
}
