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
