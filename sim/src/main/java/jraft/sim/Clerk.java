package jraft.sim;

import java.util.Random;

/**
 * Simulated client. Runs a random workload one operation at a time. Every attempt of an operation
 * reuses the same (clientId, sequence), so the servers can deduplicate retries. On WRONG_LEADER it
 * follows the hint; on timeout it retries either the same server or the next one, so the same
 * write can reach several leaders.
 */
public final class Clerk implements SimCluster.Client {
    private static final int RETRY_TIMEOUT_TICKS = 12;
    private static final int KEYS = 3;

    private final long clientId;
    private final SimCluster cluster;
    private final Random random;
    private int operationsLeft;
    private long nextSequence;
    private ClientRequest current;
    private long callTime;
    private long sentAt;
    private int target;
    private boolean resendNow;

    public Clerk(long clientId, SimCluster cluster, long seed, int operations) {
        this.clientId = clientId;
        this.cluster = cluster;
        this.random = new Random(seed);
        this.operationsLeft = operations;
        this.target = 1 + random.nextInt(SimCluster.ALL_NODE_IDS.size());
        cluster.register(clientId, this);
    }

    public boolean done() { return operationsLeft == 0 && current == null; }

    @Override
    public void tick(long now) {
        if (current == null) {
            if (operationsLeft == 0) return;
            operationsLeft--;
            current = nextOperation();
            // Like a client behind a load balancer or with a stale leader cache: sometimes start at a
            // random server, which may be an isolated old leader.
            if (random.nextDouble() < 0.2) target = 1 + random.nextInt(SimCluster.ALL_NODE_IDS.size());
            callTime = cluster.eventTime();
            send(now);
        } else if (resendNow || now - sentAt >= RETRY_TIMEOUT_TICKS) {
            // Timeout: 30% of the time retry the same server, otherwise try the next one.
            if (!resendNow && random.nextDouble() >= 0.3) target = nextNode(target);
            send(now);
        }
    }

    @Override
    public void onReply(ClientReply reply) {
        if (current == null || reply.sequence() != current.sequence()) return; // stale or duplicate reply
        if (reply.status() == ClientReply.Status.WRONG_LEADER) {
            target = reply.leaderHint() != null && reply.leaderHint() != target
                    ? reply.leaderHint() : nextNode(target);
            resendNow = true;
            return;
        }
        String output = reply.status() == ClientReply.Status.OK ? reply.value() : null;
        if (cluster.history() != null) {
            cluster.history().record(clientId, current.toCommand(), output, callTime, cluster.eventTime());
        }
        current = null;
    }

    private void send(long now) {
        resendNow = false;
        sentAt = now;
        cluster.sendToServer(target, current);
    }

    private ClientRequest nextOperation() {
        long sequence = ++nextSequence;
        String key = "k" + random.nextInt(KEYS);
        // Unique values make a duplicated APPEND or a stale read visible to the checker.
        String value = "c" + clientId + "s" + sequence + ";";
        double choice = random.nextDouble();
        if (choice < 0.4) return new ClientRequest(clientId, sequence, KvCommand.Operation.GET, key, null);
        if (choice < 0.7) return new ClientRequest(clientId, sequence, KvCommand.Operation.PUT, key, value);
        return new ClientRequest(clientId, sequence, KvCommand.Operation.APPEND, key, value);
    }

    private static int nextNode(int node) { return node % SimCluster.ALL_NODE_IDS.size() + 1; }
}
