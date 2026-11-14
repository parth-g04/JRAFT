package jraft.sim;

import jraft.raftcore.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/** Seeded in-memory network. Only this class chooses delay, loss, duplication, or partition. */
public final class SimNetwork {
    public record Delivery(int from, int to, Message message) { }
    private record Scheduled(long at, long order, Delivery delivery) { }

    private final Random random;
    private final PriorityQueue<Scheduled> queue = new PriorityQueue<>(
            Comparator.comparingLong(Scheduled::at).thenComparingLong(Scheduled::order));
    private final Map<Integer, Integer> partitionSide = new HashMap<>();
    private double dropProbability;
    private double duplicateProbability;
    private int maximumDelay;
    private long sequence;

    public SimNetwork(long seed, double dropProbability, double duplicateProbability, int maximumDelay) {
        this.random = new Random(seed);
        configure(dropProbability, duplicateProbability, maximumDelay);
    }

    public void configure(double drop, double duplicate, int delay) {
        if (drop < 0 || drop > 1 || duplicate < 0 || duplicate > 1 || delay < 0) {
            throw new IllegalArgumentException("invalid network fault probabilities or delay");
        }
        dropProbability = drop;
        duplicateProbability = duplicate;
        maximumDelay = delay;
    }

    /** Nodes in different listed groups cannot exchange messages. Unlisted nodes share a group. */
    public void partition(List<Set<Integer>> groups) {
        partitionSide.clear();
        for (int side = 0; side < groups.size(); side++) {
            for (int node : groups.get(side)) partitionSide.put(node, side + 1);
        }
    }

    public void heal() { partitionSide.clear(); }

    public void send(long now, int from, int to, Message message) {
        int fromSide = partitionSide.getOrDefault(from, 0);
        int toSide = partitionSide.getOrDefault(to, 0);
        if (fromSide != toSide || random.nextDouble() < dropProbability) return;
        schedule(now, from, to, message);
        if (random.nextDouble() < duplicateProbability) schedule(now, from, to, message);
    }

    private void schedule(long now, int from, int to, Message message) {
        int delay = maximumDelay == 0 ? 0 : random.nextInt(maximumDelay + 1);
        queue.add(new Scheduled(now + delay, sequence++, new Delivery(from, to, message)));
    }

    public List<Delivery> drainDue(long now) {
        List<Delivery> ready = new ArrayList<>();
        while (!queue.isEmpty() && queue.peek().at() <= now) {
            Delivery delivery = queue.remove().delivery();
            int fromSide = partitionSide.getOrDefault(delivery.from(), 0);
            int toSide = partitionSide.getOrDefault(delivery.to(), 0);
            if (fromSide == toSide) ready.add(delivery);
        }
        return ready;
    }

    public int pendingMessages() { return queue.size(); }
}
