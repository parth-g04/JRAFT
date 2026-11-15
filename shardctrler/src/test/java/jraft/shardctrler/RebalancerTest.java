package jraft.shardctrler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RebalancerTest {

    @Test
    void firstJoinGetsAllShardsAndLaterJoinsSplitThemEvenly() {
        List<Integer> one = Rebalancer.rebalance(Collections.nCopies(16, 0), List.of(100));
        assertThat(one).containsOnly(100);

        List<Integer> two = Rebalancer.rebalance(one, List.of(100, 101));
        assertThat(counts(two)).containsEntry(100, 8).containsEntry(101, 8);
        assertThat(Rebalancer.moves(one, two)).isEqualTo(8);

        List<Integer> three = Rebalancer.rebalance(two, List.of(100, 101, 102));
        assertThat(counts(three).values()).containsExactlyInAnyOrder(6, 5, 5);
        assertThat(Rebalancer.moves(two, three)).isEqualTo(5);   // only the new group's share moves
    }

    @Test
    void leaveMovesOnlyTheLeavingGroupsShards() {
        List<Integer> three = Rebalancer.rebalance(
                Rebalancer.rebalance(Rebalancer.rebalance(Collections.nCopies(16, 0), List.of(1)), List.of(1, 2)),
                List.of(1, 2, 3));
        int leaving = counts(three).get(2);
        List<Integer> two = Rebalancer.rebalance(three, List.of(1, 3));
        assertThat(two).doesNotContain(2);
        assertThat(Rebalancer.moves(three, two)).isEqualTo(leaving);
        assertThat(Rebalancer.rebalance(two, List.of())).containsOnly(0);
    }

    @Test
    void resultIsBalancedForRandomInputs() {
        Random random = new Random(7);
        for (int trial = 0; trial < 2_000; trial++) {
            List<Integer> groups = randomGroups(random);
            List<Integer> before = randomAssignment(random, 16, groups);
            List<Integer> after = Rebalancer.rebalance(before, groups);
            Map<Integer, Integer> counts = counts(after);
            for (int gid : groups) counts.putIfAbsent(gid, 0);
            assertThat(counts.keySet()).containsExactlyInAnyOrderElementsOf(groups);
            int max = Collections.max(counts.values());
            int min = Collections.min(counts.values());
            assertThat(max - min).as("balanced: %s", after).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void resultIsDeterministicAndIndependentOfGroupOrder() {
        Random random = new Random(11);
        for (int trial = 0; trial < 500; trial++) {
            List<Integer> groups = randomGroups(random);
            List<Integer> before = randomAssignment(random, 16, groups);
            List<Integer> shuffled = new ArrayList<>(groups);
            Collections.shuffle(shuffled, random);
            assertThat(Rebalancer.rebalance(before, shuffled)).isEqualTo(Rebalancer.rebalance(before, groups));
        }
    }

    @Test
    void numberOfMovesIsMinimalComparedToBruteForce() {
        // For 6 shards and up to 3 groups we can enumerate every balanced assignment and find the
        // true minimum number of moves, independently of the algorithm under test.
        Random random = new Random(3);
        for (int trial = 0; trial < 300; trial++) {
            List<Integer> groups = new ArrayList<>(List.of(1, 2, 3).subList(0, 1 + random.nextInt(3)));
            List<Integer> before = new ArrayList<>();
            for (int s = 0; s < 6; s++) before.add(random.nextInt(5)); // owners 0..4, some not in groups
            List<Integer> after = Rebalancer.rebalance(before, groups);
            assertThat(Rebalancer.moves(before, after)).as("before=%s groups=%s", before, groups)
                    .isEqualTo(bruteForceMinimumMoves(before, groups));
        }
    }

    private static int bruteForceMinimumMoves(List<Integer> before, List<Integer> groups) {
        int n = before.size();
        int best = Integer.MAX_VALUE;
        int[] choice = new int[n];
        long combinations = (long) Math.pow(groups.size(), n);
        for (long code = 0; code < combinations; code++) {
            long rest = code;
            for (int s = 0; s < n; s++) { choice[s] = groups.get((int) (rest % groups.size())); rest /= groups.size(); }
            Map<Integer, Integer> counts = new TreeMap<>();
            for (int gid : groups) counts.put(gid, 0);
            for (int gid : choice) counts.merge(gid, 1, Integer::sum);
            if (Collections.max(counts.values()) - Collections.min(counts.values()) > 1) continue;
            int moved = 0;
            for (int s = 0; s < n; s++) if (choice[s] != before.get(s)) moved++;
            best = Math.min(best, moved);
        }
        return best;
    }

    private static List<Integer> randomGroups(Random random) {
        List<Integer> groups = new ArrayList<>();
        int count = 1 + random.nextInt(20);           // includes more groups than shards
        for (int i = 0; i < count; i++) groups.add(100 + random.nextInt(40));
        return groups.stream().distinct().toList();
    }

    private static List<Integer> randomAssignment(Random random, int n, List<Integer> groups) {
        List<Integer> assignment = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            int pick = random.nextInt(groups.size() + 2);
            assignment.add(pick < groups.size() ? groups.get(pick) : (pick == groups.size() ? 0 : 999));
        }
        return assignment;
    }

    private static Map<Integer, Integer> counts(List<Integer> assignment) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int gid : assignment) if (gid != 0) counts.merge(gid, 1, Integer::sum);
        return counts;
    }
}
