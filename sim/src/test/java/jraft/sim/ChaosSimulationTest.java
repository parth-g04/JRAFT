package jraft.sim;

import org.junit.jupiter.api.Test;

class ChaosSimulationTest {
    @Test
    void representativeSeedsKeepReplicasConsistent() throws Exception {
        for (long seed = 1; seed <= 10; seed++) ChaosTest.runSeed(seed);
    }
}
