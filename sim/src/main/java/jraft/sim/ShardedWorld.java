package jraft.sim;

import jraft.raftcore.Configuration;
import jraft.raftcore.NodeRole;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;

/**
 * Deterministic simulation of the sharded system (ARCHITECTURE.md section 1): a shard controller
 * group and several shard groups, each a 3-node Raft group, plus clients, on one simulated network.
 * One thread, one clock, every random choice from the seed.
 *
 * Node ids: controller 1-3; shard group g (100, 101, 102) uses nodes (g-99)*10 + 1..3, i.e. 11-13,
 * 21-23, 31-33. Client ids are at least 1000.
 */
public final class ShardedWorld {
    /** A client endpoint (clerk or admin) that receives app messages and acts on ticks. */
    public interface Client {
        void onMessage(int from, Object message);
        default void tick(long now) { }
    }

    private record Envelope(long at, long order, int from, int to, Object message) { }

    public static final List<Integer> CONTROLLERS = List.of(1, 2, 3);
    public static final List<Integer> GROUP_IDS = List.of(100, 101, 102);
    private static final double APP_DROP = 0.03;
    private static final double APP_DUPLICATE = 0.03;
    private static final double STORM_DUPLICATE = 0.3;
    private static final int APP_MAX_DELAY = 2;

    private final SimClock clock = new SimClock();
    private final SimNetwork network;
    private final Random appRandom;
    private final Random crashRandom;
    private final boolean faults;
    private final int snapshotThreshold;
    private final int snapshotChunkBytes;
    private final Map<Integer, GroupNode> nodes = new TreeMap<>();
    private final Map<Integer, SimDisk> disks = new TreeMap<>();
    private final Map<Long, Client> clients = new TreeMap<>();
    private final PriorityQueue<Envelope> appQueue = new PriorityQueue<>(
            Comparator.comparingLong(Envelope::at).thenComparingLong(Envelope::order));
    private long appOrder;
    private boolean duplicateStorm;
    private long eventTick = -1;
    private long eventCounter;
    private long snapshotsInstalled;
    private HistoryRecorder history;

    public ShardedWorld(long seed, boolean faults, int snapshotThreshold, int snapshotChunkBytes) {
        this.faults = faults;
        this.snapshotThreshold = snapshotThreshold;
        this.snapshotChunkBytes = snapshotChunkBytes;
        this.network = faults ? new SimNetwork(seed, 0.03, 0.01, 3) : new SimNetwork(seed, 0, 0, 1);
        this.appRandom = new Random(seed * 13 + 5);
        this.crashRandom = new Random(seed * 31 + 7);
        for (int id : allNodeIds()) {
            disks.put(id, new SimDisk());
            nodes.put(id, newNode(id));
        }
    }

    public static List<Integer> serversOf(int gid) {
        int base = (gid - 99) * 10;
        return List.of(base + 1, base + 2, base + 3);
    }

    public static List<Integer> allNodeIds() {
        List<Integer> ids = new ArrayList<>(CONTROLLERS);
        for (int gid : GROUP_IDS) ids.addAll(serversOf(gid));
        return ids;
    }

    /** Group id of a shard-group node, or 0 for a controller node. */
    public static int groupOf(int nodeId) { return nodeId < 10 ? 0 : nodeId / 10 + 99; }

    private GroupNode newNode(int id) {
        int gid = groupOf(id);
        List<Integer> members = gid == 0 ? CONTROLLERS : serversOf(gid);
        GroupNode.Service service = gid == 0 ? new ControllerService() : new ShardKvService(this, gid, id, CONTROLLERS);
        return new GroupNode(id, this, Configuration.ofVoters(members), disks.get(id), service,
                snapshotThreshold, snapshotChunkBytes);
    }

    // ---------------- scheduler ----------------

    public void tick() {
        long now = clock.advance();
        for (GroupNode node : List.copyOf(nodes.values())) node.tick();
        for (SimNetwork.Delivery delivery : network.drainDue(now)) {
            GroupNode target = nodes.get(delivery.to());
            if (target != null) target.receive(delivery.from(), delivery.message());
        }
        while (!appQueue.isEmpty() && appQueue.peek().at() <= now) {
            Envelope envelope = appQueue.remove();
            if (envelope.to() >= 1_000) {
                Client client = clients.get((long) envelope.to());
                if (client != null) client.onMessage(envelope.from(), envelope.message());
            } else {
                GroupNode target = nodes.get(envelope.to());
                // Server-to-server messages obey partitions; client messages do not.
                boolean serverToServer = envelope.from() < 1_000;
                if (target != null && (!serverToServer || network.connected(envelope.from(), envelope.to()))) {
                    target.onAppMessage(envelope.from(), envelope.message());
                }
            }
        }
        for (Client client : List.copyOf(clients.values())) client.tick(now);
    }

    public void runTicks(int ticks) { for (int i = 0; i < ticks; i++) tick(); }

    /** Sends an application message (client request/reply, migration RPC) with seeded faults. */
    public void sendApp(int from, int to, Object message) {
        if (from < 1_000 && to < 1_000 && !network.connected(from, to)) return;
        if (faults && appRandom.nextDouble() < APP_DROP) return;
        int copies = faults && appRandom.nextDouble() < (duplicateStorm ? STORM_DUPLICATE : APP_DUPLICATE) ? 2 : 1;
        for (int copy = 0; copy < copies; copy++) {
            int delay = faults ? appRandom.nextInt(APP_MAX_DELAY + 1) : 0;
            appQueue.add(new Envelope(clock.now() + delay + 1, appOrder++, from, to, message));
        }
    }

    public void register(long clientId, Client client) { clients.put(clientId, client); }

    /** Strictly increasing event time for histories: tick * 1000 + counter within the tick. */
    public long eventTime() {
        if (eventTick != clock.now()) {
            eventTick = clock.now();
            eventCounter = 0;
        }
        return clock.now() * 1000 + ++eventCounter;
    }

    // ---------------- faults ----------------

    public void crashNode(int id) {
        if (nodes.remove(id) == null) return;
        disks.get(id).crash(crashRandom);
        network.crash(id);
    }

    public void restartNode(int id) {
        if (nodes.containsKey(id)) return;
        network.restart(id);
        nodes.put(id, newNode(id));
    }

    void setDuplicateStorm(boolean on) {
        duplicateStorm = on;
        network.configure(0.03, on ? STORM_DUPLICATE : 0.01, 3);
    }

    public void healAll() {
        for (int id : allNodeIds()) restartNode(id);
        network.heal();
        if (duplicateStorm) setDuplicateStorm(false);
    }

    private boolean unsafeShardReads;

    /** Test-only negative control: groups answer GETs from any local copy, without ownership checks or ReadIndex. */
    public void setUnsafeShardReads(boolean unsafe) { unsafeShardReads = unsafe; }
    boolean unsafeShardReads() { return unsafeShardReads; }

    void countSnapshotInstalled() { snapshotsInstalled++; }
    public long snapshotsInstalled() { return snapshotsInstalled; }

    // ---------------- inspection ----------------

    public Integer leaderOf(List<Integer> members) {
        for (int id : members) {
            GroupNode node = nodes.get(id);
            if (node != null && node.core().role() == NodeRole.LEADER) return id;
        }
        return null;
    }

    GroupNode node(int id) { return nodes.get(id); }

    public ShardKvService shardService(int nodeId) {
        GroupNode node = nodes.get(nodeId);
        return node == null ? null : (ShardKvService) node.service();
    }

    public ControllerService controllerService(int nodeId) {
        GroupNode node = nodes.get(nodeId);
        return node == null ? null : (ControllerService) node.service();
    }

    public void setHistory(Path path) { history = new HistoryRecorder(path); }
    public HistoryRecorder history() { return history; }
    public SimClock clock() { return clock; }
    public SimNetwork network() { return network; }

    public String describe() {
        return nodes.values().stream().map(GroupNode::describe).toList().toString();
    }
}
