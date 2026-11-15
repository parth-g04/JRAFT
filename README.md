# J-Raft: Distributed Key-Value Store

A distributed key-value store built from scratch in Java on the **Raft** consensus algorithm, currently being upgraded to **J-Raft v2**: a deterministic Raft core, a seed-reproducible fault-injection simulator, a durable write-ahead log, and (next) exactly-once clients, snapshots, membership changes and sharding.

Plan: [PHASES.md](PHASES.md) · Spec: [ARCHITECTURE.md](ARCHITECTURE.md) · Bugs found by simulation: [docs/BUGS.md](docs/BUGS.md) · Results: [docs/RESULTS.md](docs/RESULTS.md) · Decisions: [docs/DECISIONS.md](docs/DECISIONS.md)

---

## Status

| Phase | What | Status |
|------:|------|--------|
| 0 | Audit of the v1 code, Gradle multi-module build, Makefile, CI | Done |
| 1 | `RaftCore` as a pure state machine (`step`/`tick`/`propose` → `Output`s), PreVote, fast log backtracking | Done |
| 2 | Deterministic simulator (`SimNetwork`, `SimClock`, `Nemesis`), history recording, Porcupine checker | Done (200 seeds) |
| 3 | Durable storage: segmented CRC WAL, atomic hard state, crash recovery, group commit, crash/restart chaos | Done (500 seeds) |
| 4 | Exactly-once client sessions + linearizable ReadIndex reads | Next |
| 5 | Snapshots & InstallSnapshot | Planned |
| 6 | Membership changes (single-server, learners) | Planned |
| 7 | Shard controller + sharded KV with live migration | Planned |
| 8 | Real-TCP chaos, metrics dashboard, benchmarks | Planned |
| 9 | Polish | Planned |

## What is implemented today

### Raft core (`raft-core`): no threads, no I/O, no clock
- `RaftCore` is a deterministic state machine. Time is logical **ticks**. Every side effect is returned as an ordered `Output` (`PersistHardState`, `AppendToLog`, `TruncateLog`, `Send`, `ApplyEntries`), so the same code runs under the real driver and under the simulator.
- Leader election with randomized timeouts and **PreVote**, so a partitioned node cannot inflate terms and depose a healthy leader.
- Log replication with **fast backtracking** (`conflictIndex`/`conflictTerm`), a commit rule that only counts current-term entries (Raft Figure 8), and in-order apply.

### Durable storage (`raft-storage`)
- **WAL segments**: `[len][crc32][type][index][term][payload]` records, rolled at 64 MB.
- **Recovery**: replay all segments and truncate at the first bad CRC or torn record. Tested by crashing at *every byte offset* of a write.
- **Hard state** (`term`, `votedFor`, `commitIndex`): CRC-protected and written with write-temp → `fsync` → atomic rename.
- **Group commit**: concurrent appends share one `force(true)` per window.
- **Persist before send**: the driver (`RaftNode`) fsyncs before any message leaves or any entry is applied, verified by a spy test.
- Metric: `raft_fsync_seconds` (Micrometer timer `raft.fsync`).

### Deterministic simulation (`sim`)
- One single-threaded scheduler drives a 3-node cluster from a **seed**, so every run is exactly reproducible.
- `SimNetwork`: drop, delay, duplicate, reorder, partition.
- `SimDisk`: loses unsynced writes on crash and may keep a torn prefix of unsynced appends, just like a real disk.
- `Nemesis`: random partitions and node crash/restart.
- Every operation is recorded to `build/histories/<seed>.jsonl` and can be checked for **linearizability** with the Porcupine-based Go checker in `tools/porcupine-check`.
- A failing seed prints `Reproduce: make chaos-one SEED=<n>`.

---

## Build and test

Requires **JDK 21 or newer**. The Gradle wrapper is included, so no Gradle install is needed. Go 1.22 is needed only for the linearizability checker.

```bash
make test             # unit tests + 50-seed quick chaos     (or: ./gradlew test)
make chaos SEEDS=500  # long simulation                     (or: ./gradlew :sim:chaos -Pseed=1 -Pseeds=500)
make chaos-one SEED=81
make bench            # group-commit window benchmark → CSV
make check-history FILE=build/histories/123.jsonl   # Porcupine: OK / ILLEGAL (+ HTML visualization)
```

On Windows without `make`, use `gradlew.bat` with the same task names.

## Module layout

```
raft-core/       RaftCore, Message, Output, LogEntry   (pure logic, no I/O)
raft-storage/    FileStorage (WAL), HardStateFile, GroupCommitStorage
raft-transport/  RaftNode driver, Transport, StorageOutputHandler
sim/             SimCluster, SimNetwork, SimDisk, SimClock, Nemesis, ChaosTest, HistoryRecorder
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
