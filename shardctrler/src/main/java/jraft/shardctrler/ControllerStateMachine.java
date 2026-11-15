package jraft.shardctrler;

import jraft.raftcore.LogEntry;
import jraft.raftcore.StateMachine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Replicated shard controller state: the list of all configurations. Join, Leave and Move are
 * logged; Query is a read (served with ReadIndex, not logged). Per-client sessions make a
 * retried Join/Leave/Move apply once (a duplicated Join must not create two configurations).
 */
public final class ControllerStateMachine implements StateMachine {
    private static final int SNAPSHOT_MAGIC = 0x53435452; // SCTR

    private final List<ShardConfig> configs = new ArrayList<>(List.of(ShardConfig.INITIAL));
    private final Map<Long, Long> lastSequence = new TreeMap<>();

    @Override
    public void apply(LogEntry entry) {
        if (entry.isConfig() || entry.command().length == 0) return; // membership entry or no-op
        ControllerCommand command = ControllerCommand.decode(entry.command());
        Long last = lastSequence.get(command.clientId());
        if (last != null && command.sequence() <= last) return;    // duplicate
        lastSequence.put(command.clientId(), command.sequence());

        ShardConfig current = latest();
        TreeMap<Integer, List<Integer>> groups = new TreeMap<>(current.groups());
        List<Integer> shards = new ArrayList<>(current.shards());
        switch (command) {
            case ControllerCommand.Join join -> {
                groups.putAll(join.groups());
                shards = Rebalancer.rebalance(shards, groups.keySet());
            }
            case ControllerCommand.Leave leave -> {
                leave.gids().forEach(groups::remove);
                shards = Rebalancer.rebalance(shards, groups.keySet());
            }
            case ControllerCommand.Move move -> {
                if (!groups.containsKey(move.gid()) || move.shard() < 0 || move.shard() >= ShardConfig.NSHARDS) return;
                shards.set(move.shard(), move.gid());
            }
        }
        configs.add(new ShardConfig(current.num() + 1, shards, groups));
    }

    /** True once the command with this (clientId, sequence) has been applied. */
    public boolean applied(long clientId, long sequence) {
        Long last = lastSequence.get(clientId);
        return last != null && sequence <= last;
    }

    /** Configuration {@code num}; -1 or a number beyond the latest returns the latest. */
    public ShardConfig query(int num) {
        if (num < 0 || num >= configs.size()) return latest();
        return configs.get(num);
    }

    public ShardConfig latest() { return configs.getLast(); }

    @Override
    public byte[] snapshot() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(SNAPSHOT_MAGIC);
            out.writeInt(configs.size());
            for (ShardConfig config : configs) config.writeTo(out);
            out.writeInt(lastSequence.size());
            for (Map.Entry<Long, Long> session : lastSequence.entrySet()) {
                out.writeLong(session.getKey());
                out.writeLong(session.getValue());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void restore(byte[] snapshot) {
        configs.clear();
        lastSequence.clear();
        if (snapshot.length == 0) {
            configs.add(ShardConfig.INITIAL);
            return;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot));
            if (in.readInt() != SNAPSHOT_MAGIC) throw new IOException("not a controller snapshot");
            int count = in.readInt();
            for (int i = 0; i < count; i++) configs.add(ShardConfig.readFrom(in));
            int sessions = in.readInt();
            for (int i = 0; i < sessions; i++) lastSequence.put(in.readLong(), in.readLong());
        } catch (IOException malformed) {
            throw new IllegalArgumentException("invalid controller snapshot", malformed);
        }
    }

    public static byte[] encode(ControllerCommand command) { return ControllerCommand.encode(command); }
}
