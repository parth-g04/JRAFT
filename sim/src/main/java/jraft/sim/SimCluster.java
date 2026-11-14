package jraft.sim;

import jraft.raftcore.NodeRole;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic single-threaded scheduler for one three-node Raft group. */
public final class SimCluster {
    private final SimClock clock = new SimClock();
    private final SimNetwork network;
    private final Nemesis nemesis;
    private final Map<Integer, SimNode> nodes = new TreeMap<>();
    private HistoryRecorder historyRecorder;
    private long historySeed;

    public SimCluster(long seed) {
        network = new SimNetwork(seed, 0.04, 0.01, 3);
        nemesis = new Nemesis(seed ^ 0x5DEECE66DL);
        List<Integer> ids = List.of(1, 2, 3);
        for (int id : ids) nodes.put(id, new SimNode(id, this, ids));
    }

    public void tick() {
        long now = clock.advance();
        nemesis.onTick(now, network);
        nodes.values().forEach(SimNode::tick);
        for (SimNetwork.Delivery delivery : network.drainDue(now)) {
            SimNode target = nodes.get(delivery.to());
            if (target != null) target.receive(delivery.from(), delivery.message());
        }
    }

    public void runTicks(int ticks) { for (int i = 0; i < ticks; i++) tick(); }

    public SimNode leader() {
        return nodes.values().stream().filter(node -> node.core().role() == NodeRole.LEADER).findFirst().orElse(null);
    }

    public KvStateMachine.Result result(long clientId, long sequence) {
        for (SimNode node : nodes.values()) {
            KvStateMachine.Result result = node.stateMachine().result(clientId, sequence);
            if (result != null) return result;
        }
        return null;
    }

    public void execute(KvCommand command, long clientId, long callTick) {
        long retryAt = Long.MIN_VALUE;
        for (int elapsed = 0; elapsed < 250; elapsed++) {
            if (clock.now() >= retryAt) {
                int targetId = 1 + (int) ((elapsed / 8) % nodes.size());
                nodes.get(targetId).propose(KvCommandCodec.encode(command));
                retryAt = clock.now() + 8;
            }
            tick();
            KvStateMachine.Result applied = result(command.clientId(), command.sequence());
            if (applied != null) {
                if (historyRecorder != null) historyRecorder.record(clientId, command, applied, callTick, clock.now());
                return;
            }
        }
        throw new IllegalStateException("operation timed out at tick " + clock.now()
                + "; Reproduce: make chaos-one SEED=" + historySeed);
    }

    public void setHistorySeed(long seed) {
        historySeed = seed;
        historyRecorder = new HistoryRecorder(Path.of("build", "histories", seed + ".jsonl"));
    }
    public SimClock clock() { return clock; }
    public SimNetwork network() { return network; }
    public List<KvStateMachine> stateMachines() {
        List<KvStateMachine> machines = new ArrayList<>();
        nodes.values().forEach(node -> machines.add(node.stateMachine()));
        return List.copyOf(machines);
    }
}
