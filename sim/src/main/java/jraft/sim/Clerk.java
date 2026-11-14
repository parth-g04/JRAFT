package jraft.sim;

/** Minimal sequential client. Retries a pending request with the same client/sequence identity. */
public final class Clerk {
    private final long clientId;
    private final SimCluster cluster;
    private long nextSequence;

    public Clerk(long clientId, SimCluster cluster) {
        this.clientId = clientId;
        this.cluster = cluster;
    }

    public KvStateMachine.Result execute(KvCommand.Operation operation, String key, String value) {
        long sequence = ++nextSequence;
        long start = cluster.clock().now();
        KvCommand command = new KvCommand(clientId, sequence, operation, key, value);
        cluster.execute(command, clientId, start);
        return cluster.result(clientId, sequence);
    }
}
