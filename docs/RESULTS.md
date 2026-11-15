# Simulation results

## Phase 2: deterministic simulator

- Date: 2026-11-15
- Workload: 200 consecutive seeds starting at seed 1; each seed runs one three-node group, short partitions, message loss/delay/duplication, and 24 sequential PUT/GET operations.
- Result: all 200 seeds passed. After healing, the three state-machine maps agreed for every run.
- Reproduce: `java -cp build/phase2-all jraft.sim.ChaosTest --seed 1 --seeds 200` (or `make chaos SEEDS=200` after Gradle is available).
- History: `build/histories/<seed>.jsonl` for each seed.
- Porcupine: checker and Go integration test are implemented but were not run in this environment because Go is unavailable.

These runs do not cover crashes, durable recovery, concurrent clients, or ReadIndex. Those are later phases.

## Phase 3: WAL, recovery, and restart simulation

- Date: 2026-11-15
- Workload: 500 consecutive seeds starting at seed 1; temporary network partitions plus simulated node crashes/restarts, with 24 PUT/GET operations per seed.
- Crash model: `SimDisk` loses unsynced writes. When the unsynced writes were pure appends, a seeded random prefix of them survives and the torn record after it is discarded (what CRC tail truncation does on a real disk). Hard state is replaced atomically, so it is old or new, never torn.
- Result: all 500 seeds passed (`./gradlew :sim:chaos -Pseed=1 -Pseeds=500` printed `Chaos passed for 500 seed(s)`). At the end of each run, after all nodes restarted and the network healed, replicas had identical state.
- Reproduce: `make chaos SEEDS=500`; a single seed: `make chaos-one SEED=<n>`.
- Regression discovered and fixed: seed 81 exposed a PreVote liveness bug. See [BUGS.md](BUGS.md).
- Storage unit tests (JUnit): hard-state round trip and CRC rejection; corrupt last WAL record is excluded on recovery; a crash at **every byte offset** of a 4-record WAL recovers a prefix of complete records and stays appendable; multi-segment roll and recovery; an unsynced truncation is never resurrected by a torn crash; persist-before-send order checked with a spy (`RaftNodePersistenceOrderTest`).
- Metric: the `raft.fsync` Micrometer timer (exported as `raft_fsync_seconds` by a Prometheus registry) records every WAL and hard-state fsync.

### Group-commit window benchmark

`make bench`: 16 writer threads, each appending one 100-byte entry and waiting for it to be durable, 64 times (1,024 entries). Windows laptop SSD, JDK 25, three runs per window (ranges shown).

| window | fsyncs | entries / fsync | ops/s | avg fsync |
|-------:|-------:|----------------:|------:|----------:|
| 0 ms | 285-312 | 3.3-3.6 | 551-678 | 1.1-1.3 ms |
| 1 ms | 155-201 | 5.1-6.6 | 433-647 | 1.1-1.4 ms |
| 2 ms | 164-228 | 4.5-6.2 | 344-565 | 1.2-1.5 ms |
| 5 ms | 170-203 | 5.0-6.0 | 284-332 | 1.2-1.5 ms |

Reading: a window cuts the number of fsyncs by about 40%, but on this machine an fsync costs only ~1.2 ms, so waiting 1-5 ms for more writers costs more than it saves and throughput falls. Even at 0 ms, writers that arrive while a force is in progress share the next one (3.3 entries per fsync). A window pays off when fsync is slow relative to the window (spinning disks, cloud block storage). These are local single-machine numbers, not a general performance claim.

- Environment: JDK 25 compiling with `--release 21`, Gradle 9.8.1 wrapper. Go is not installed locally, so the Porcupine checker runs only in CI.

## Phase 4: exactly-once clients and ReadIndex reads

- Date: 2026-11-15
- Workload per seed: 3 concurrent clients × 40 operations (40% GET, 30% PUT, 30% APPEND) on 3 keys with unique values; clients retry with the same `(clientId, sequence)` to the same or a different node, follow leader hints, and 20% of operations start at a random node. Nemesis: partitions of 10-40 ticks (half of them isolate the current leader, which clients can still reach), crash/restart with torn-tail disks, and duplication storms (30% of Raft and client messages duplicated). Client channel: 5% drop, 5% duplicate, 0-2 ticks delay.
- Checks per seed: every client operation completes after healing (liveness); replicas converge; the whole history is **linearizable** (in-process `LinearizabilityChecker`).
- Result: all 500 seeds passed (`./gradlew :sim:chaos -Pseed=1 -Pseeds=500` printed `Chaos passed for 500 seed(s)` in 3 min 5 s).
- Negative control: the same chaos with ReadIndex disabled (GETs served from the leader's local state) failed the linearizability check on 16 of seeds 1-200; the first is seed 34 (a GET on an isolated old leader returned a value overwritten through the new leader). With ReadIndex: 0 of 500.
- Scripted tests: isolated old leader never answers a read and its pending read fails with WRONG_LEADER after healing; the same scenario without ReadIndex returns the stale value and is flagged; an APPEND delivered three times, and retried at a new leader after the old one crashed, is applied exactly once on every replica.
- Reproduce: `make chaos SEEDS=500`; `make chaos-one SEED=34`.
- Porcupine: CI cross-checks seed 9001 (must be OK) and the unsafe seed-34 history (must be ILLEGAL). Not run locally (no Go).

## Phase 5: snapshots, compaction and InstallSnapshot

- Date: 2026-11-15
- Chaos: the Phase 4 workload and nemesis, now with a snapshot every **8** in-memory log entries, **64-byte** InstallSnapshot chunks and AppendEntries batches of at most 8 entries.
- Result: all 500 seeds passed (linearizable, replicas converge, every client operation completes): `Chaos passed for 500 seed(s); snapshots taken 18018, installed via InstallSnapshot 1816 (in 489 seeds)`, 3 min 27 s.
- Scripted: a follower down for 10,000 entries catches up by InstallSnapshot (1 KB chunks), ends with fewer than 1,000 entries in its log and the same state as the leader, and recovers that state from its own disk after a restart. A crash between saving a snapshot and compacting the WAL recovers the same state. Client sessions survive a snapshot install, so a retried APPEND is still applied once.
- Storage tests: partial snapshot `.tmp` at crash → old snapshot and full log; snapshot saved but compaction not run → log resumes after the snapshot and recovery deletes covered segments; snapshot beyond the whole log → empty WAL, appends continue at index+1; corrupt newest snapshot → falls back to an older valid one; truncate after compaction.
- Reproduce: `make chaos SEEDS=500`; `make chaos-one SEED=<n>`.

### Recovery time, 1M entries (`./gradlew :bench:recoveryBenchmark`)

1,000,000 committed PUTs on 10,000 keys with 100-byte values. Recovery = open storage + rebuild the KV state machine. Three runs each; Windows laptop SSD, JDK 25, warm OS page cache.

| mode | entries replayed | WAL on disk | snapshot | recovery (median) | runs |
|------|-----------------:|------------:|---------:|------------------:|------|
| WAL only | 1,000,000 | 158.9 MB | none | 1,267 ms | 1267 / 1101 / 1447 ms |
| snapshot at 999,000 + suffix | 1,000 | 24.7 MB | 1.1 MB | 91 ms | 91 / 166 / 79 ms |

Reading: recovery time follows the number of replayed entries; with a snapshot it is about 14x faster and no longer grows with history. The WAL still holds 24.7 MB after compaction because segments are 64 MB and only deleted once every record in them is covered; the newest segment straddles the snapshot point. The OS page cache was warm (data had just been written), so cold-disk numbers would be higher for both modes. Single machine, not a general performance claim.

- Also in this phase: `FileStorage` keeps the active segment's channel open and parses segments from one buffered read instead of opening the file per append and reading fields with `RandomAccessFile.readInt`; writing 1M entries takes about 1.4 s. `SimDisk.flush` copies only the new tail instead of the whole log (it was O(n^2) and made the 10k-entry test take 214 s; now ~19 s for the whole snapshot test class).

## Phase 6: membership changes

- Date: 2026-11-15
- Chaos: the Phase 5 workload (snapshots every 8 entries, 64-byte chunks) with five server processes. Nodes 1-3 start as voters, 4-5 outside the cluster. The nemesis now also changes membership: it adds a non-member (as a learner, promoted when caught up) or removes a random voter (often the leader) while keeping at least three voters. Crashes and partitions target current members; removed servers keep running.
- Checks per seed: linearizable history, every client operation completes after healing, and all members of the final configuration have identical state.
- Result: all 500 seeds passed (the spec asks for 300): `Chaos passed for 500 seed(s); snapshots taken 27422, installed via InstallSnapshot 4142 (in 500 seeds), membership changes 4164 (leader removed itself 453x)`, in 50 s.
- Negative control: with ReadIndex disabled, 16 of seeds 1-200 still fail the linearizability check (first: seed 22, now the regression seed).
- Scripted: grow 3 to 5 voters and remove two original servers (the leader first) under client load, still linearizable; a removed leader hands over and the group keeps committing; a learner that is 200 entries behind catches up by InstallSnapshot before promotion.
- Performance: fixing the AppendEntries ping-pong (BUGS.md) cut the 500-seed run from 3 min 27 s (Phase 5, 3 nodes) to 50 s (5 nodes, membership changes).
- Reproduce: `make chaos SEEDS=500`; `make chaos-one SEED=<n>`.
