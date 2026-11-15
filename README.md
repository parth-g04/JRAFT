# J-Raft: Distributed Key-Value Store

A distributed key-value store built from scratch in Java on the **Raft** consensus algorithm, currently being upgraded to **J-Raft v2**: a deterministic Raft core, a seed-reproducible fault-injection simulator, a durable write-ahead log, exactly-once clients and linearizable reads checked on every simulated run, snapshots with InstallSnapshot, and (next) membership changes and sharding.

Plan: [PHASES.md](PHASES.md) · Spec: [ARCHITECTURE.md](ARCHITECTURE.md) · Bugs found by simulation: [docs/BUGS.md](docs/BUGS.md) · Results: [docs/RESULTS.md](docs/RESULTS.md) · Decisions: [docs/DECISIONS.md](docs/DECISIONS.md)

---

## Status

| Phase | What | Status |
|------:|------|--------|
| 0 | Audit of the v1 code, Gradle multi-module build, Makefile, CI | Done |
| 1 | `RaftCore` as a pure state machine (`step`/`tick`/`propose` → `Output`s), PreVote, fast log backtracking | Done |
| 2 | Deterministic simulator (`SimNetwork`, `SimClock`, `Nemesis`), history recording, Porcupine checker | Done (200 seeds) |
| 3 | Durable storage: segmented CRC WAL, atomic hard state, crash recovery, group commit, crash/restart chaos | Done (500 seeds) |
| 4 | Exactly-once client sessions + linearizable ReadIndex reads, linearizability checked on every seed | Done (500 seeds) |
| 5 | Snapshots, WAL compaction, chunked InstallSnapshot | Done (500 seeds) |
| 6 | Membership changes (single-server, learners) | Next |
| 7 | Shard controller + sharded KV with live migration | Planned |
| 8 | Real-TCP chaos, metrics dashboard, benchmarks | Planned |
| 9 | Polish | Planned |

## What is implemented today

### Raft core (`raft-core`): no threads, no I/O, no clock
- `RaftCore` is a deterministic state machine. Time is logical **ticks**. Every side effect is returned as an ordered `Output` (`PersistHardState`, `AppendToLog`, `TruncateLog`, `Send`, `ApplyEntries`), so the same code runs under the real driver and under the simulator.
- Leader election with randomized timeouts and **PreVote**, so a partitioned node cannot inflate terms and depose a healthy leader.
- Log replication with **fast backtracking** (`conflictIndex`/`conflictTerm`), a commit rule that only counts current-term entries (Raft Figure 8), and in-order apply.
- A new leader commits a **no-op** from its own term, so it learns its commit index quickly.
- **Snapshots and log compaction:** when the log passes a threshold, the state machine (including client sessions) is snapshotted and the log prefix is dropped. Indexes stay stable through an offset log (`lastIncludedIndex`/`lastIncludedTerm`).
- **InstallSnapshot:** a follower that is too far behind receives the leader's snapshot in chunks, resumable after loss or restart, then continues with normal AppendEntries (bounded batches).
- **ReadIndex** linearizable reads without writing to the log: the leader records the commit index, proves it is still leader by collecting heartbeat acks for a fresh *read round* from a majority, waits until that index is applied, then serves the read. An isolated old leader never confirms a round, so it can never return stale data.

### Exactly-once clients
- Every request carries `(clientId, sequence)`. The replicated state machine keeps a **session table** (`lastSequence`, `lastReply`) per client. A retried write, even one committed twice through two different leaders, is applied once and answered from the cache.
- Followers answer `WRONG_LEADER` with a leader hint; clients follow the hint or retry another node on timeout.

### Durable storage (`raft-storage`)
- **WAL segments**: `[len][crc32][type][index][term][payload]` records, rolled at 64 MB.
- **Recovery**: replay all segments and truncate at the first bad CRC or torn record. Tested by crashing at *every byte offset* of a write.
- **Hard state** (`term`, `votedFor`, `commitIndex`): CRC-protected and written with write-temp → `fsync` → atomic rename.
- **Snapshot files** (`snapshots/snap-<index>-<term>.bin`, CRC-protected, temp + fsync + rename) are made durable *before* WAL segments they cover are deleted. Recovery loads the newest valid snapshot, replays only the WAL after it, and finishes any compaction a crash interrupted. 1M-entry recovery: 1.27 s from the WAL alone, 91 ms with a snapshot.
- **Group commit**: concurrent appends share one `force(true)` per window.
- **Persist before send**: the driver (`RaftNode`) fsyncs before any message leaves or any entry is applied, verified by a spy test.
- Metric: `raft_fsync_seconds` (Micrometer timer `raft.fsync`).

### Deterministic simulation (`sim`)
- One single-threaded scheduler drives a 3-node cluster from a **seed**, so every run is exactly reproducible.
- `SimNetwork`: drop, delay, duplicate, reorder, partition.
- `SimDisk`: loses unsynced writes on crash and may keep a torn prefix of unsynced appends, just like a real disk. Snapshots are atomic; compaction becomes durable only at the next flush, so crashes land between the two.
- Chaos runs with a snapshot every **8** entries and **64-byte** chunks, so nearly every seed compacts and catches a node up by InstallSnapshot.
- `Nemesis`: random partitions (often isolating the current leader), node crash/restart, and message-duplication storms. Three concurrent clients retry against the same and different leaders.
- Every seed's history is checked for **linearizability** in-process (`LinearizabilityChecker`) and written to `build/histories/<seed>.jsonl` for the independent Porcupine-based Go checker in `tools/porcupine-check`.
- **Negative control:** with ReadIndex switched off (`unsafeLocalReads`), the checker flags stale reads, so passing seeds actually mean something.
- A failing seed prints `Reproduce: make chaos-one SEED=<n>`.

---

## Build and test

Requires **JDK 21 or newer**. The Gradle wrapper is included, so no Gradle install is needed. Go 1.22 is needed only for the linearizability checker.

```bash
make test             # unit tests + 50-seed quick chaos     (or: ./gradlew test)
make chaos SEEDS=500  # long simulation                     (or: ./gradlew :sim:chaos -Pseed=1 -Pseeds=500)
make chaos-one SEED=81
make bench            # group-commit window benchmark → CSV
./gradlew :bench:recoveryBenchmark   # recovery time, 1M entries, with/without snapshot
make check-history FILE=build/histories/123.jsonl   # Porcupine: OK / ILLEGAL (+ HTML visualization)
```

On Windows without `make`, use `gradlew.bat` with the same task names.

## Module layout

```
raft-core/       RaftCore, Message, Output, LogEntry   (pure logic, no I/O)
raft-storage/    FileStorage (WAL), HardStateFile, GroupCommitStorage
raft-transport/  RaftNode driver, Transport, StorageOutputHandler
sim/             SimCluster, SimNode (KV server), Clerk, KvStateMachine, SimNetwork, SimDisk,
                 Nemesis, ChaosTest, HistoryRecorder, LinearizabilityChecker
kv/              original v1 server and client (being migrated onto raft-core)
shardctrler/     Phase 7
bench/           storage benchmark (system benchmarks in Phase 8)
tools/porcupine-check/   Go linearizability checker
```

---

## The original v1 server (still available)

The first version is kept in `kv/src/main/java`: a thread-per-client socket server with heartbeats, leader election and a local append-only `data.log`.

```bash
cd kv/src/main/java
javac *.java
java Server 5000    # terminal 1
java Server 5001    # terminal 2
java Server 5002    # terminal 3
java Client         # interact with the cluster
```

Kill the leader (Ctrl+C) and another node is elected within a few seconds. Restart the old node and it rejoins as a follower.

---

**Author:** Parth Gupta
