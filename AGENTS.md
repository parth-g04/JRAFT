# AGENTS.md — J-Raft v2

Also follow `docs/GLOBAL_AGENT_RULES.md`. Spec: `docs/ARCHITECTURE.md`. Plan: `docs/PHASES.md`.

## Project
Upgrade an EXISTING student-written Raft KV store (Java). The student must be able to explain every line, so refactor incrementally and explain changes.

## Stack
Java 21, Gradle 8 (Kotlin DSL) multi-module, JUnit 5 + AssertJ, Micrometer, Go 1.22 only for `tools/porcupine-check` (github.com/anishathalye/porcupine).

## Commands
```
make test            # unit + 50-seed quick chaos
make chaos SEEDS=500 # long simulation
make check-history FILE=build/histories/123.jsonl
make bench
```

## Rules
1. **Correctness over performance.** Any change to Raft logic must keep all chaos seeds passing.
2. `raft-core` has zero I/O, zero threads, zero wall-clock access. Time = ticks.
3. **Persist before send.** Never reply to an RPC before required state is durable.
4. Deterministic decisions only: never iterate `HashMap`/`HashSet` where order affects replicated state; use sorted structures.
5. Every bug found by simulation → entry in `docs/BUGS.md` + regression test with the seed.
6. Don't copy MIT 6.5840 solutions from the internet. Implement from the Raft paper and this spec.
7. Do not delete existing features without asking.
8. Keep a seed-reproducible failure message: `Reproduce: make chaos-one SEED=<n>`.

## Definition of done
Phase acceptance met; quick chaos (50 seeds) in CI green; long chaos run result recorded in `docs/RESULTS.md`.
