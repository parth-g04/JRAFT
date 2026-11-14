package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PorcupineCheckerIntegrationTest {
    @Test
    void generatedHistoryIsCheckedWhenGoIsInstalled() throws Exception {
        assumeTrue(goAvailable(), "Go 1.22 is not installed; skipping Porcupine history check");
        long seed = 9001;
        ChaosTest.runSeed(seed);
        Path checkerDirectory = Path.of("tools", "porcupine-check").toAbsolutePath();
        Path history = Path.of("build", "histories", seed + ".jsonl").toAbsolutePath();
        Process process = new ProcessBuilder("go", "run", ".", history.toString())
                .directory(checkerDirectory.toFile()).redirectErrorStream(true).start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Porcupine checker should finish").isTrue();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("OK");
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
