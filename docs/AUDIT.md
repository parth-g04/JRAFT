# Existing implementation audit

## Current structure

The original six default-package Java files have been moved unchanged into `kv/src/main/java` so the repository can adopt the requested Gradle multi-module layout without changing runtime behavior. The other modules are scaffolds for later phases.

| Existing class | Current responsibility | Target module |
| --- | --- | --- |
| `Server` | Socket accept loop, election timer, ad hoc election and heartbeat sending | `raft-transport` driver plus `raft-core` logic (Phase 1); KV wiring in `kv` |
| `ClientHandler` | Text protocol and direct store access | `raft-transport` / `kv` |
| `RaftState` | Three process-global state labels | `raft-core` |
| `KeyValueStore` | Minimal storage interface | `kv` |
| `InMemoryStore` | Concurrent map and local append-only text file | `kv` initially; durable WAL belongs to `raft-storage` in Phase 3 |
| `Client` | Hard-coded single-node request example | `kv` client library, later Clerk |
| `Main` | Local store demonstration | `kv` demo |

## Raft feature map

| Feature | Existing implementation | Correctness status |
| --- | --- | --- |
| Election | `Server.runRaftCycle` counts self plus sequential `VOTE_REQUEST` replies and declares leadership at two votes | Not Raft-safe: no term, candidate identity, vote persistence, log freshness, randomized timeout, or concurrent cluster coordination |
| Replication | None | Writes go directly to the contacted node's local store |
| Commit | None | No log indexes, quorum replication, or commit index |
| Apply | Immediate local `put` | No committed-entry application order |
| Heartbeats | Leader sends a bare `HEARTBEAT` every 500 ms | No term/leader identity, acknowledgement tracking, or AppendEntries semantics |
| Recovery | `InMemoryStore.loadFromDisk` replays comma-separated local records | Not Raft recovery; data is not replicated and commas/newlines are not escaped |
| Client routing | Client hard-codes port 5000; server accepts PUT/GET on all nodes | No leader redirect and non-leader writes are accepted |
| Concurrency | One scheduled election thread, one new thread per connection, shared static Raft state | State is shared across all Server instances in one JVM and is not consistently synchronized |

## Known correctness risks

- `src/Server.java:14-16` (now `kv/src/main/java/Server.java`): Raft state and heartbeat time are static, so multiple servers in one JVM share a single state and timeout.
- `kv/src/main/java/Server.java:37-65`: elections have no terms or durable `votedFor`; sequential requests can produce unsafe leadership decisions and timeouts use wall-clock time.
- `kv/src/main/java/ClientHandler.java:27-41`: vote requests grant repeatedly to any requester unless the process is currently marked leader; no candidate ID or term is checked.
- `kv/src/main/java/ClientHandler.java:13-25`: malformed or incomplete client commands can throw indexing errors; values cannot contain spaces.
- `kv/src/main/java/ClientHandler.java:16-18`: every node acknowledges a local PUT, without checking leadership or replication.
- `kv/src/main/java/InMemoryStore.java:16-27`: the map is updated before persistence succeeds, and persistence errors are logged but do not fail the client operation.
- `kv/src/main/java/InMemoryStore.java:43-57`: comma-delimited recovery silently misparses keys/values containing commas and has no checksum or torn-tail handling.
- `kv/src/main/java/Server.java:72-80`: one unbounded thread is created per accepted client connection; scheduler lifecycle is not managed.

## Build and test baseline

The project had no build configuration or tests. Phase 0 adds Gradle Kotlin DSL modules and a small in-memory key/value smoke test. This test does not establish Raft correctness. The host currently has Java 25 but no Gradle or Go executable; CI is configured for Java 21 and Go 1.22.

## Phase 0 boundary

No Raft behavior was refactored. The target modules beyond the legacy code placement are intentionally empty scaffolds. No functionality was removed.
