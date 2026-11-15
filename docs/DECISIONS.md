# Architecture decisions

## RaftCore is a deterministic state machine

- Date: 2026-11-15
- Status: accepted for Phase 1
- Decision: keep Raft protocol state in `raft-core` and expose `step`, `tick`, and `propose` operations that return ordered `Output` values. The core receives logical ticks instead of reading wall-clock time. A driver executes persistence, log, network, and apply outputs in order.
- Reason: the same core can be driven by a real transport or by the deterministic simulator planned for Phase 2. Ordered outputs make persist-before-send visible and testable.
- Consequence: the legacy socket server remains unchanged during this phase; wiring it into the new protocol is a later integration task.

## Build: Gradle wrapper 9.8.1 and `--release 21` instead of a JDK-21 toolchain

- Date: 2026-11-15
- Status: accepted
- Decision: commit a Gradle wrapper (9.8.1) and compile with `options.release = 21` rather than requiring a JDK-21 toolchain.
- Reason: without a wrapper, CI used whatever Gradle the runner shipped, and a strict toolchain failed on developer machines that have JDK 17/25 but not 21. `--release 21` still restricts the code to the Java 21 API and bytecode. Gradle 9 is used instead of 8 because Gradle 8 cannot run on JDK 25.
- Consequence: any JDK ≥ 21 builds the project; CI stays on Java 21.

## Group commit drains at window close

- Date: 2026-11-15
- Status: accepted for Phase 3
- Decision: `GroupCommitStorage` collects the batch when the window closes, not when it opens; `flush()` blocks until the caller's entries are forced.
- Reason: a batch captured at open time can never include later writers, which defeats the purpose of the window (see BUGS.md).
- Consequence: `RaftNode` still flushes before every `Send`/`Apply`, so persist-before-send holds regardless of the window.

## ReadIndex with read rounds stamped on AppendEntries

- Date: 2026-11-15
- Status: accepted for Phase 4
- Decision: `RaftCore.readIndex(readId)` records `readIndex` and a fresh `readRound`. Every AppendRequest the leader sends carries its current `readRound`; followers echo it in AppendReply. A read is ready when a majority (including the leader) has echoed a round at or above the read's round **and** `lastApplied >= readIndex`. Then the core emits `ReadReady` *after* the `ApplyEntries` it depends on. A leader that steps down fails all pending reads (`ReadFailed`).
- Reason: correlating acks with a round number instead of one message per read batches concurrent reads into the same heartbeat and survives duplicated or reordered replies (we keep the max round per peer). Any reply in the leader's term counts, even a log mismatch: it still proves the follower accepted this leader after the read began.
- Consequence: reads cost no log entry and no fsync, but one heartbeat round trip. No clock-based leases: the core has no wall clock.

## Leader commits a no-op at the start of its term

- Date: 2026-11-15
- Status: accepted for Phase 4
- Decision: `becomeLeader` appends an empty-command entry from the new term.
- Reason: a new leader cannot know its commitIndex is current until an entry of its own term commits (Raft section 8). Without the no-op, ReadIndex would wait for an unrelated client write. Until the no-op commits, `readIndex` uses `lastLogIndex` (which includes every committed entry, by leader completeness).
- Consequence: log indexes shift by one per term; state machines skip empty commands.

## Client sessions live in the replicated state machine

- Date: 2026-11-15
- Status: accepted for Phase 4
- Decision: `KvStateMachine` keeps `clientId -> (lastSequence, lastResult)` in a TreeMap. A command whose sequence is at or below `lastSequence` is not applied; a retry of the latest sequence gets the cached reply. Servers also answer from the cache before proposing.
- Reason: duplicates can be committed more than once (client retries to several leaders, duplicated messages). Deduplication must happen at apply time on every replica, deterministically. Clients have one outstanding request, so one entry per client suffices.
- Consequence: sessions grow with the number of clients (no expiry yet). Phase 5 snapshots must include the session table, and Phase 7 migrates it with each shard.

## In-process linearizability checker plus Porcupine

- Date: 2026-11-15
- Status: accepted for Phase 4
- Decision: `sim.LinearizabilityChecker` (per-key Wing-Gong search with memoization) checks every chaos seed in-process. `tools/porcupine-check` implements the same KV model independently and checks seeds in CI.
- Reason: running Go per seed is slow and needs Go locally. Two independent implementations make a checker bug much less likely to hide a real bug.
