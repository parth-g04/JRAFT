package jraft.sim;

import jraft.raftcore.NodeRole;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;

/**
 * Deterministic single-threaded scheduler for one three-node Raft group and its clients.
 * Every random choice comes from a Random seeded by the run's seed.
 */
public final class SimCluster {
    /** Anything that receives client replies and acts on ticks (a Clerk, or a scripted test client). */
    public interface Client {
        void onReply(ClientReply reply);
        default void tick(long now) { }
    }

    private record ClientEnvelope(long at, long order, int nodeId, ClientRequest request, ClientReply reply) { }

    public static final List<Integer> NODE_IDS = List.of(1, 2, 3);
    private static final double CLIENT_DROP = 0.05;
    private static final double CLIENT_DUPLICATE = 0.05;
    private static final double STORM_DUPLICATE = 0.3;
    private static final int CLIENT_MAX_DELAY = 2;

    private final SimClock clock = new SimClock();
    private final SimNetwork network;
    private final Nemesis nemesis;
    private final Map<Integer, SimNode> nodes = new TreeMap<>();
    private final Map<Integer, SimDisk> disks = new TreeMap<>();
    private final Map<Long, Client> clients = new TreeMap<>();
    private final Random crashRandom;
    private final Random clientRandom;
    private final PriorityQueue<ClientEnvelope> clientQueue = new PriorityQueue<>(
            Comparator.comparingLong(ClientEnvelope::at).thenComparingLong(ClientEnvelope::order));
    private final boolean faults;
    private long clientOrder;
    private boolean duplicateStorm;
    private boolean unsafeLocalReads;
    private long eventTick = -1;
    private long eventCounter;
    private HistoryRecorder historyRecorder;
    private long historySeed;

    /** Chaos cluster: random network faults, client-channel faults and a nemesis. */
    public SimCluster(long seed) { this(seed, true); }

    /** Fault-free cluster for scripted scenarios; faults are injected by the test itself. */
    public static SimCluster quiet(long seed) { return new SimCluster(seed, false); }

    private SimCluster(long seed, boolean faults) {
        this.faults = faults;
        network = faults ? new SimNetwork(seed, 0.04, 0.01, 3) : new SimNetwork(seed, 0, 0, 1);
        nemesis = faults ? new Nemesis(seed ^ 0x5DEECE66DL) : null;
        crashRandom = new Random(seed * 31 + 7);
        clientRandom = new Random(seed * 17 + 3);
        historySeed = seed;
        for (int id : NODE_IDS) {
            SimDisk disk = new SimDisk();
            disks.put(id, disk);
            nodes.put(id, new SimNode(id, this, NODE_IDS, disk));
        }
    }

    public void tick() {
        long now = clock.advance();
        if (nemesis != null) nemesis.onTick(now, this);
        nodes.values().forEach(SimNode::tick);
        for (SimNetwork.Delivery delivery : network.drainDue(now)) {
            SimNode target = nodes.get(delivery.to());
            if (target != null) target.receive(delivery.from(), delivery.message());
        }
        while (!clientQueue.isEmpty() && clientQueue.peek().at() <= now) {
            ClientEnvelope envelope = clientQueue.remove();
            if (envelope.request() != null) {
                SimNode target = nodes.get(envelope.nodeId());   // null if crashed: request lost
                if (target != null) target.onClientRequest(envelope.request());
            } else {
                Client client = clients.get(envelope.reply().clientId());
                if (client != null) client.onReply(envelope.reply());
            }
        }
        for (Client client : List.copyOf(clients.values())) client.tick(now);
    }

    public void runTicks(int ticks) { for (int i = 0; i < ticks; i++) tick(); }

    // ---------------- client channel ----------------

    public void register(long clientId, Client client) { clients.put(clientId, client); }

    /** Client -> server. Partitions do not apply: a client can still reach an isolated old leader. */
    public void sendToServer(int nodeId, ClientRequest request) {
        enqueueClientMessage(nodeId, request, null);
    }

    void replyToClient(int fromNode, ClientReply reply) { enqueueClientMessage(fromNode, null, reply); }

    private void enqueueClientMessage(int nodeId, ClientRequest request, ClientReply reply) {
        if (faults && clientRandom.nextDouble() < CLIENT_DROP) return;
        int copies = 1;
        double duplicate = duplicateStorm ? STORM_DUPLICATE : CLIENT_DUPLICATE;
        if (faults && clientRandom.nextDouble() < duplicate) copies = 2;
        for (int copy = 0; copy < copies; copy++) {
            int delay = faults ? clientRandom.nextInt(CLIENT_MAX_DELAY + 1) : 0;
            clientQueue.add(new ClientEnvelope(clock.now() + delay + 1, clientOrder++, nodeId, request, reply));
        }
    }

    /**
     * Strictly increasing simulated time for history records: tick * 1000 + counter within the tick.
     * A finer clock than ticks keeps "A returned before B was called" exact for the checker.
     */
    public long eventTime() {
        if (eventTick != clock.now()) {
            eventTick = clock.now();
            eventCounter = 0;
        }
        return clock.now() * 1000 + ++eventCounter;
    }

    // ---------------- faults ----------------

    void setDuplicateStorm(boolean on) {
        duplicateStorm = on;
        network.configure(0.04, on ? STORM_DUPLICATE : 0.01, 3);
    }

    public void crashNode(int id) {
        SimNode removed = nodes.remove(id);
        if (removed == null) return;
        disks.get(id).crash(crashRandom);
        network.crash(id);
    }

    public void restartNode(int id) {
        if (nodes.containsKey(id)) return;
        network.restart(id);
        nodes.put(id, new SimNode(id, this, NODE_IDS, disks.get(id)));
    }

    public void healNetwork() { if (nemesis != null) nemesis.heal(network); else network.heal(); }

    public void finishFaults() {
        if (nemesis != null) nemesis.finish(this);
        if (duplicateStorm) setDuplicateStorm(false);
        for (int id : NODE_IDS) restartNode(id);
        network.heal();
    }

    /** Test-only negative control: serve GETs from local state without ReadIndex. */
    public void setUnsafeLocalReads(boolean unsafe) { unsafeLocalReads = unsafe; }
    boolean unsafeLocalReads() { return unsafeLocalReads; }

    // ---------------- inspection ----------------

    public SimNode leader() {
        return nodes.values().stream().filter(node -> node.core().role() == NodeRole.LEADER).findFirst().orElse(null);
    }

    public Integer leaderId() {
        SimNode leader = leader();
        return leader == null ? null : leader.core().id();
    }

    /** Leader among the given nodes (e.g. one side of a partition). */
    public Integer leaderAmong(List<Integer> ids) {
        for (int id : ids) {
            SimNode node = nodes.get(id);
            if (node != null && node.core().role() == NodeRole.LEADER) return id;
        }
        return null;
    }

    public void setHistorySeed(long seed) {
        historySeed = seed;
        historyRecorder = new HistoryRecorder(java.nio.file.Path.of("build", "histories", seed + ".jsonl"));
    }

    public HistoryRecorder history() { return historyRecorder; }
    public long seed() { return historySeed; }
    public SimClock clock() { return clock; }
    public SimNetwork network() { return network; }
    SimNode node(int id) { return nodes.get(id); }
    public String describeNodes() { return nodes.values().stream().map(SimNode::describe).toList().toString(); }

    public List<KvStateMachine> stateMachines() {
        List<KvStateMachine> machines = new ArrayList<>();
        nodes.values().forEach(node -> machines.add(node.stateMachine()));
        return List.copyOf(machines);
    }
}
