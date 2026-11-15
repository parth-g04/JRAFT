# Bug diary

Record each simulation-discovered bug and its regression test here.

## Entry template

- Date:
- Symptom:
- How found (include seed and `Reproduce: make chaos-one SEED=<n>` when applicable):
- Root cause:
- Fix:
- Regression test:

## 2026-11-15 — Seed 81 stalls elections after a restart

- Symptom: a three-node simulated cluster stopped completing a client write after a node restart and short partition.
- How found: 500-seed Phase 3 chaos run. Failure output: `Reproduce: make chaos-one SEED=81`.
- Root cause: PreVote replies were counted only when the responder's current term exactly matched the candidate's. A healthy peer can be behind the candidate's term and still grant the matching prospective-term request, so the equality check discarded a valid vote and blocked a majority.
- Fix: include the requested prospective term in `PreVoteReply`; count grants correlated to the active prospective term when the responder is not ahead.
- Regression test: `RaftCoreTest.prevoteReplyFromPeerOnAnOlderTermCountsForMatchingRound` and `ChaosSimulationTest.regressionSeed81KeepsClusterAvailableAfterRestartAndPartition`.

## 2026-11-15 — Group commit never actually grouped writes

- Symptom: with a 1–5 ms group-commit window, two appends issued inside the same window still produced two fsyncs, and the second waited for a second full window.
- How found: rewriting the group-commit benchmark to use concurrent writers (the old benchmark flushed after every batch from one thread, which hid the problem).
- Root cause: `GroupCommitStorage.scheduleIfNeeded` copied `pending` into the batch when the window *opened*. Entries appended while the window was open were left for the next batch.
- Fix: the scheduled commit drains `pending` when the window *closes*, so every entry appended during the window shares one `force(true)`.
- Regression test: `GroupCommitStorageTest.appendsInsideTheSameWindowShareOneFsync`.

## 2026-11-15 — WAL recovery looked broken because `LogEntry` equality compared array references

- Symptom: `FileStorageTest` failed with "expected LogEntry[index=1, term=1, ...] but was LogEntry[index=1, term=1, ...]" even though the recovered bytes were identical.
- How found: running `gradle test` for the first time (the Phase 3 tests had only been run as ad-hoc smoke checks).
- Root cause: `LogEntry` is a record holding `byte[] command`. A record's generated `equals`/`hashCode` compare arrays by reference, so a recovered entry never equals the written one.
- Fix: `LogEntry` overrides `equals`, `hashCode` and `toString` using `Arrays.equals/hashCode/toString`. No Raft logic compared entries, so behaviour is unchanged.
- Regression test: all `FileStorageTest` recovery tests.

## 2026-11-15 — CI red since Phase 1 (build + test harness issues)

- Symptom: GitHub Actions `make test` failed on every push; locally the build could not start.
- How found: reproducing CI locally.
- Root causes (four independent problems):
  1. Gradle 9 no longer adds `junit-platform-launcher` to test runtime, so every test task failed with "Failed to load JUnit Platform".
  2. `RaftCoreTest.appendConflictReportsFirstIndexOfConflictingTerm` sent `prevLogIndex=0`, which always matches, so the follower correctly accepted it and no conflict hint was produced. The test was wrong, not `RaftCore`.
  3. `FileStorageTest` contained a copy-pasted duplicate method, so it did not compile.
  4. `PorcupineCheckerIntegrationTest` resolved `tools/porcupine-check` relative to `sim/` (Gradle's test working directory). It is skipped without Go, so it only failed in CI.
- Fix: JUnit BOM + launcher in the root build; corrected the test to send a mismatching `prevLogIndex/prevLogTerm`; removed the duplicate; Gradle passes `jraft.repoRoot` to the sim tests; CI runs `go mod tidy && go test` for the checker; Gradle wrapper pinned so CI and local builds use the same version. Also dropped the strict JDK-21 toolchain (it failed on machines with only JDK 17/25) in favour of `options.release = 21`.
- Regression test: `make test` in CI.

## 2026-11-15 — Simulator could not detect stale reads (test-harness bug)

- Symptom: none visible. That was the problem. Every seed passed regardless of how reads were served.
- How found: designing the Phase 4 stale-leader test.
- Root cause: the Phase 2/3 harness sent every GET through the Raft log, and the client took its answer from *any* replica's state machine (`SimCluster.result` scanned all nodes) instead of from the server it talked to. An old leader could never hand a stale value to a client, so the checker had nothing to catch.
- Fix: a real client/server path (`ClientRequest`/`ClientReply` over a seeded, fault-injecting client channel that ignores partitions, so clients can reach an isolated old leader). Clients only see what the contacted server replies; every seed is checked by the in-process `LinearizabilityChecker`.
- Regression test: negative controls that serve GETs from local state without ReadIndex and require the checker to fail: `ClientScenarioTest.withoutReadIndexTheSameScenarioReturnsStaleDataAndTheCheckerCatchesIt`, `ChaosSimulationTest.seed22WithoutReadIndexIsCaughtByTheChecker` (seed 34 until the Phase 6 nemesis changed the schedule), and `PorcupineCheckerIntegrationTest.porcupineAlsoFlagsTheStaleReadHistory` (CI).

## 2026-11-15: Follower could commit entries it had not verified (latent)

- Symptom: none in practice so far. Found by code review while adding bounded AppendEntries batches for Phase 5.
- How found: writing `RaftCoreSnapshotTest.followerCommitsOnlyEntriesVerifiedByThisRequest`.
- Root cause: on a successful AppendEntries the follower set `commitIndex = min(leaderCommit, lastLogIndex)`. Raft (Figure 2) says `min(leaderCommit, index of last new entry)`. Entries after the request's last entry were never checked against the leader's log in that request and may be a stale suffix from an old term. Committing them would apply entries the cluster never committed. It stayed hidden because the leader always sent its entire log suffix, so the two values were equal. Once a leader sends bounded batches (or a delayed short request arrives), they differ.
- Fix: commit at most `previousIndex + entries.size()` (the last index this request verified).
- Regression test: `RaftCoreSnapshotTest.followerCommitsOnlyEntriesVerifiedByThisRequest` (fails with the old rule: commits 3 instead of 1).

## 2026-11-15: AppendEntries ping-pong multiplied messages without bound

- Symptom: after adding two more server processes and membership changes, some chaos seeds took 17-71 s instead of ~0.2 s, with a normal number of ticks. Stack samples showed all the time in `SimNetwork.drainDue`: the in-flight message queue kept growing.
- How found: Phase 6 chaos run (100 seeds did not finish in 10 minutes); profiled seed 21.
- Root cause: the leader answered *every* AppendReply with another AppendEntries, even when the follower was fully caught up. Each heartbeat therefore started a request/reply chain that never ended, the number of chains grew with every heartbeat, and each duplicated message (duplication storms) forked yet another permanent chain. It existed since Phase 1. It was hidden by the shorter runs and smaller clusters of earlier phases, but already cost time: 500 seeds took 3.5 min in Phase 5.
- Fix: after a successful reply, send more only if the follower's nextIndex is still within the leader's log; after a rejection, always retry (backtracking). Heartbeats and ReadIndex rounds still go out on their own schedule.
- Regression test: whole chaos suite; 500 seeds now run in 50 s with more nodes and more work (no seed over a few hundred ms).
