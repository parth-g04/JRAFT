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
