package jraft.sim;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-process linearizability checker for KV histories, so every chaos seed is checked even
 * without Go. tools/porcupine-check implements the same KV model independently and runs in CI.
 *
 * Linearizable means: there is one total order of the operations that (1) respects real time,
 * i.e. if A returned before B was called then A comes first, and (2) is a legal sequential KV
 * execution. Keys are independent, so each key's sub-history is checked on its own.
 *
 * Search (Wing and Gong, with memoization as in Lowe / Porcupine): repeatedly choose an operation
 * that may go next. It may go next if no other remaining operation returned before it was called.
 * Apply it to the model; if the model rejects it, backtrack. A (set of linearized ops, value)
 * pair that already failed is never explored again.
 */
public final class LinearizabilityChecker {
    /** One completed operation. {@code output}: value returned by get (null = absent); null for writes. */
    public record Operation(long clientId, String op, String key, String value, String output, long call, long ret) { }

    public record Result(boolean linearizable, String key, List<Operation> keyHistory) {
        static Result ok() { return new Result(true, null, List.of()); }
    }

    private LinearizabilityChecker() { }

    public static Result check(List<Operation> history) {
        Map<String, List<Operation>> byKey = new TreeMap<>();
        for (Operation operation : history) byKey.computeIfAbsent(operation.key(), k -> new ArrayList<>()).add(operation);
        for (Map.Entry<String, List<Operation>> entry : byKey.entrySet()) {
            List<Operation> operations = new ArrayList<>(entry.getValue());
            operations.sort(Comparator.comparingLong(Operation::call));
            if (!new Search(operations).run()) return new Result(false, entry.getKey(), operations);
        }
        return Result.ok();
    }

    private static final class Search {
        private record Visited(BitSet linearized, String value) { }

        private final List<Operation> operations;
        private final BitSet linearized = new BitSet();
        private final Set<Visited> failed = new HashSet<>(); // lookup only; order never matters

        Search(List<Operation> operations) { this.operations = operations; }

        boolean run() { return search(null); }

        private boolean search(String value) {
            if (linearized.cardinality() == operations.size()) return true;
            Visited state = new Visited((BitSet) linearized.clone(), value);
            if (failed.contains(state)) return false;
            // An operation may go next only if it was called before every remaining operation returned.
            long earliestReturn = Long.MAX_VALUE;
            for (int i = linearized.nextClearBit(0); i < operations.size(); i = linearized.nextClearBit(i + 1)) {
                earliestReturn = Math.min(earliestReturn, operations.get(i).ret());
            }
            for (int i = linearized.nextClearBit(0); i < operations.size(); i = linearized.nextClearBit(i + 1)) {
                Operation candidate = operations.get(i);
                if (candidate.call() > earliestReturn) break; // sorted by call: no later op can go next either
                Step step = apply(value, candidate);
                if (!step.legal()) continue;
                linearized.set(i);
                if (search(step.value())) return true;
                linearized.clear(i);
            }
            failed.add(state);
            return false;
        }
    }

    private record Step(boolean legal, String value) { }

    /** Sequential KV model; must match kvModel() in tools/porcupine-check/main.go. */
    private static Step apply(String value, Operation operation) {
        return switch (operation.op()) {
            case "get" -> new Step(Objects.equals(value, operation.output()), value);
            case "put" -> new Step(operation.output() == null, operation.value());
            case "append" -> new Step(operation.output() == null, (value == null ? "" : value) + operation.value());
            case "delete" -> new Step(Objects.equals(value, operation.output()), null);
            default -> new Step(false, value);
        };
    }
}
