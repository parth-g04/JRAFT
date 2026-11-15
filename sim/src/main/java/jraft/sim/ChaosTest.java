package jraft.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Seed-reproducible workload runner. Use --seed N or --seeds COUNT. */
public final class ChaosTest {
    static final int CLIENTS = 3;
    static final int OPERATIONS_PER_CLIENT = 40;
    /** Faults run for at most this long; then the cluster is healed so remaining ops can finish. */
    private static final int FAULT_TICKS = 2_000;
    private static final int RECOVERY_TICKS = 2_000;

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

    public static void runSeed(long seed) throws Exception { runSeed(seed, false); }

    /**
     * @param unsafeLocalReads test-only negative control: serve GETs from the leader's local state
     *                         without ReadIndex. The checker is expected to catch stale reads.
     */
    public static void runSeed(long seed, boolean unsafeLocalReads) throws Exception {
        SimCluster cluster = new SimCluster(seed);
        cluster.setUnsafeLocalReads(unsafeLocalReads);
        cluster.setHistorySeed(seed);
        List<Clerk> clerks = new ArrayList<>();
        for (int c = 1; c <= CLIENTS; c++) clerks.add(new Clerk(c, cluster, seed * 1_000 + c, OPERATIONS_PER_CLIENT));
        try {
            for (int t = 0; t < FAULT_TICKS && !allDone(clerks); t++) cluster.tick();
            cluster.finishFaults();
            for (int t = 0; t < RECOVERY_TICKS && !allDone(clerks); t++) cluster.tick();
            if (!allDone(clerks)) {
                throw new IllegalStateException("clients did not finish after the cluster healed (liveness) at tick "
                        + cluster.clock().now() + " nodes=" + cluster.describeNodes());
            }
            cluster.runTicks(40);
            List<Map<String, String>> states = new ArrayList<>();
            cluster.stateMachines().forEach(machine -> states.add(machine.snapshotView()));
            if (states.size() != 3 || !states.get(0).equals(states.get(1)) || !states.get(1).equals(states.get(2))) {
                throw new IllegalStateException("replicas diverged: " + states);
            }
            LinearizabilityChecker.Result result = LinearizabilityChecker.check(cluster.history().operations());
            if (!result.linearizable()) {
                throw new IllegalStateException("history is NOT linearizable for key " + result.key()
                        + ": " + result.keyHistory() + "; history file " + cluster.history().path()
                        + " (make check-history FILE=" + cluster.history().path() + " for the visualization)");
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("seed " + seed + " failed. Reproduce: make chaos-one SEED=" + seed,
                    failure);
        }
    }

    private static boolean allDone(List<Clerk> clerks) { return clerks.stream().allMatch(Clerk::done); }
}
