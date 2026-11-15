package jraft.shardctrler;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One numbered configuration of the sharded store (ARCHITECTURE.md section 5).
 * {@code shards.get(s)} is the group that owns shard s (0 = unassigned); {@code groups} maps each
 * replica group id to its servers. Configuration 0 is the empty initial configuration.
 */
public record ShardConfig(int num, List<Integer> shards, SortedMap<Integer, List<Integer>> groups) {
    public static final int NSHARDS = 16;
    public static final ShardConfig INITIAL = new ShardConfig(0, Collections.nCopies(NSHARDS, 0), new TreeMap<>());

    public ShardConfig {
        if (shards.size() != NSHARDS) throw new IllegalArgumentException("expected " + NSHARDS + " shards");
        shards = List.copyOf(shards);
        TreeMap<Integer, List<Integer>> copy = new TreeMap<>();
        groups.forEach((gid, servers) -> copy.put(gid, List.copyOf(servers)));
        groups = Collections.unmodifiableSortedMap(copy);
    }

    /** Deterministic key to shard mapping (String.hashCode is specified by the JLS). */
    public static int shardOf(String key) { return Math.floorMod(key.hashCode(), NSHARDS); }

    public int owner(int shard) { return shards.get(shard); }

    /** Shards owned by {@code gid}, ascending. */
    public List<Integer> shardsOf(int gid) {
        List<Integer> owned = new ArrayList<>();
        for (int shard = 0; shard < NSHARDS; shard++) if (shards.get(shard) == gid) owned.add(shard);
        return owned;
    }

    public List<Integer> serversOf(int gid) { return groups.getOrDefault(gid, List.of()); }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(num);
        for (int gid : shards) out.writeInt(gid);
        out.writeInt(groups.size());
        for (Map.Entry<Integer, List<Integer>> group : groups.entrySet()) {
            out.writeInt(group.getKey());
            out.writeInt(group.getValue().size());
            for (int server : group.getValue()) out.writeInt(server);
        }
    }

    public static ShardConfig readFrom(DataInputStream in) throws IOException {
        int num = in.readInt();
        List<Integer> shards = new ArrayList<>();
        for (int i = 0; i < NSHARDS; i++) shards.add(in.readInt());
        TreeMap<Integer, List<Integer>> groups = new TreeMap<>();
        int groupCount = in.readInt();
        for (int g = 0; g < groupCount; g++) {
            int gid = in.readInt();
            int serverCount = in.readInt();
            List<Integer> servers = new ArrayList<>();
            for (int s = 0; s < serverCount; s++) servers.add(in.readInt());
            groups.put(gid, servers);
        }
        return new ShardConfig(num, shards, groups);
    }

    @Override public String toString() { return "Config#" + num + " shards=" + shards + " groups=" + groups.keySet(); }
}
