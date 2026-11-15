package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Cross-checks simulator histories with the independent Go/Porcupine checker (runs in CI). */
class PorcupineCheckerIntegrationTest {
    @Test
    void generatedHistoryIsCheckedWhenGoIsInstalled() throws Exception {
        assumeTrue(goAvailable(), "Go 1.22 is not installed; skipping Porcupine history check");
        long seed = 9001;
        ChaosTest.runSeed(seed);
        CheckerRun run = runChecker(seed);
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("OK");
    }

    @Test
    void porcupineAlsoFlagsTheStaleReadHistory() throws Exception {
        assumeTrue(goAvailable(), "Go 1.22 is not installed; skipping Porcupine history check");
        long seed = 34;
        try {
            ChaosTest.runSeed(seed, true); // without ReadIndex: expected to fail the Java checker
        } catch (IllegalStateException expected) {
            // the history file is still written; now ask Porcupine
        }
        CheckerRun run = runChecker(seed);
        assertThat(run.output()).as(run.output()).contains("ILLEGAL");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    private record CheckerRun(int exitCode, String output) { }

    private static CheckerRun runChecker(long seed) throws Exception {
        // Gradle runs tests from sim/, so resolve the checker from the repository root.
        Path repoRoot = Path.of(System.getProperty("jraft.repoRoot", "..")).toAbsolutePath();
        Path checkerDirectory = repoRoot.resolve("tools").resolve("porcupine-check");
        Path history = Path.of("build", "histories", seed + ".jsonl").toAbsolutePath();
        Process process = new ProcessBuilder("go", "run", ".", history.toString())
                .directory(checkerDirectory.toFile()).redirectErrorStream(true).start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Porcupine checker should finish").isTrue();
        return new CheckerRun(process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }

    private static boolean goAvailable() {
        try {
            Process process = new ProcessBuilder("go", "version").start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            return finished && process.exitValue() == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }
}
