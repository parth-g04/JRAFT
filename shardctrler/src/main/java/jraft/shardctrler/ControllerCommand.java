package jraft.shardctrler;

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

/** Logged controller operations. (clientId, sequence) makes retries exactly-once. */
public sealed interface ControllerCommand {
    long clientId();
    long sequence();

    /** New groups (gid -> servers) join; shards are rebalanced. */
    record Join(long clientId, long sequence, SortedMap<Integer, List<Integer>> groups) implements ControllerCommand {
        public Join {
            TreeMap<Integer, List<Integer>> copy = new TreeMap<>();
            groups.forEach((gid, servers) -> copy.put(gid, List.copyOf(servers)));
            groups = copy;
        }
    }

    /** Groups leave; their shards are rebalanced onto the remaining groups. */
    record Leave(long clientId, long sequence, List<Integer> gids) implements ControllerCommand {
        public Leave { gids = List.copyOf(gids); }
    }

    /** Assign one shard to one group explicitly (no rebalancing). */
    record Move(long clientId, long sequence, int shard, int gid) implements ControllerCommand { }

    static byte[] encode(ControllerCommand command) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeLong(command.clientId());
            out.writeLong(command.sequence());
            switch (command) {
                case Join join -> {
                    out.writeByte(1);
                    out.writeInt(join.groups().size());
                    for (Map.Entry<Integer, List<Integer>> group : join.groups().entrySet()) {
                        out.writeInt(group.getKey());
                        out.writeInt(group.getValue().size());
                        for (int server : group.getValue()) out.writeInt(server);
                    }
                }
                case Leave leave -> {
                    out.writeByte(2);
                    out.writeInt(leave.gids().size());
                    for (int gid : leave.gids()) out.writeInt(gid);
                }
                case Move move -> {
                    out.writeByte(3);
                    out.writeInt(move.shard());
                    out.writeInt(move.gid());
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static ControllerCommand decode(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            long clientId = in.readLong();
            long sequence = in.readLong();
            int type = in.readUnsignedByte();
            ControllerCommand command = switch (type) {
                case 1 -> {
                    TreeMap<Integer, List<Integer>> groups = new TreeMap<>();
                    int count = in.readInt();
                    for (int g = 0; g < count; g++) {
                        int gid = in.readInt();
                        List<Integer> servers = new ArrayList<>();
                        int serverCount = in.readInt();
                        for (int s = 0; s < serverCount; s++) servers.add(in.readInt());
                        groups.put(gid, servers);
                    }
                    yield new Join(clientId, sequence, groups);
                }
                case 2 -> {
                    List<Integer> gids = new ArrayList<>();
                    int count = in.readInt();
                    for (int i = 0; i < count; i++) gids.add(in.readInt());
                    yield new Leave(clientId, sequence, gids);
                }
                case 3 -> new Move(clientId, sequence, in.readInt(), in.readInt());
                default -> throw new IOException("unknown controller command " + type);
            };
            if (in.available() != 0) throw new IOException("trailing bytes");
            return command;
        } catch (IOException malformed) {
            throw new IllegalArgumentException("invalid controller command", malformed);
        }
    }
}
