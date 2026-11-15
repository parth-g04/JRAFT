package jraft.sim;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChaosSimulationTest {
    @Test
    void representativeSeedsAreLinearizableAndReplicasConverge() throws Exception {
        for (long seed = 1; seed <= 10; seed++) ChaosTest.runSeed(seed);
    }

    @Test
    void regressionSeed81KeepsClusterAvailableAfterRestartAndPartition() throws Exception {
        ChaosTest.runSeed(81);
    }

    @Test
    void seed22IsLinearizableWithReadIndex() throws Exception {
        ChaosTest.runSeed(22);
    }

    @Test
    void seed22WithoutReadIndexIsCaughtByTheChecker() {
        // Negative control: the same seed with reads served from local state must be flagged.
        assertThatThrownBy(() -> ChaosTest.runSeed(22, true))
                .hasMessageContaining("Reproduce: make chaos-one SEED=22")
                .rootCause().hasMessageContaining("NOT linearizable");
    }
}
