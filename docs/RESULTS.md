# Simulation results

## Phase 2: deterministic simulator

- Date: 2026-11-15
- Workload: 200 consecutive seeds starting at seed 1; each seed runs one three-node group, short partitions, message loss/delay/duplication, and 24 sequential PUT/GET operations.
- Result: all 200 seeds passed. After healing, the three state-machine maps agreed for every run.
- Reproduce: `java -cp build/phase2-all jraft.sim.ChaosTest --seed 1 --seeds 200` (or `make chaos SEEDS=200` after Gradle is available).
- History: `build/histories/<seed>.jsonl` for each seed.
- Porcupine: checker and Go integration test are implemented but were not run in this environment because Go is unavailable.

These runs do not cover crashes, durable recovery, concurrent clients, or ReadIndex. Those are later phases.
