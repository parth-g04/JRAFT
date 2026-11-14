# Architecture decisions

## RaftCore is a deterministic state machine

- Date: 2026-11-15
- Status: accepted for Phase 1
- Decision: keep Raft protocol state in `raft-core` and expose `step`, `tick`, and `propose` operations that return ordered `Output` values. The core receives logical ticks instead of reading wall-clock time. A driver executes persistence, log, network, and apply outputs in order.
- Reason: the same core can be driven by a real transport or by the deterministic simulator planned for Phase 2. Ordered outputs make persist-before-send visible and testable.
- Consequence: the legacy socket server remains unchanged during this phase; wiring it into the new protocol is a later integration task.
