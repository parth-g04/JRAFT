package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import jraft.sim.LinearizabilityChecker.Operation;
import org.junit.jupiter.api.Test;

class LinearizabilityCheckerTest {
    @Test
    void sequentialPutThenGetIsLinearizable() {
        assertThat(check(put(1, "x", "a", 1, 2), get(2, "x", "a", 3, 4))).isTrue();
    }

    @Test
    void staleReadAfterACompletedWriteIsRejected() {
        // put(b) returned at 4, the get started at 5 but still saw "a".
        assertThat(check(put(1, "x", "a", 1, 2), put(1, "x", "b", 3, 4), get(2, "x", "a", 5, 6))).isFalse();
    }

    @Test
    void concurrentOperationsMayBeOrderedEitherWay() {
        // The get overlaps put(b), so both "a" and "b" are valid answers.
        assertThat(check(put(1, "x", "a", 1, 2), put(1, "x", "b", 3, 10), get(2, "x", "a", 4, 5))).isTrue();
        assertThat(check(put(1, "x", "a", 1, 2), put(1, "x", "b", 3, 10), get(2, "x", "b", 4, 5))).isTrue();
    }

    @Test
    void duplicatedAppendIsRejected() {
        assertThat(check(append(1, "x", "a;", 1, 2), get(2, "x", "a;a;", 3, 4))).isFalse();
        assertThat(check(append(1, "x", "a;", 1, 2), get(2, "x", "a;", 3, 4))).isTrue();
    }

    @Test
    void readOfMissingKeyBeforeAnyWriteIsLegal() {
        assertThat(check(get(1, "x", null, 1, 2), put(2, "x", "a", 3, 4))).isTrue();
        assertThat(check(put(2, "x", "a", 1, 2), get(1, "x", null, 3, 4))).isFalse();
    }

    @Test
    void keysAreCheckedIndependently() {
        LinearizabilityChecker.Result result = LinearizabilityChecker.check(List.of(
                put(1, "x", "a", 1, 2), get(1, "x", "a", 3, 4),
                put(1, "y", "b", 1, 2), get(1, "y", "stale", 3, 4)));
        assertThat(result.linearizable()).isFalse();
        assertThat(result.key()).isEqualTo("y");
    }

    private static boolean check(Operation... operations) {
        return LinearizabilityChecker.check(List.of(operations)).linearizable();
    }

    private static Operation put(long client, String key, String value, long call, long ret) {
        return new Operation(client, "put", key, value, null, call, ret);
    }

    private static Operation append(long client, String key, String value, long call, long ret) {
        return new Operation(client, "append", key, value, null, call, ret);
    }

    private static Operation get(long client, String key, String output, long call, long ret) {
        return new Operation(client, "get", key, null, output, call, ret);
    }
}
