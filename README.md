# J-Raft: Distributed Key-Value Store

A lightweight, persistent distributed database built from scratch in Java. This project implements a custom consensus mechanism inspired by **Raft** to handle leader election, node fault tolerance, and data consistency across a networked cluster.

##  Overview

J-Raft moves beyond standard CRUD applications by tackling the complexities of **Distributed Systems**. It replaces high-level frameworks with raw socket programming to manage node states, handle race conditions, and ensure data survival during crashes.

The system is designed to run as a cluster of independent nodes that communicate via a custom binary/text protocol to elect a leader and serve client requests.

##  Key Features

### 1. Distributed Consensus (Raft Implementation)
* **Leader Election:** Nodes monitor the health of the cluster. If a leader fails (simulated via timeout), followers automatically trigger an election to choose a new leader.
* **Heartbeat Mechanism:** The Leader maintains authority by sending periodic heartbeats. Followers reset their internal timeouts upon receipt to prevent unnecessary elections.
* **Split-Brain Protection:** Implements strict state checks (e.g., `VOTE_DENIED` logic) to ensure only one valid leader exists, even if network delays cause nodes to drift.

### 2. High-Performance Concurrency
* **Multithreading:** Uses a `Thread-Per-Client` model (via `ExecutorService` and `Runnable`) to handle non-blocking I/O.
* **Thread Safety:** Manages shared resources using `ConcurrentHashMap` and `volatile` state variables to prevent race conditions during state transitions.

### 3. Durability & Persistence
* **Write-Ahead Log (WAL):** Every `PUT` operation is appended to an on-disk log (`data.log`) before acknowledgment.
* **Crash Recovery:** On startup, the system parses the log to reconstruct the in-memory state, ensuring zero data loss across restarts.

---

##  Technical Stack

* **Language:** Java (JDK 17+)
* **Networking:** Raw `java.net.Socket` & `ServerSocket` (TCP)
* **Concurrency:** `java.util.concurrent`
* **I/O:** `java.io` (File Persistence)

---

##  How It Works (Design Decisions)

The system operates on a state-machine model where every node exists in one of three states:
1.  **FOLLOWER:** Passive state. Responds to requests and listens for Heartbeats.
2.  **CANDIDATE:** If the election timeout triggers (Leader is dead), the node promotes itself and requests votes.
3.  **LEADER:** Handles all write operations and manages the cluster state.

**Handling Conflicts:**
One of the core engineering challenges was preventing "Dual Leaders." The system enforces a strict rule where a node rejects vote requests if it believes a valid Leader is already active. Additionally, if a Candidate receives a Heartbeat from a valid Leader, it immediately steps down to Follower state.

---

##  Getting Started

### Prerequisites
* Java Development Kit (JDK) installed.
* A terminal (or multiple terminals to simulate the cluster).

### Installation
Clone the repository and compile the source code:
```bash
git clone https://github.com/parth-g04/JRAFT.git
cd JRAFT/src
javac *.java
```
### Running the Cluster
To simulate a distributed environment, open **3 separate terminals** and run the following commands in order:

**Terminal 1 (Node A):**
```bash
java Server 5000
```
Initially, this node will become Leader as it is alone.

**Terminal 2 (Node B):**
```bash
java Server 5001
```
**Terminal 3 (Node C):**
```bash
java Server 5003
```
## Client Interaction
**You can use the included Client to test data storage:**
```bash
java client
```
## Testing Fault Tolerance

To verify the system's resilience:
1.  Start the cluster (Ports 5000, 5001, 5002).
2.  Identify the **Leader** (Console will show `[Leader] Sending Heartbeats...`).
3.  **Kill the Leader** (Press `Ctrl+C`).
4.  Watch the other nodes. Within 3 seconds, one will detect the failure, start an election, and become the new Leader.
5.  **Restart the old node.** It will rejoin the cluster automatically as a Follower.

---

## 🔮 Future Roadmap

* **Log Replication:** Currently, data is persisted locally. Next step is to replicate the Write-Ahead Log across all follower nodes.
* **Snapshotting:** Implement log compaction to manage disk usage over time.
* **Dynamic Membership:** Allow adding/removing nodes without restarting the cluster.

---

**Author:**
Parth Gupta


