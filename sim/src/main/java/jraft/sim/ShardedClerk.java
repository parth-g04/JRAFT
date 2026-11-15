package jraft.sim;

import jraft.kv.KvResult;
import jraft.kv.ShardKvCommand;
import jraft.kv.ShardKvMessage;
import jraft.shardctrler.ControllerMessage;
import jraft.shardctrler.ShardConfig;

import java.util.Random;

/**
 * Sharded client (Clerk in ARCHITECTURE.md): routes each key to the group that owns its shard
 * in a cached configuration; on WRONG_GROUP (or repeated timeouts) it asks the controller for
 * the latest configuration and retries. Every attempt of an operation keeps the same
 * (clientId, sequence), so the group that finally applies it can deduplicate.
 */
public final class ShardedClerk implements ShardedWorld.Client {
    private static final int RETRY_TICKS = 15;
    private static final int QUERY_RETRY_TICKS = 8;
    private static final int KEYS = 10;

    private final long clientId;
    private final ShardedWorld world;
    private final Random random;
    private int operationsLeft;
    private long nextSequence;
    private ShardConfig config = ShardConfig.INITIAL;
    private boolean needConfig = true;
    private long querySequence;
    private long lastQuery = Long.MIN_VALUE / 2;
    private ShardKvMessage.KvRequest current;
    private long callTime;
    private long sentAt;
    private int timeouts;

    public ShardedClerk(long clientId, ShardedWorld world, long seed, int operations) {
        this.clientId = clientId;
        this.world = world;
        this.random = new Random(seed);
        this.operationsLeft = operations;
        world.register(clientId, this);
    }

    public boolean done() { return operationsLeft == 0 && current == null; }

    @Override
    public void tick(long now) {
        if (needConfig) {
            if (now - lastQuery >= QUERY_RETRY_TICKS) queryController(now);
            return;
        }
        if (current == null) {
            if (operationsLeft == 0) return;
            operationsLeft--;
            current = nextOperation();
            callTime = world.eventTime();
            timeouts = 0;
            send(now);
        } else if (now - sentAt >= RETRY_TICKS) {
            // Every third timeout, suspect a stale configuration.
            if (++timeouts % 3 == 0) needConfig = true;
            else send(now);
        }
    }

    @Override
    public void onMessage(int from, Object message) {
        if (message instanceof ControllerMessage.Reply reply) {
            if (!reply.ok() || reply.clientId() != clientId || reply.sequence() != querySequence || !needConfig) return;
            if (reply.config().num() >= config.num()) config = reply.config();
            needConfig = false;
            if (current != null) send(world.clock().now());
        } else if (message instanceof ShardKvMessage.KvReply reply) {
            if (current == null || reply.sequence() != current.sequence()) return;   // stale or duplicate
            switch (reply.result().status()) {
                case WRONG_LEADER -> { }                                            // another server may answer
                case WRONG_GROUP -> needConfig = true;
                case OK, NO_KEY -> {
                    String output = reply.result().status() == KvResult.Status.OK ? reply.result().value() : null;
                    if (current.op() != ShardKvCommand.Op.GET) output = null;
                    if (world.history() != null) {
                        world.history().record(clientId, new KvCommand(clientId, current.sequence(),
                                KvCommand.Operation.valueOf(current.op().name()), current.key(), current.value()),
                                output, callTime, world.eventTime());
                    }
                    current = null;
                }
            }
        }
    }

    private void queryController(long now) {
        lastQuery = now;
        long sequence = ++querySequence;
        for (int controller : ShardedWorld.CONTROLLERS) {
            world.sendApp((int) clientId, controller, ControllerMessage.Request.query(clientId, sequence, -1));
        }
    }

    /** Sends the current request to every server of the owning group; only the leader answers OK. */
    private void send(long now) {
        sentAt = now;
        int gid = config.owner(ShardConfig.shardOf(current.key()));
        if (gid == 0) {
            needConfig = true;
            return;
        }
        for (int server : config.serversOf(gid)) world.sendApp((int) clientId, server, current);
    }

    private ShardKvMessage.KvRequest nextOperation() {
        long sequence = ++nextSequence;
        String key = "k" + random.nextInt(KEYS);
        String value = "c" + clientId + "s" + sequence + ";";
        double choice = random.nextDouble();
        if (choice < 0.4) return new ShardKvMessage.KvRequest(clientId, sequence, ShardKvCommand.Op.GET, key, null);
        if (choice < 0.7) return new ShardKvMessage.KvRequest(clientId, sequence, ShardKvCommand.Op.PUT, key, value);
        return new ShardKvMessage.KvRequest(clientId, sequence, ShardKvCommand.Op.APPEND, key, value);
    }
}
