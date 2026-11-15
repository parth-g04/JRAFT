package jraft.kv;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The contents of one shard: its key/value pairs and its client sessions. Sessions travel with
 * the shard, so a retry that reaches the new owner is still recognized as a duplicate.
 */
public record ShardData(SortedMap<String, String> data, SortedMap<Long, Session> sessions) {
    /** Last applied sequence of a client on this shard and the reply it got. */
    public record Session(long lastSequence, KvResult lastResult) { }

    public static final ShardData EMPTY = new ShardData(new TreeMap<>(), new TreeMap<>());

    public ShardData {
        data = Collections.unmodifiableSortedMap(new TreeMap<>(data));
        sessions = Collections.unmodifiableSortedMap(new TreeMap<>(sessions));
    }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(data.size());
        for (Map.Entry<String, String> entry : data.entrySet()) {
            writeString(out, entry.getKey());
            writeString(out, entry.getValue());
        }
        out.writeInt(sessions.size());
        for (Map.Entry<Long, Session> entry : sessions.entrySet()) {
            out.writeLong(entry.getKey());
            out.writeLong(entry.getValue().lastSequence());
            KvResult result = entry.getValue().lastResult();
            out.writeByte(result.status().ordinal());
            out.writeBoolean(result.value() != null);
            if (result.value() != null) writeString(out, result.value());
        }
    }

    public static ShardData readFrom(DataInputStream in) throws IOException {
        TreeMap<String, String> data = new TreeMap<>();
        int keys = in.readInt();
        for (int i = 0; i < keys; i++) data.put(readString(in), readString(in));
        TreeMap<Long, Session> sessions = new TreeMap<>();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            long clientId = in.readLong();
            long sequence = in.readLong();
            KvResult.Status status = KvResult.Status.values()[in.readUnsignedByte()];
            String value = in.readBoolean() ? readString(in) : null;
            sessions.put(clientId, new Session(sequence, new KvResult(status, value)));
        }
        return new ShardData(data, sessions);
    }

    static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) throw new IOException("invalid string length");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}
