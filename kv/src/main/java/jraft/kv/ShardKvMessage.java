package jraft.kv;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/** Messages between clients and shard groups, and between shard groups (ARCHITECTURE.md section 3). */
public sealed interface ShardKvMessage {
    /** Client request. (clientId, sequence) identifies the operation across retries. */
    record KvRequest(long clientId, long sequence, ShardKvCommand.Op op, String key, String value) implements ShardKvMessage { }

    record KvReply(long clientId, long sequence, KvResult result) implements ShardKvMessage { }

    /** New owner asks the previous owner for the frozen data of {@code shards} at configuration {@code configNum}. */
    record PullRequest(int fromGid, int configNum, List<Integer> shards) implements ShardKvMessage {
        public PullRequest { shards = List.copyOf(shards); }
    }

    /** {@code shards} is null when the previous owner is not ready (has not applied configNum yet). */
    record PullReply(int fromGid, int configNum, SortedMap<Integer, ShardData> shards) implements ShardKvMessage {
        public PullReply { shards = shards == null ? null : new TreeMap<>(shards); }
    }

    /** New owner tells the previous owner it may delete its copy (garbage collection). */
    record DeleteRequest(int fromGid, int configNum, List<Integer> shards) implements ShardKvMessage {
        public DeleteRequest { shards = List.copyOf(shards); }
    }

    /** {@code done}: the previous owner has committed the deletion (or moved past configNum). */
    record DeleteReply(int fromGid, int configNum, List<Integer> shards, boolean done) implements ShardKvMessage {
        public DeleteReply { shards = List.copyOf(shards); }
    }
}
