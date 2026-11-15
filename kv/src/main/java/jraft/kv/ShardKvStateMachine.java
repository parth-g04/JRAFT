package jraft.kv;

import jraft.raftcore.LogEntry;
import jraft.raftcore.StateMachine;
import jraft.shardctrler.ShardConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * State machine of one shard group (ARCHITECTURE.md section 4).
 *
 * Every shard this group holds has a status:
 * <ul>
 *   <li>SERVING: ours in the current configuration, data present.</li>
 *   <li>PULLING: ours in the current configuration, waiting for the data from the previous owner.</li>
 *   <li>BEPULLING: ours in the previous configuration only; frozen, kept until the new owner has it.</li>
 *   <li>GCING: data received; we serve it, and still have to tell the previous owner to delete its copy.</li>
 * </ul>
 * A new configuration is applied only once every shard is SERVING, so the group moves one
 * configuration at a time. Shards not involved in a migration keep serving throughout.
 */
public final class ShardKvStateMachine implements StateMachine {
    public enum ShardStatus { SERVING, PULLING, BEPULLING, GCING }

    private static final int SNAPSHOT_MAGIC = 0x534B5631; // SKV1

    private static final class Shard {
        ShardStatus status;
        TreeMap<String, String> data = new TreeMap<>();
        TreeMap<Long, ShardData.Session> sessions = new TreeMap<>();

        Shard(ShardStatus status) { this.status = status; }

        ShardData copy() { return new ShardData(data, sessions); }
    }

    private final int gid;
    private ShardConfig current = ShardConfig.INITIAL;
    private ShardConfig previous = ShardConfig.INITIAL;
    private final TreeMap<Integer, Shard> shards = new TreeMap<>();

    public ShardKvStateMachine(int gid) { this.gid = gid; }

    @Override
    public void apply(LogEntry entry) { applyEntry(entry); }

    /**
     * Applies one entry. For a ClientOp the result is the reply for that client; null means the
     * entry produced no client reply (control entries, or a duplicate of an older sequence).
     */
    public KvResult applyEntry(LogEntry entry) {
        if (entry.isConfig() || entry.command().length == 0) return null; // membership entry or no-op
        return switch (ShardKvCommand.decode(entry.command())) {
            case ShardKvCommand.ClientOp op -> applyClientOp(op);
            case ShardKvCommand.Reconfigure reconfigure -> { applyReconfigure(reconfigure.next()); yield null; }
            case ShardKvCommand.InsertShards insert -> { applyInsert(insert); yield null; }
            case ShardKvCommand.DeleteShards delete -> { applyDelete(delete); yield null; }
            case ShardKvCommand.GcDone done -> { applyGcDone(done); yield null; }
        };
    }

    private KvResult applyClientOp(ShardKvCommand.ClientOp op) {
        Shard shard = shards.get(ShardConfig.shardOf(op.key()));
        if (shard == null || !(shard.status == ShardStatus.SERVING || shard.status == ShardStatus.GCING)) {
            return KvResult.WRONG_GROUP;   // not applied, no session recorded: the client retries elsewhere
        }
        ShardData.Session session = shard.sessions.get(op.clientId());
        if (session != null && op.sequence() <= session.lastSequence()) {
            return op.sequence() == session.lastSequence() ? session.lastResult() : null; // duplicate
        }
        KvResult result = switch (op.op()) {
            case GET -> readFrom(shard, op.key());
            case PUT -> { shard.data.put(op.key(), op.value()); yield KvResult.OK_EMPTY; }
            case APPEND -> { shard.data.merge(op.key(), op.value(), String::concat); yield KvResult.OK_EMPTY; }
            case DELETE -> {
                String old = shard.data.remove(op.key());
                yield old == null ? new KvResult(KvResult.Status.NO_KEY, null) : new KvResult(KvResult.Status.OK, old);
            }
        };
        shard.sessions.put(op.clientId(), new ShardData.Session(op.sequence(), result));
        return result;
    }

    private void applyReconfigure(ShardConfig next) {
        if (next.num() != current.num() + 1 || !stable()) return; // one configuration at a time
        for (int s = 0; s < ShardConfig.NSHARDS; s++) {
            boolean ownedNow = current.owner(s) == gid;
            boolean ownedNext = next.owner(s) == gid;
            if (ownedNow && !ownedNext) {
                shards.get(s).status = ShardStatus.BEPULLING;
            } else if (!ownedNow && ownedNext) {
                // A shard that had no owner (first configuration) starts empty; otherwise fetch it.
                shards.put(s, new Shard(current.owner(s) == 0 ? ShardStatus.SERVING : ShardStatus.PULLING));
            }
        }
        previous = current;
        current = next;
    }

    private void applyInsert(ShardKvCommand.InsertShards insert) {
        if (insert.configNum() != current.num()) return;   // stale or duplicate
        insert.shards().forEach((s, data) -> {
            Shard shard = shards.get(s);
            if (shard == null || shard.status != ShardStatus.PULLING) return;
            shard.data = new TreeMap<>(data.data());
            shard.sessions = new TreeMap<>(data.sessions());
            shard.status = ShardStatus.GCING;
        });
    }

    private void applyDelete(ShardKvCommand.DeleteShards delete) {
        if (delete.configNum() != current.num()) return;
        for (int s : delete.shards()) {
            Shard shard = shards.get(s);
            if (shard != null && shard.status == ShardStatus.BEPULLING) shards.remove(s);
        }
    }

    private void applyGcDone(ShardKvCommand.GcDone done) {
        if (done.configNum() != current.num()) return;
        for (int s : done.shards()) {
            Shard shard = shards.get(s);
            if (shard != null && shard.status == ShardStatus.GCING) shard.status = ShardStatus.SERVING;
        }
    }

    // ---------------- reads and queries used by the server ----------------

    /** Linearizable read (call after ReadIndex): the value, or WRONG_GROUP if we do not serve the key. */
    public KvResult read(String key) {
        Shard shard = shards.get(ShardConfig.shardOf(key));
        if (shard == null || !(shard.status == ShardStatus.SERVING || shard.status == ShardStatus.GCING)) {
            return KvResult.WRONG_GROUP;
        }
        return readFrom(shard, key);
    }

    private static KvResult readFrom(Shard shard, String key) {
        String value = shard.data.get(key);
        return value == null ? new KvResult(KvResult.Status.NO_KEY, null) : new KvResult(KvResult.Status.OK, value);
    }

    /** Every shard we hold is SERVING (no migration in progress): we may move to the next configuration. */
    public boolean stable() {
        for (Shard shard : shards.values()) if (shard.status != ShardStatus.SERVING) return false;
        return true;
    }

    /** Shards in {@code status}, grouped by the group that owned them in the previous configuration. */
    public SortedMap<Integer, List<Integer>> shardsByPreviousOwner(ShardStatus status) {
        TreeMap<Integer, List<Integer>> byOwner = new TreeMap<>();
        shards.forEach((s, shard) -> {
            if (shard.status == status) byOwner.computeIfAbsent(previous.owner(s), g -> new ArrayList<>()).add(s);
        });
        return byOwner;
    }

    /**
     * Answers a pull for configuration {@code configNum}: the frozen data of the requested shards,
     * or null if we are not there yet (or no longer have them). Frozen data never changes, so any
     * replica that has applied the configuration gives the same answer.
     */
    public SortedMap<Integer, ShardData> pull(int configNum, List<Integer> requested) {
        if (current.num() != configNum) return null;
        TreeMap<Integer, ShardData> result = new TreeMap<>();
        for (int s : requested) {
            Shard shard = shards.get(s);
            if (shard == null || shard.status != ShardStatus.BEPULLING) return null;
            result.put(s, shard.copy());
        }
        return result;
    }

    /** For a delete request: true when none of the shards is still held as BEPULLING for {@code configNum}. */
    public boolean deleted(int configNum, List<Integer> requested) {
        if (current.num() > configNum) return true;
        if (current.num() < configNum) return false;
        for (int s : requested) {
            Shard shard = shards.get(s);
            if (shard != null && shard.status == ShardStatus.BEPULLING) return false;
        }
        return true;
    }

    public ShardConfig currentConfig() { return current; }
    public ShardConfig previousConfig() { return previous; }
    public int gid() { return gid; }

    public ShardStatus status(int shard) {
        Shard held = shards.get(shard);
        return held == null ? null : held.status;
    }

    /** Copy of the data of a shard we hold (for tests and convergence checks). */
    public SortedMap<String, String> dataOf(int shard) {
        Shard held = shards.get(shard);
        return held == null ? new TreeMap<>() : new TreeMap<>(held.data);
    }

    // ---------------- snapshots ----------------

    @Override
    public byte[] snapshot() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(SNAPSHOT_MAGIC);
            current.writeTo(out);
            previous.writeTo(out);
            out.writeInt(shards.size());
            for (Map.Entry<Integer, Shard> entry : shards.entrySet()) {
                out.writeInt(entry.getKey());
                out.writeByte(entry.getValue().status.ordinal());
                entry.getValue().copy().writeTo(out);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void restore(byte[] snapshot) {
        shards.clear();
        current = ShardConfig.INITIAL;
        previous = ShardConfig.INITIAL;
        if (snapshot.length == 0) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot));
            if (in.readInt() != SNAPSHOT_MAGIC) throw new IOException("not a shard kv snapshot");
            current = ShardConfig.readFrom(in);
            previous = ShardConfig.readFrom(in);
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int s = in.readInt();
                Shard shard = new Shard(ShardStatus.values()[in.readUnsignedByte()]);
                ShardData data = ShardData.readFrom(in);
                shard.data = new TreeMap<>(data.data());
                shard.sessions = new TreeMap<>(data.sessions());
                shards.put(s, shard);
            }
            if (in.available() != 0) throw new IOException("trailing snapshot bytes");
        } catch (IOException malformed) {
            throw new IllegalArgumentException("invalid shard kv snapshot", malformed);
        }
    }

    public static byte[] encode(ShardKvCommand command) { return ShardKvCommand.encode(command); }
}
