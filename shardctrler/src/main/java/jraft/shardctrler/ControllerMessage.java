package jraft.shardctrler;

/** Controller client protocol: Join/Leave/Move are logged, Query is a linearizable read. */
public sealed interface ControllerMessage {
    /** {@code command} null means Query({@code queryNum}); -1 asks for the latest configuration. */
    record Request(long clientId, long sequence, ControllerCommand command, int queryNum) implements ControllerMessage {
        public static Request query(long clientId, long sequence, int num) { return new Request(clientId, sequence, null, num); }
    }

    /** {@code ok} false: this server is not the leader (or lost leadership); try another one. */
    record Reply(long clientId, long sequence, boolean ok, ShardConfig config) implements ControllerMessage { }
}
