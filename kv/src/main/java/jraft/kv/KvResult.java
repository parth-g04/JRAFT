package jraft.kv;

/** Outcome of a client operation on a shard group (ARCHITECTURE.md section 3). */
public record KvResult(Status status, String value) {
    public enum Status { OK, NO_KEY, WRONG_GROUP, WRONG_LEADER }

    public static final KvResult WRONG_GROUP = new KvResult(Status.WRONG_GROUP, null);
    public static final KvResult WRONG_LEADER = new KvResult(Status.WRONG_LEADER, null);
    public static final KvResult OK_EMPTY = new KvResult(Status.OK, null);
}
