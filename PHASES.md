# J-Raft v2 — Build Phases

---

## Phase 0 — Audit existing code + build/test harness (Day 1–3)

**Prompt:**
```
Read AGENTS.md, docs/GLOBAL_AGENT_RULES.md, docs/ARCHITECTURE.md, docs/PHASES.md. Implement ONLY Phase 0.
1. Read the entire existing J-Raft codebase. Write docs/AUDIT.md: current structure, how each Raft feature is implemented (election, replication, commit, apply, heartbeats, recovery), threading model, known correctness risks (cite file:line), and a mapping table "existing class → target module in ARCHITECTURE.md section 9".
2. Convert the build to a Gradle multi-module project matching section 9 WITHOUT changing behaviour (move code, fix imports). All existing tests (if any) must pass; if none, add smoke tests: 3-node cluster elects one leader; PUT then GET works.
3. Makefile (build, test, chaos, bench, check-history), GitHub Actions CI with Java 21 and Go 1.22.
4. docs/BUGS.md template (date, symptom, how found, root cause, fix, regression test).
Do NOT start refactoring Raft logic yet. Ask me before deleting any existing functionality.
```
**Acceptance:** `make test` green; `AUDIT.md` reviewed **by you**. Read it carefully; it is your own code explained back.

---

## Phase 1 — RaftCore as a deterministic state machine (Day 4–8)

**Learn first:** why separating logic from I/O makes testing possible; the etcd/raft "Ready" pattern.

**Prompt:**
```
Implement ONLY Phase 1: refactor Raft logic into raft-core's RaftCore with step/tick/propose returning Output lists (ARCHITECTURE.md section 1). No threads, sockets or System time inside raft-core.
- Add PreVote and fast log backtracking (conflictIndex/conflictTerm).
- RaftNode driver executes outputs in order: persist (still in-memory storage for now via an interface) → send → apply.
- Port existing behaviour; keep TcpTransport working behind a Transport interface.
- Unit tests directly on RaftCore: election safety (one leader per term), log matching, leader completeness, Figure-8 scenario from the Raft paper, PreVote prevents term inflation from a partitioned node.
Add ADR "RaftCore pure state machine".
```

---

## Phase 2 — Simulation framework + nemesis (Day 9–12) ⭐

**Prompt:**
```
Implement ONLY Phase 2: sim package (ARCHITECTURE.md section 6) and HistoryRecorder + tools/porcupine-check Go program (section 7).
- Single-threaded scheduler driving all nodes with SimClock; SimNetwork with drop/delay/duplicate/reorder/partition; crash/restart nodes (in-memory storage reset for now — durability comes in Phase 3, so restart = new empty node is NOT allowed yet; only network faults in this phase).
- Nemesis schedules random faults from a seed; ChaosTest(seed) parameterized over 200 seeds; histories written to build/histories/<seed>.jsonl; failing seeds print the reproduce command.
- Porcupine checker with KV model partitioned by key; HTML visualization written for failures.
- Basic KV state machine on top (single group, no sharding) with Clerk retries.
Record every bug the simulation finds in docs/BUGS.md with a regression test.
```
**Acceptance:** 200 seeds pass, or each failing one is documented and fixed. Expect to find bugs. That is the point.

---

## Phase 3 — Durable storage: WAL, hard state, recovery (Day 13–16)

**Learn first:** `FileChannel.force`, write-temp-rename atomicity, CRC32, torn writes.

**Prompt:**
```
Implement ONLY Phase 3 (ARCHITECTURE.md section 2): raft-storage with WAL segments, HardStateFile, recovery with CRC tail truncation, group commit.
- Storage interface implemented by FileStorage and SimDisk (models lost unsynced writes and torn last record on crash).
- Enforce persist-before-send in RaftNode (test with a spy).
- Extend nemesis with crash/restart (state from SimDisk). Run ChaosTest 500 seeds.
- Unit tests: corrupt last record → recovered log excludes it; random crash points during append.
- Metric raft_fsync_seconds; benchmark group-commit window 0/1/2/5 ms (bench module stub) and record results.
```

---

## Phase 4 — Exactly-once + linearizable reads (Day 17–19)

**Prompt:**
```
Implement ONLY Phase 4.
- Client sessions: clientId/seq, dedup table in state machine returning cached reply for duplicates (ARCHITECTURE.md section 4).
- ReadIndex for GET: leader records commitIndex, confirms leadership with a heartbeat quorum tied to a readId, waits until lastApplied ≥ index, then reads. Followers redirect.
- Nemesis adds message duplication + client retries to the same and different leaders.
- Tests: duplicated APPEND applied once; stale-leader read scenario (old leader partitioned) must not return stale data — verified by the checker.
```

---

## Phase 5 — Snapshots & InstallSnapshot (Day 20–22)

**Prompt:**
```
Implement ONLY Phase 5: StateMachine.snapshot/restore, snapshot trigger at log size threshold, compaction of WAL segments, chunked InstallSnapshot RPC, follower restore. Update RaftCore indexing to work with an offset log (lastIncludedIndex/Term).
- Tests: follower down for 10k entries catches up via snapshot; crash during snapshot write never loses data; recovery time benchmark with/without snapshots for 1M entries → docs/RESULTS.md.
- ChaosTest 500 seeds with small snapshot threshold (to exercise it constantly).
```

---

## Phase 6 — Membership changes (Day 23–24)

**Prompt:**
```
Implement ONLY Phase 6: single-server membership change via ConfigChange log entries effective when appended; learner (non-voting) catch-up before promotion; leader step-down if removed. Admin API addServer/removeServer. Nemesis adds random membership changes. Tests + 300 chaos seeds.
```

---

## Phase 7 — Shard controller + sharded KV with migration (Day 25–30) ⭐

**Prompt:**
```
Implement ONLY Phase 7 (ARCHITECTURE.md sections 4 and 5).
- shardctrler: Raft-replicated config service with Join/Leave/Move/Query and deterministic minimal-movement rebalance (unit tests: balance property, determinism, minimal moves).
- ShardKvServer: polls controller, applies config changes via log entries one config at a time, pull-based migration of shard data + dedup tables, WRONG_GROUP replies, garbage collection of migrated shards, keeps serving unaffected shards during migration.
- Clerk: routes by shard, refreshes config on WRONG_GROUP.
- Chaos: joins/leaves during load + partitions + crashes; checker over all keys; 300 seeds.
```

---

## Phase 8 — Real network test, metrics, benchmarks (Day 31–33)

**Prompt:**
```
Implement ONLY Phase 8.
- ProxyTransport-based chaos test using real TCP across processes (docker compose with 3 groups × 3 + controller, and a chaos controller that toggles proxies); record histories with wall-clock and check them.
- Micrometer metrics from ARCHITECTURE.md section 8 + Grafana dashboard.
- bench: ops/s and p50/p99 vs shard count (1, 2, 4 groups) and read ratio; fail-over time measurement (kill leader 50 times); plots in docs/RESULTS.md.
```

---

## Phase 9 — Polish

**Prompt:**
```
Implement ONLY Phase 9: README.md rewrite (pitch, Mermaid diagrams for architecture and migration sequence, features vs v1 table, verification methodology, results, bug stories from BUGS.md, how to run chaos and view Porcupine visualizations), clean code, all tests green.
```

**Then yourself:** update your resume's J-Raft entry (see COMPANY_PROJECT_MAP.md). Practice explaining Figure 8 and ReadIndex on a whiteboard.
