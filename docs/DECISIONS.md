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

## Snapshots: offset log, driver-owned state machine bytes, durable before compaction

- Date: 2026-11-15
- Status: accepted for Phase 5
- Decision: `RaftCore` keeps `snapshot` (lastIncludedIndex/Term + bytes) and only entries after it; all index arithmetic goes through `termAt`/`entryAt`. The driver decides *when* to snapshot (log size threshold) and passes `StateMachine.snapshot()` bytes to `compact(index, bytes)`. The core emits `SaveSnapshot`, and the driver writes the snapshot durably (temp + fsync + rename) **before** `compactThrough` deletes WAL segments. A received snapshot is `ApplySnapshot`: persist, compact, then `restore`.
- Reason: the core stays pure (no I/O, no state-machine knowledge beyond bytes). Ordering persistence before compaction means a crash at any point leaves either the old snapshot with the full WAL, or the new snapshot with a WAL that may still hold covered entries. Recovery skips those and finishes the compaction.
- Consequence: WAL segments are deleted whole, only once every record in them is covered, so up to one segment (64 MB) of covered records can remain.

## InstallSnapshot: chunked, pinned per follower, paper's keep-or-discard rule

- Date: 2026-11-15
- Status: accepted for Phase 5
- Decision: the leader sends the snapshot in chunks (16 KB by default, 64 bytes in chaos), one chunk per heartbeat or reply; the follower replies with the next offset it expects, so loss, duplication and follower restarts are handled by resending from that offset. Each follower's transfer is **pinned** to the snapshot it started with until it is installed. On the last chunk the follower keeps its log suffix if its log has the snapshot's last entry (same index and term), otherwise it discards the whole log (Raft Figure 13). A snapshot not newer than the follower's commit index is ignored.
- Reason: without pinning, a leader that compacts faster than a transfer completes would restart the transfer forever (the chaos threshold of 8 entries makes this likely).
- Consequence: a follower may install a slightly older snapshot and then catch up with normal AppendEntries or another snapshot.

## Bounded AppendEntries batches

- Date: 2026-11-15
- Status: accepted for Phase 5
- Decision: the leader sends at most `maxEntriesPerAppend` entries per AppendEntries (64 by default, 8 in the simulator). `matchIndex`/`nextIndex` only move forward on success replies.
- Reason: a follower that is 10,000 entries behind should not receive one giant message. This also required the follower commit-rule fix in BUGS.md.

## Membership: single-server changes, configuration effective when appended

- Date: 2026-11-15
- Status: accepted for Phase 6
- Decision: membership is a `Configuration{voters, learners}` stored in `CONFIG` log entries (WAL record type 2). Every server uses the **latest configuration in its log**, committed or not, from the moment the entry is appended; truncating the entry reverts to the previous one. Only one change at a time, and only once the leader has committed an entry of its own term (Raft thesis section 4.1 and Ongaro's 2015 correction to single-server changes). Snapshots record the configuration as of their index (snapshot file format v2, v1 still readable), and InstallSnapshot carries it.
- Reason: adding or removing one voter at a time guarantees that any majority of the old voter set overlaps any majority of the new one, so two leaders cannot be elected for the same term without joint consensus.
- Consequence: commit, election and ReadIndex quorums are computed over the current configuration's voters. A leader that removes itself keeps replicating without counting itself and steps down when the change commits.

## Learners and automatic promotion

- Date: 2026-11-15
- Status: accepted for Phase 6
- Decision: `addServer` adds a non-voting learner. The leader replicates to it (entries or snapshot) but never counts it for quorums, and promotes it to voter with a second single-server change once its matchIndex reaches the commit index. Servers that are not voters (learners, new or removed servers) never start elections; any server accepts AppendEntries from a leader even if it is not yet in its own configuration.
- Reason: adding an empty voter directly would immediately raise the quorum size while that server cannot acknowledge anything, which can stall commits (thesis section 4.2.1).
- Consequence: an add is two log entries (learner, then promotion). Admin API: `addServer(id)` / `removeServer(id)` returning ACCEPTED, NOT_LEADER, IN_PROGRESS, NO_CHANGE or INVALID (removing the last voter).

## Sharding: controller group, per-shard status machine, pull-based migration

- Date: 2026-11-15
- Status: accepted for Phase 7
- Decision: a Raft-replicated **shard controller** keeps numbered configurations (16 shards -> group, groups -> servers). Join/Leave/Move are logged with client sessions; Query is a ReadIndex read. Rebalancing gives the ceil(16/g) targets to the groups that already own the most shards and moves only shards forced out (minimal, deterministic).
- Decision: each **shard group** puts every step of a reconfiguration in its own Raft log: `Reconfigure(next)` (only the next number, only when every shard is SERVING), `InsertShards`, `DeleteShards`, `GcDone`. Shard status: SERVING, PULLING (ours, waiting for data), BEPULLING (frozen copy kept for the new owner), GCING (we have it; old owner must delete). Client operations and ReadIndex reads succeed only on SERVING/GCING shards, otherwise WRONG_GROUP.
- Decision: migration is **pull-based**: the new owner's leader asks the previous owner for the frozen data and the per-shard session table, retrying until it gets it; then it asks the old owner to delete its copy and records GcDone. Requests are stateless and simply repeated; every log command is idempotent.
- Reason: a group can only lose a shard by committing a configuration entry; after that entry the shard is frozen, so the data the new owner pulls includes every write the old owner ever accepted, and no write is accepted twice (sessions move with the shard). Serving only one configuration step at a time keeps the state per shard small and explainable. Unaffected shards keep serving throughout.
- Consequence: a group waits for all its migrations (in and out) before applying the next configuration, so a slow peer group delays reconfiguration of its neighbours but never correctness.
- Simulator: `GroupNode` is a generic Raft host (RaftCore + SimDisk + a service: controller or shard group); `ShardedWorld` runs a controller and three shard groups (12 nodes) on one deterministic network. The single-group `SimCluster` from Phases 2-6 is kept unchanged for its tests.
