package jraft.shardctrler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic minimal-movement rebalancing (ARCHITECTURE.md section 5).
 *
 * Goal: every group owns floor(N/g) or ceil(N/g) shards, and as few shards as possible move.
 * 1. Target sizes: the (N mod g) groups that already own the most shards (ties: lower gid) get
 *    ceil(N/g); the rest get floor(N/g). Giving the larger targets to the larger groups keeps the
 *    most shards in place.
 * 2. Shards of groups that no longer exist (or unassigned shards) go to a free pool.
 * 3. Groups above target release their highest-numbered shards into the pool.
 * 4. Groups below target take the lowest-numbered free shards.
 * Only shards forced out by steps 2-3 move, so the number of moves is minimal.
 * Every iteration is over sorted collections, so all replicas compute the same result.
 */
public final class Rebalancer {
    private Rebalancer() { }

    public static List<Integer> rebalance(List<Integer> shards, Collection<Integer> groupIds) {
        int n = shards.size();
        TreeSet<Integer> groups = new TreeSet<>(groupIds);
        if (groups.isEmpty()) return new ArrayList<>(Collections.nCopies(n, 0));

        Map<Integer, TreeSet<Integer>> owned = new TreeMap<>();
        for (int gid : groups) owned.put(gid, new TreeSet<>());
        TreeSet<Integer> free = new TreeSet<>();
        for (int shard = 0; shard < n; shard++) {
            int gid = shards.get(shard);
            if (groups.contains(gid)) owned.get(gid).add(shard);
            else free.add(shard);
        }

        List<Integer> order = new ArrayList<>(groups);
        order.sort(Comparator.<Integer>comparingInt(gid -> -owned.get(gid).size()).thenComparingInt(gid -> gid));
        Map<Integer, Integer> target = new TreeMap<>();
        int base = n / groups.size();
        int extra = n % groups.size();
        for (int i = 0; i < order.size(); i++) target.put(order.get(i), base + (i < extra ? 1 : 0));

        for (int gid : order) {
            TreeSet<Integer> mine = owned.get(gid);
            while (mine.size() > target.get(gid)) free.add(mine.pollLast());
        }
        for (int gid : order) {
            TreeSet<Integer> mine = owned.get(gid);
            while (mine.size() < target.get(gid)) mine.add(free.pollFirst());
        }

        List<Integer> result = new ArrayList<>(Collections.nCopies(n, 0));
        owned.forEach((gid, mine) -> mine.forEach(shard -> result.set(shard, gid)));
        return result;
    }

    /** Number of shards whose owner differs between two assignments. */
    public static int moves(List<Integer> before, List<Integer> after) {
        int moved = 0;
        for (int i = 0; i < before.size(); i++) if (!before.get(i).equals(after.get(i))) moved++;
        return moved;
    }
}
