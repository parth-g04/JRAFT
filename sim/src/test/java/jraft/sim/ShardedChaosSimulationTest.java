package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ShardedChaosSimulationTest {
    @Test
    void representativeSeedsAreLinearizableAndEveryShardEndsAtItsOwner() {
        for (long seed = 1; seed <= 5; seed++) {
            ShardedChaosTest.Outcome outcome = ShardedChaosTest.runSeed(seed);
            assertThat(outcome.finalConfigNum()).as("seed %d reconfigured", seed).isGreaterThan(1);
        }
    }

    @Test
    void readingFromAShardThatMovedAwayIsCaughtByTheChecker() {
        // Negative control: answering GETs from a local copy without ownership checks or ReadIndex
        // returns stale data after migration; the checker must flag it.
        assertThatThrownBy(() -> ShardedChaosTest.runSeed(4, true))
                .hasMessageContaining("Reproduce: make shard-chaos-one SEED=4")
                .rootCause().hasMessageContaining("NOT linearizable");
    }
}
