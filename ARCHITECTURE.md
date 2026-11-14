# J-Raft v2 — Architecture Specification

> The agent must first **read the existing J-Raft code** and map it onto this architecture (Phase 0). Keep what works; refactor incrementally; never rewrite everything at once.

## 1. Target architecture

```
 Clients (Clerk library: retries, clientId/seq, config cache)
     │  GET/PUT/APPEND/DELETE(key)
     ▼
 ┌───────────────────────────── Shard Controller (Raft group "ctrl", 3 nodes) ─────────┐
 │ state: list of Configs { num, shards[NShards] -> groupId, groups: groupId -> [servers]} │
 │ ops: Join(groupId, servers), Leave(groupId), Move(shard, groupId), Query(num)          │
 └────────────────────────────────────────────────────────────────────────────────────────┘
     ▲ polls config every 100 ms
 ┌───┴──────────────────┐   ┌──────────────────────┐   ┌──────────────────────┐
 │ ShardKV group 100    │   │ ShardKV group 101    │   │ ShardKV group 102    │
 │ 3 × RaftNode + KV SM │   │ 3 × RaftNode + KV SM │   │ ...                  │
 │ owns shards {0..3}   │◄─►│ owns shards {4..7}   │   │                      │
 └──────────────────────┘   └──────────────────────┘   └──────────────────────┘
          shard migration (pull-based: new owner fetches shard data + dedupe table)

 Each RaftNode:
   RaftCore (pure state machine: handles messages & ticks, emits outputs)
     ├── Storage: WAL segments + HardState file + Snapshot files
     ├── Transport (interface): TcpTransport | SimTransport (fault-injecting)
     └── StateMachine interface: apply(entry) -> result; snapshot(); restore(bytes)
```

### Key design choice: RaftCore as a deterministic state machine
```java
public final class RaftCore {
  // No threads, no sockets, no clocks inside. Easy to test deterministically.
  public List<Output> step(Message msg);    // RPC requests/responses
  public List<Output> tick();               // logical time: election/heartbeat timers
  public List<Output> propose(byte[] cmd);  // client command (leader only)
}
sealed interface Output permits Send, PersistHardState, AppendToLog, TruncateLog,
     ApplyEntries, SaveSnapshot, ApplySnapshot, ReadReady {}
```
A `RaftNode` driver thread executes outputs in the correct order (**persist before send**). This mirrors etcd's raft library design and makes simulation testing possible.

## 2. Storage format
```
data/<nodeId>/
  hardstate.bin          [term:8][votedFor:8][commitIndex:8][crc:4]  written via write-temp + fsync + atomic rename
  wal/00000000000000000001.seg   records: [len:4][crc32:4][type:1][index:8][term:8][payload]
  wal/...                segment roll at 64 MB
  snapshots/snap-<lastIndex>-<lastTerm>.bin   [header][state machine bytes][crc]
```
- **Group commit**: appends buffered; one `force(true)` per batch (≤ 2 ms window or 256 entries).
- **Recovery**: load latest valid snapshot → replay WAL entries after snapshot index → truncate at first CRC failure.
- **Compaction**: after snapshot at index i, delete segments whose last index ≤ i.

## 3. RPCs (binary framing over TCP; length-prefixed; versioned)
- `RequestVote(term, candidateId, lastLogIndex, lastLogTerm, preVote:boolean)` (add **PreVote** to avoid disruptive elections from partitioned nodes)
- `AppendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries[], leaderCommit)` + reply with `conflictIndex/conflictTerm` for fast backtracking
- `InstallSnapshot(term, leaderId, lastIncludedIndex, lastIncludedTerm, offset, data chunk, done)`
- `ReadIndexRequest / Heartbeat-ack` correlation via `readId`
- Client: `ClientRequest{clientId, seq, op, key, value, configNum}` → `ClientReply{status: OK|NO_KEY|WRONG_LEADER(hint)|WRONG_GROUP|TIMEOUT, value}`

## 4. KV state machine
```java
Map<Integer shardId, ShardState> shards;   // ShardState { Map<String,String> data; Status: SERVING|PULLING|BEPULLING|GCING }
Map<Long clientId, DedupEntry{lastSeq, lastReply}> dedup;  // per shard, migrates with shard
int configNum;
```
- Ops: GET (via ReadIndex, not logged), PUT, APPEND, DELETE (logged).
- Config changes are themselves log entries (`ConfigEntry`) so all replicas switch at the same log position.
- Shard migration entries: `InsertShard(shardId, configNum, data, dedup)`, `DeleteShard(shardId, configNum)`.

## 5. Shard controller
- Config rebalance on Join/Leave: deterministic algorithm moving the minimum number of shards so that group shard counts differ by at most 1; iterate groups in sorted order (deterministic across replicas — **never iterate a HashMap for decisions**).
- NShards = 16 (constant).

## 6. Fault-injection simulation (`sim` package)
- `SimNetwork`: in-JVM message delivery with seeded `Random`: drop probability, delay range, duplication probability, reorder, partitions (sets of node ids), node crash (discard in-memory state, keep `SimDisk`) and restart.
- `SimDisk`: in-memory files that model fsync: on crash, unsynced writes are lost (or torn randomly).
- `SimClock`: logical ticks; whole cluster driven by a single-threaded scheduler loop → fully reproducible from a seed.
- Test runner: `ChaosTest(seed)` runs N clients doing random ops on a small keyspace (10 keys) for T simulated seconds while a nemesis schedules faults; records history.

## 7. History & linearizability checking
- History record JSON lines: `{"clientId":1,"op":"put","key":"k3","value":"x","output":null,"call":123456,"return":123789}` (simulated nanos).
- `tools/porcupine-check/` Go program: reads history, uses `github.com/anishathalye/porcupine` KV model (partitioned by key), outputs `OK`/`ILLEGAL` and writes the HTML visualization.
- JUnit invokes the checker via ProcessBuilder (skip with a clear message if Go not installed; CI installs Go).
- Also run a **real TCP** chaos test with a `ProxyTransport` (iptables-free): a TCP proxy between nodes that can drop/partition.

## 8. Metrics & benchmarks
Metrics: `raft_term`, `raft_is_leader`, `raft_commit_index`, `raft_apply_lag`, `raft_fsync_seconds` histogram, `raft_elections_total`, `kv_ops_total{op,status}`, `kv_op_latency_seconds`, `shard_migrations_total`.
Benchmark tool `bench/`: N clients, mix 50/50 or 90/10 read/write, value 100 B, duration; outputs ops/s and p50/p99 to CSV; scripts to plot vs shards and vs group-commit window.

## 9. Package layout (target)
```
jraft/
├── raft-core/        (RaftCore, messages, Output, Log abstraction — no I/O)
├── raft-storage/     (WAL, HardStateFile, SnapshotStore)
├── raft-transport/   (TcpTransport, codec, ProxyTransport)
├── kv/               (KvStateMachine, ShardKvServer, Clerk)
├── shardctrler/      (Controller state machine, client)
├── sim/              (SimNetwork, SimDisk, SimClock, Nemesis, ChaosTest, HistoryRecorder)
├── bench/
├── tools/porcupine-check/ (Go)
└── docs/ (ARCHITECTURE.md, PHASES.md, DECISIONS.md, BUGS.md, RESULTS.md)
```
