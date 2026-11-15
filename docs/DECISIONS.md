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
