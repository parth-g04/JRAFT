package jraft.raftcore;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Cluster membership (Raft thesis chapter 4). Voters elect leaders and form commit quorums;
 * learners only receive the log, so a new server can catch up before it can affect quorums.
 * Lists are sorted and disjoint so every replica iterates them in the same order.
 */
public record Configuration(List<Integer> voters, List<Integer> learners) {
    public Configuration {
        voters = List.copyOf(new TreeSet<>(voters));
        learners = List.copyOf(new TreeSet<>(learners));
        for (int learner : learners) {
            if (voters.contains(learner)) throw new IllegalArgumentException("server " + learner + " is both voter and learner");
        }
    }

    public static Configuration ofVoters(List<Integer> voters) { return new Configuration(voters, List.of()); }

    public boolean isVoter(int id) { return voters.contains(id); }
    public boolean isLearner(int id) { return learners.contains(id); }
    public boolean isMember(int id) { return isVoter(id) || isLearner(id); }

    /** Voters and learners, sorted. */
    public List<Integer> members() {
        TreeSet<Integer> all = new TreeSet<>(voters);
        all.addAll(learners);
        return List.copyOf(all);
    }

    /** True when {@code acks} contains a majority of the voters. */
    public boolean isQuorum(java.util.Collection<Integer> acks) {
        int count = 0;
        for (int voter : voters) if (acks.contains(voter)) count++;
        return count >= voters.size() / 2 + 1;
    }

    public Configuration withLearner(int id) {
        List<Integer> newLearners = new ArrayList<>(learners);
        newLearners.add(id);
        return new Configuration(voters, newLearners);
    }

    public Configuration promote(int id) {
        List<Integer> newVoters = new ArrayList<>(voters);
        newVoters.add(id);
        return new Configuration(newVoters, learners.stream().filter(l -> l != id).toList());
    }

    public Configuration without(int id) {
        return new Configuration(voters.stream().filter(v -> v != id).toList(),
                learners.stream().filter(l -> l != id).toList());
    }

    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * (2 + voters.size() + learners.size()));
        buffer.putInt(voters.size());
        voters.forEach(buffer::putInt);
        buffer.putInt(learners.size());
        learners.forEach(buffer::putInt);
        return buffer.array();
    }

    public static Configuration decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        List<Integer> voters = new ArrayList<>();
        int voterCount = buffer.getInt();
        for (int i = 0; i < voterCount; i++) voters.add(buffer.getInt());
        List<Integer> learners = new ArrayList<>();
        int learnerCount = buffer.getInt();
        for (int i = 0; i < learnerCount; i++) learners.add(buffer.getInt());
        if (buffer.hasRemaining()) throw new IllegalArgumentException("trailing configuration bytes");
        return new Configuration(voters, learners);
    }

    @Override public String toString() { return "voters=" + voters + (learners.isEmpty() ? "" : " learners=" + learners); }
}
