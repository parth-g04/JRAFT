package jraft.sim;

import jraft.raftcore.LogEntry;
import jraft.raftcore.StateMachine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic single-group key/value state machine with client sessions (exactly-once writes).
 *
 * Each client has at most one outstanding request and numbers them 1, 2, 3, ... A client may
 * retry a request many times (to the same or a different leader), so the same command can be
 * committed to the log more than once. The session table remembers the last sequence applied per
 * client and its reply: a repeated sequence is not applied again and gets the cached reply.
 *
 * The session table is replicated state (it is rebuilt by replaying the log), so every replica
 * makes the same duplicate decisions. TreeMaps keep iteration deterministic, so equal states
 * also produce byte-identical snapshots. Snapshots include the session table: a client retry
 * that arrives after a follower restored a snapshot must still be recognized as a duplicate.
 */
public final class KvStateMachine implements StateMachine {
    private static final int SNAPSHOT_MAGIC = 0x4B565331; // KVS1

    public record Result(String value, boolean found) { }
    /** Last applied sequence for a client and the reply it produced. */
    public record Session(long lastSequence, Result lastResult) { }

    private final Map<String, String> values = new TreeMap<>();
    private final Map<Long, Session> sessions = new TreeMap<>();

    @Override
    public void apply(LogEntry entry) {
        if (entry.isConfig() || entry.command().length == 0) return; // membership entry or leader no-op
        KvCommand command = KvCommandCodec.decode(entry.command());
        Session session = sessions.get(command.clientId());
        if (session != null && command.sequence() <= session.lastSequence()) return; // duplicate
        Result result = switch (command.operation()) {
            case GET -> read(command.key()); // GETs normally use ReadIndex and are not logged
            case PUT -> {
                values.put(command.key(), command.value());
                yield new Result(null, true);
            }
            case APPEND -> {
                values.merge(command.key(), command.value(), String::concat);
                yield new Result(null, true);
            }
            case DELETE -> {
                boolean found = values.containsKey(command.key());
                yield new Result(values.remove(command.key()), found);
            }
        };
        sessions.put(command.clientId(), new Session(command.sequence(), result));
    }

    /** Cached reply if {@code sequence} is the client's most recently applied request, else null. */
    public Result cachedResult(long clientId, long sequence) {
        Session session = sessions.get(clientId);
        return session != null && session.lastSequence() == sequence ? session.lastResult() : null;
    }

    /** True when this or a later request of the client has already been applied. */
    public boolean alreadyApplied(long clientId, long sequence) {
        Session session = sessions.get(clientId);
        return session != null && sequence <= session.lastSequence();
    }

    @Override
    public byte[] snapshot() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(SNAPSHOT_MAGIC);
            out.writeInt(values.size());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                writeString(out, entry.getKey());
                writeString(out, entry.getValue());
            }
            out.writeInt(sessions.size());
            for (Map.Entry<Long, Session> entry : sessions.entrySet()) {
                Session session = entry.getValue();
                out.writeLong(entry.getKey());
                out.writeLong(session.lastSequence());
                out.writeBoolean(session.lastResult().found());
                out.writeBoolean(session.lastResult().value() != null);
                if (session.lastResult().value() != null) writeString(out, session.lastResult().value());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void restore(byte[] snapshot) {
        values.clear();
        sessions.clear();
        if (snapshot.length == 0) return; // empty snapshot = initial state
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot));
            if (in.readInt() != SNAPSHOT_MAGIC) throw new IOException("not a KV snapshot");
            int valueCount = in.readInt();
            for (int i = 0; i < valueCount; i++) values.put(readString(in), readString(in));
            int sessionCount = in.readInt();
            for (int i = 0; i < sessionCount; i++) {
                long clientId = in.readLong();
                long sequence = in.readLong();
                boolean found = in.readBoolean();
                String value = in.readBoolean() ? readString(in) : null;
                sessions.put(clientId, new Session(sequence, new Result(value, found)));
            }
            if (in.available() != 0) throw new IOException("trailing snapshot bytes");
        } catch (IOException malformed) {
            throw new IllegalArgumentException("invalid KV snapshot", malformed);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) throw new IOException("invalid string length");
        return new String(in.readNBytes(length), java.nio.charset.StandardCharsets.UTF_8);
    }

    public Result read(String key) { return new Result(values.get(key), values.containsKey(key)); }
    public Map<String, String> snapshotView() { return Map.copyOf(values); }
    public Map<Long, Session> sessionsView() { return Map.copyOf(sessions); }
}
