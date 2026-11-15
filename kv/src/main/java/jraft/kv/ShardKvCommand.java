package jraft.kv;

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
 * Commands in a shard group's Raft log (ARCHITECTURE.md section 4). Configuration changes and
 * shard migration steps are log entries too, so every replica of the group switches at the same
 * log position.
 */
public sealed interface ShardKvCommand {
    enum Op { GET, PUT, APPEND, DELETE }

    /** A client write (GETs normally use ReadIndex instead of the log). */
    record ClientOp(long clientId, long sequence, Op op, String key, String value) implements ShardKvCommand { }

    /** Move from configuration num-1 to {@code next} (applied only if it is exactly the next one). */
    record Reconfigure(ShardConfig next) implements ShardKvCommand { }

    /** Data pulled from the previous owner for configuration {@code configNum}. */
    record InsertShards(int configNum, SortedMap<Integer, ShardData> shards) implements ShardKvCommand {
        public InsertShards { shards = new TreeMap<>(shards); }
    }

    /** Old owner: the new owner has the data, drop the frozen copy (garbage collection). */
    record DeleteShards(int configNum, List<Integer> shards) implements ShardKvCommand {
        public DeleteShards { shards = List.copyOf(shards); }
    }

    /** New owner: the old owner deleted its copy; the shard is fully ours again. */
    record GcDone(int configNum, List<Integer> shards) implements ShardKvCommand {
        public GcDone { shards = List.copyOf(shards); }
    }

    static byte[] encode(ShardKvCommand command) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            switch (command) {
                case ClientOp op -> {
                    out.writeByte(1);
                    out.writeLong(op.clientId());
                    out.writeLong(op.sequence());
                    out.writeByte(op.op().ordinal());
                    ShardData.writeString(out, op.key());
                    out.writeBoolean(op.value() != null);
                    if (op.value() != null) ShardData.writeString(out, op.value());
                }
                case Reconfigure reconfigure -> {
                    out.writeByte(2);
                    reconfigure.next().writeTo(out);
                }
                case InsertShards insert -> {
                    out.writeByte(3);
                    out.writeInt(insert.configNum());
                    out.writeInt(insert.shards().size());
                    for (Map.Entry<Integer, ShardData> shard : insert.shards().entrySet()) {
                        out.writeInt(shard.getKey());
                        shard.getValue().writeTo(out);
                    }
                }
                case DeleteShards delete -> {
                    out.writeByte(4);
                    writeShardList(out, delete.configNum(), delete.shards());
                }
                case GcDone done -> {
                    out.writeByte(5);
                    writeShardList(out, done.configNum(), done.shards());
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static ShardKvCommand decode(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int type = in.readUnsignedByte();
            ShardKvCommand command = switch (type) {
                case 1 -> {
                    long clientId = in.readLong();
                    long sequence = in.readLong();
                    Op op = Op.values()[in.readUnsignedByte()];
                    String key = ShardData.readString(in);
                    String value = in.readBoolean() ? ShardData.readString(in) : null;
                    yield new ClientOp(clientId, sequence, op, key, value);
                }
                case 2 -> new Reconfigure(ShardConfig.readFrom(in));
                case 3 -> {
                    int configNum = in.readInt();
                    TreeMap<Integer, ShardData> shards = new TreeMap<>();
                    int count = in.readInt();
                    for (int i = 0; i < count; i++) shards.put(in.readInt(), ShardData.readFrom(in));
                    yield new InsertShards(configNum, shards);
                }
                case 4 -> {
                    int configNum = in.readInt();
                    yield new DeleteShards(configNum, readShardList(in));
                }
                case 5 -> {
                    int configNum = in.readInt();
                    yield new GcDone(configNum, readShardList(in));
                }
                default -> throw new IOException("unknown shard kv command " + type);
            };
            if (in.available() != 0) throw new IOException("trailing bytes");
            return command;
        } catch (IOException malformed) {
            throw new IllegalArgumentException("invalid shard kv command", malformed);
        }
    }

    private static void writeShardList(DataOutputStream out, int configNum, List<Integer> shards) throws IOException {
        out.writeInt(configNum);
        out.writeInt(shards.size());
        for (int shard : shards) out.writeInt(shard);
    }

    private static List<Integer> readShardList(DataInputStream in) throws IOException {
        List<Integer> shards = new ArrayList<>();
        int count = in.readInt();
        for (int i = 0; i < count; i++) shards.add(in.readInt());
        return shards;
    }
}
