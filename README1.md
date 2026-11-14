# J-Raft v2 — Sharded, Durable, Linearizability-Verified Key-Value Store

> **One-line pitch:** An upgrade of your existing from-scratch Raft key-value store into a production-style system: durable write-ahead log, snapshots, membership changes, exactly-once client semantics, multi-group sharding with live shard migration, and **mathematical verification** that it never returns a wrong answer under network partitions and crashes.

**Targets:** Nutanix, Amazon, Juspay, Infosys SP, PubMatic, JPMorgan, Amadeus: any interview with a distributed-systems or "tell me about your hardest project" question
**Difficulty:** ★★★★★ | **Time:** 4–5 weeks (you already have the base) | **Language:** Java 21

---

## 1. Why upgrade instead of starting new?
Your current resume line says "leader election, heartbeats and crash recovery". Interviewers at Amazon/Nutanix will immediately ask:
- "What happens if the log grows forever?" → **snapshots**
- "Is your crash recovery real if the log is in memory?" → **durable WAL with fsync**
- "If a client retries a SET, is it applied twice?" → **client sessions / dedupe**
- "Are reads linearizable or can they be stale?" → **ReadIndex**
- "How do you add a node?" → **membership change**
- "How does it scale beyond one Raft group?" → **sharding**
- "How do you *know* it is correct?" → **linearizability checking + fault injection**

Answering all seven with "I built and tested it" puts you in the top 1% of candidates. (Nutanix's own distributed storage relies on consensus-based metadata, so this maps directly onto their product.)

## 2. Concepts you must learn (teacher notes)
1. **Raft recap** — one leader per term; leader appends entries; an entry is *committed* when stored on a majority; committed entries are applied to the state machine in order. Read the Raft paper (Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm", extended version) sections 5–8 again.
2. **Durability** — `currentTerm`, `votedFor` and the log must be on disk *before* replying to RPCs. "On disk" means `fsync`/`FileChannel.force(true)`, not just `write()`, otherwise the OS cache loses it on power failure.
3. **Write-ahead log (WAL) segments** — append-only files of records `[length][crc32][payload]`; CRC detects torn writes after a crash; truncate the partially written tail on recovery.
4. **Snapshots & log compaction** — periodically save the state machine and discard log entries up to that index. Slow followers receive the snapshot via `InstallSnapshot` RPC.
5. **Linearizability** — every operation appears to take effect atomically at some instant between its call and its return, and all clients agree on the order. The strongest single-object consistency model.
6. **ReadIndex** — to serve a read linearizably without writing to the log: leader records commitIndex, confirms it is still leader with a heartbeat round to a majority, waits until applied ≥ that index, then reads.
7. **Exactly-once semantics** — clients attach `(clientId, sequenceNo)`; the state machine remembers the last sequence applied per client and its result; duplicates return the cached result.
8. **Membership change (single-server)** — add or remove one server at a time via a special config log entry; new servers first catch up as non-voting *learners*.
9. **Sharding** — split keys into N shards (hash(key) mod N); each shard is owned by one Raft group; a **shard controller** (itself a Raft group) stores the shard→group config. When config changes, groups **migrate** shard data to each other.
10. **Linearizability checking** — record every client operation (invoke time, return time, input, output) and use an algorithm (Wing & Gong / Lowe, implemented by **Porcupine**) to check whether some valid sequential order explains all results.
11. **Fault injection / deterministic simulation** — run all nodes in one JVM with a fake network you control: drop, delay, duplicate, reorder messages, partition the cluster, crash and restart nodes. This is how FoundationDB and TigerBeetle test.
12. **Jepsen** — Kyle Kingsbury's project that breaks real databases with exactly these techniques; read a couple of Jepsen reports (e.g., on etcd) to see what bugs look like.

Reference: MIT 6.5840 (Distributed Systems) labs 3–5 describe exactly these features. **Read the lab descriptions for understanding, but don't copy others' solutions**: interviewers can tell.

## 3. Tech stack
Java 21 (virtual threads optional), your existing socket transport (or migrate to Netty for performance), JUnit 5, a Go helper program that wraps **Porcupine** for checking histories (JSON in → result + HTML visualization out), JMH or a custom load generator, Micrometer + Prometheus.

## 4. Proof you will produce
- "Linearizability verified on 2,000 randomized test runs (partitions, crashes, message loss up to 20%)".
- Throughput/latency chart: ops/s vs number of shards (1, 2, 4).
- Leader fail-over time distribution (p50/p99).
- Snapshot effect: recovery time with/without snapshots for a 1M-entry log.
- A bug story: "my checker found a stale-read bug when …, fixed by …". **Keep a BUGS.md diary.**

## 5. Demo (2 minutes)
1. Start 3 groups × 3 nodes + controller; run load.
2. Kill a leader → show fail-over in logs/metrics.
3. Add a group → shards migrate live, clients keep working.
4. Run the chaos test suite for 60 s → Porcupine prints "linearizable ✅" and opens the visualization.

## 6. Interview questions you must be able to answer
1. Why must `votedFor` be persisted? (Otherwise a node may vote twice in a term after restart → two leaders.)
2. Why can't a leader commit entries from previous terms by counting replicas? (Figure 8 in the Raft paper; it commits them only indirectly via a current-term entry.)
3. How does ReadIndex differ from lease-based reads? (Lease reads avoid the heartbeat round but rely on bounded clock drift.)
4. What happens to in-flight client requests during shard migration? (Group returns `WRONG_GROUP`; client refreshes config from controller and retries with same seq no → exactly-once preserved because dedupe tables migrate with the shard.)
5. How does your WAL detect corruption? (CRC32 per record + length prefix; truncate tail at the first bad record.)
6. What is the cost of fsync and how do you amortize it? (Group commit: batch multiple appends into one fsync.)
7. Explain linearizability vs sequential consistency vs eventual consistency with an example.
8. What did your fault injection find? (Your real bug stories.)
9. How would you make this multi-datacenter? (Raft groups spanning DCs add WAN latency per write; alternatives: per-DC groups + async replication, or witness replicas.)
10. Why single-server membership changes instead of joint consensus? (Simpler; safe because any two majorities of configurations differing by one server overlap.)
