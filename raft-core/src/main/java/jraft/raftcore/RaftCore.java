package jraft.raftcore;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic Raft protocol state. It owns no threads, I/O, or clock.
 *
 * Log indexing (Phase 5): entries up to {@code snapshot.index()} have been compacted into a
 * snapshot. {@code log} holds only entries snapshot.index()+1 .. lastLogIndex(). Always go
 * through {@link #termAt(long)} and {@link #entryAt(long)} instead of indexing {@code log} directly.
 */
public final class RaftCore {
    public static final int DEFAULT_MAX_ENTRIES_PER_APPEND = 64;
    public static final int DEFAULT_SNAPSHOT_CHUNK_BYTES = 16 * 1024;

    private final int id;
    /** Membership before any configuration entry or snapshot configuration exists. */
    private final Configuration bootstrap;
    /** CONFIG entries currently in the in-memory log: index -> configuration. */
    private final TreeMap<Long, Configuration> configEntries = new TreeMap<>();
    private final int electionTimeoutTicks;
    private final int heartbeatTicks;
    private int maxEntriesPerAppend = DEFAULT_MAX_ENTRIES_PER_APPEND;
    private int snapshotChunkBytes = DEFAULT_SNAPSHOT_CHUNK_BYTES;
    private final List<LogEntry> log = new ArrayList<>();
    /** Latest snapshot: covers every entry up to snapshot.index(). Index 0 = no snapshot. */
    private Snapshot snapshot = Snapshot.EMPTY;
    private final Map<Integer, Long> nextIndex = new HashMap<>();
    private final Map<Integer, Long> matchIndex = new HashMap<>();
    private final Set<Integer> preVotes = new HashSet<>();
    private final Set<Integer> votes = new HashSet<>();
    private NodeRole role = NodeRole.FOLLOWER;
    private long currentTerm;
    private Integer votedFor;
    private long commitIndex;
    private long lastApplied;
    private int electionElapsed;
    private int heartbeatElapsed;
    private int leaderContactElapsed;
    private boolean preVoteRound;
    /** Who this node believes is the leader of currentTerm (null if unknown). Used for client redirects. */
    private Integer leaderId;

    // ---- ReadIndex state (leader only; never persisted) ----
    /** A read waiting for (1) a majority to ack readRound and (2) lastApplied >= readIndex. */
    private record PendingRead(long readId, long readIndex, long readRound) { }
    private final List<PendingRead> pendingReads = new ArrayList<>();
    /** Highest readRound each peer has echoed back in the current term. */
    private final Map<Integer, Long> ackedRound = new TreeMap<>();
    /** Incremented for every read; stamped on every AppendRequest the leader sends afterwards. */
    private long readRound;

    // ---- InstallSnapshot state ----
    /** Leader: the snapshot being sent to a peer. Pinned until installed, so a leader that keeps
     *  compacting cannot restart the transfer forever. */
    private final Map<Integer, Snapshot> transfers = new TreeMap<>();
    /** Leader: next byte offset to send to a peer for its pinned snapshot. */
    private final Map<Integer, Long> transferOffset = new TreeMap<>();
    /** Follower: chunks received so far for (receivingIndex, receivingTerm). */
    private ByteArrayOutputStream receiving;
    private long receivingIndex;
    private long receivingTerm;

    /** A member of a fixed initial cluster: voters = peers plus this node. */
    public RaftCore(int id, List<Integer> peers, int electionTimeoutTicks, int heartbeatTicks) {
        this(id, initialVoters(id, peers), electionTimeoutTicks, heartbeatTicks);
    }

    /**
     * {@code bootstrap} is the membership used until the log or a snapshot says otherwise. A node
     * that is not a voter in it (e.g. a server about to be added) never starts elections.
     */
    public RaftCore(int id, Configuration bootstrap, int electionTimeoutTicks, int heartbeatTicks) {
        if (electionTimeoutTicks < 1 || heartbeatTicks < 1 || heartbeatTicks >= electionTimeoutTicks) {
            throw new IllegalArgumentException("timeouts must be positive and heartbeat shorter than election timeout");
        }
        this.id = id;
        this.bootstrap = bootstrap;
        this.electionTimeoutTicks = electionTimeoutTicks;
        this.heartbeatTicks = heartbeatTicks;
    }

    private static Configuration initialVoters(int id, List<Integer> peers) {
        List<Integer> voters = new ArrayList<>(peers);
        voters.add(id);
        return Configuration.ofVoters(voters);
    }

    public RaftCore(int id, List<Integer> peers, int electionTimeoutTicks, int heartbeatTicks,
                    long currentTerm, Integer votedFor, long commitIndex, List<LogEntry> initialLog) {
        this(id, peers, electionTimeoutTicks, heartbeatTicks, currentTerm, votedFor, commitIndex,
                Snapshot.EMPTY, initialLog);
    }

    /**
     * Recovery constructor. {@code initialLog} must start right after {@code recoveredSnapshot.index()}
     * (see {@link #entriesAfter}). The driver must restore the state machine from the snapshot and
     * apply entries up to {@link #commitIndex()} itself; lastApplied starts at commitIndex.
     */
    public RaftCore(int id, List<Integer> peers, int electionTimeoutTicks, int heartbeatTicks,
                    long currentTerm, Integer votedFor, long commitIndex, Snapshot recoveredSnapshot,
                    List<LogEntry> initialLog) {
        this(id, initialVoters(id, peers), electionTimeoutTicks, heartbeatTicks, currentTerm, votedFor,
                commitIndex, recoveredSnapshot, initialLog);
    }

    public RaftCore(int id, Configuration bootstrap, int electionTimeoutTicks, int heartbeatTicks,
                    long currentTerm, Integer votedFor, long commitIndex, Snapshot recoveredSnapshot,
                    List<LogEntry> initialLog) {
        this(id, bootstrap, electionTimeoutTicks, heartbeatTicks);
        long committed = Math.max(commitIndex, recoveredSnapshot.index());
        if (currentTerm < 0 || commitIndex < 0 || committed > recoveredSnapshot.index() + initialLog.size()
                || recoveredSnapshot.term() > currentTerm) {
            throw new IllegalArgumentException("invalid recovered hard state");
        }
        for (int i = 0; i < initialLog.size(); i++) {
            LogEntry entry = initialLog.get(i);
            if (entry.index() != recoveredSnapshot.index() + i + 1 || entry.term() > currentTerm) {
                throw new IllegalArgumentException("recovered log must be contiguous after the snapshot and not from a future term");
            }
        }
        this.snapshot = recoveredSnapshot;
        this.currentTerm = currentTerm;
        this.votedFor = votedFor;
        this.commitIndex = committed;
        this.lastApplied = committed;
        this.log.addAll(initialLog);
        for (LogEntry entry : initialLog) if (entry.isConfig()) configEntries.put(entry.index(), entry.configuration());
    }

    /** Entries of a recovered WAL that follow the snapshot. A gap after the snapshot discards the rest. */
    public static List<LogEntry> entriesAfter(Snapshot snapshot, List<LogEntry> recovered) {
        List<LogEntry> suffix = new ArrayList<>();
        for (LogEntry entry : recovered) {
            if (entry.index() <= snapshot.index()) continue;
            if (entry.index() != snapshot.index() + suffix.size() + 1) break;
            suffix.add(entry);
        }
        return suffix;
    }

    /** Tuning knobs; small values make the simulator exercise batching and chunking constantly. */
    public RaftCore configure(int maxEntriesPerAppend, int snapshotChunkBytes) {
        if (maxEntriesPerAppend < 1 || snapshotChunkBytes < 1) throw new IllegalArgumentException("limits must be positive");
        this.maxEntriesPerAppend = maxEntriesPerAppend;
        this.snapshotChunkBytes = snapshotChunkBytes;
        return this;
    }

    public List<Output> tick() { return finish(tickInternal()); }
    public List<Output> propose(byte[] command) { return finish(proposeInternal(command)); }
    public List<Output> step(int from, Message message) { return finish(stepInternal(from, message)); }

    /**
     * Starts a linearizable read (Raft thesis section 6.4, ReadIndex). The leader records a read index,
     * confirms it is still leader by collecting heartbeat acks from a majority for a fresh round,
     * and emits Output.ReadReady once the state machine has applied up to that index.
     * Nothing is written to the log. Non-leaders answer Output.ReadFailed immediately.
     */
    public List<Output> readIndex(long readId) {
        if (role != NodeRole.LEADER) return List.of(new Output.ReadFailed(readId));
        // If this leader has not yet committed an entry of its own term, its commitIndex may lag
        // behind entries committed by the previous leader. Its log contains every committed entry
        // (leader completeness), so waiting for lastLogIndex (which includes the no-op) is safe.
        long index = termAt(commitIndex) == currentTerm ? commitIndex : lastLogIndex();
        readRound++;
        pendingReads.add(new PendingRead(readId, index, readRound));
        return finish(broadcastAppend());
    }

    /**
     * Log compaction (Raft section 7). The driver calls this with the state machine's snapshot taken
     * right after applying entry {@code index}. Entries up to {@code index} are dropped from memory;
     * the returned SaveSnapshot tells the driver to persist the snapshot before trimming the WAL.
     */
    public List<Output> compact(long index, byte[] stateMachineSnapshot) {
        if (index <= snapshot.index() || index > lastApplied) {
            throw new IllegalArgumentException("can only compact applied entries after the current snapshot: "
                    + index + " (snapshot " + snapshot.index() + ", applied " + lastApplied + ")");
        }
        Snapshot taken = new Snapshot(index, termAt(index), configurationAt(index), stateMachineSnapshot);
        log.subList(0, (int) (index - snapshot.index())).clear();
        configEntries.headMap(index, true).clear();
        snapshot = taken;
        return List.of(new Output.SaveSnapshot(taken));
    }

    // ---------------- membership changes (Raft thesis section 4.1, single-server) ----------------

    public enum ConfigChangeStatus { ACCEPTED, NOT_LEADER, IN_PROGRESS, NO_CHANGE, INVALID }
    public record ConfigChangeResult(ConfigChangeStatus status, List<Output> outputs) { }

    /** Adds {@code server} as a learner. The leader promotes it to voter once it has caught up. */
    public ConfigChangeResult addServer(int server) {
        ConfigChangeStatus blocked = changeBlocked();
        if (blocked != null) return new ConfigChangeResult(blocked, List.of());
        if (configuration().isMember(server)) return new ConfigChangeResult(ConfigChangeStatus.NO_CHANGE, List.of());
        // A re-added server starts from scratch on the leader's side.
        matchIndex.put(server, 0L);
        nextIndex.put(server, lastLogIndex() + 1);
        transfers.remove(server);
        transferOffset.remove(server);
        return new ConfigChangeResult(ConfigChangeStatus.ACCEPTED, finish(appendConfig(configuration().withLearner(server))));
    }

    /** Removes a voter or learner. A leader that removes itself steps down once the change commits. */
    public ConfigChangeResult removeServer(int server) {
        ConfigChangeStatus blocked = changeBlocked();
        if (blocked != null) return new ConfigChangeResult(blocked, List.of());
        Configuration current = configuration();
        if (!current.isMember(server)) return new ConfigChangeResult(ConfigChangeStatus.NO_CHANGE, List.of());
        if (current.isVoter(server) && current.voters().size() == 1) {
            return new ConfigChangeResult(ConfigChangeStatus.INVALID, List.of());
        }
        return new ConfigChangeResult(ConfigChangeStatus.ACCEPTED, finish(appendConfig(current.without(server))));
    }

    /**
     * Single-server changes are safe only one at a time: the previous change must be committed, and
     * the leader must have committed an entry of its own term (otherwise a change from an earlier
     * term that it has not seen committed could still overlap with this one).
     */
    private ConfigChangeStatus changeBlocked() {
        if (role != NodeRole.LEADER) return ConfigChangeStatus.NOT_LEADER;
        if (termAt(commitIndex) != currentTerm || latestConfigIndex() > commitIndex) return ConfigChangeStatus.IN_PROGRESS;
        return null;
    }

    /** Appends a CONFIG entry. Like every server, the leader uses it as soon as it is in its log. */
    private List<Output> appendConfig(Configuration next) {
        LogEntry entry = LogEntry.config(lastLogIndex() + 1, currentTerm, next);
        log.add(entry);
        configEntries.put(entry.index(), next);
        matchIndex.put(id, entry.index());
        List<Output> outputs = new ArrayList<>();
        outputs.add(new Output.AppendToLog(List.of(entry)));
        outputs.addAll(broadcastAppend());
        outputs.addAll(advanceCommit());
        return outputs;
    }

    /** Promote a learner whose log has caught up with the commit index. */
    private List<Output> maybePromote(int server) {
        if (!configuration().isLearner(server) || matchIndex.getOrDefault(server, 0L) < commitIndex
                || changeBlocked() != null) {
            return List.of();
        }
        return appendConfig(configuration().promote(server));
    }

    /** Leader whose removal has committed: stop leading (it is no longer a voter). */
    private void stepDownIfRemoved() {
        if (role == NodeRole.LEADER && !configuration().isVoter(id) && latestConfigIndex() <= commitIndex) {
            role = NodeRole.FOLLOWER;
            leaderId = null;
            electionElapsed = 0;
        }
    }

    /** Latest configuration in the log (committed or not): what this server uses. */
    public Configuration configuration() {
        return configEntries.isEmpty() ? snapshotConfiguration() : configEntries.lastEntry().getValue();
    }

    /** Latest configuration whose entry is committed. */
    public Configuration committedConfiguration() { return configurationAt(commitIndex); }

    private Configuration configurationAt(long index) {
        var entry = configEntries.floorEntry(index);
        return entry != null ? entry.getValue() : snapshotConfiguration();
    }

    private Configuration snapshotConfiguration() {
        return snapshot.configuration() != null ? snapshot.configuration() : bootstrap;
    }

    private long latestConfigIndex() {
        return configEntries.isEmpty() ? snapshot.index() : configEntries.lastKey();
    }

    /** Members this leader replicates to (voters and learners, without itself). */
    private List<Integer> replicationTargets() {
        return configuration().members().stream().filter(member -> member != id).toList();
    }

    private List<Integer> otherVoters() {
        return configuration().voters().stream().filter(voter -> voter != id).toList();
    }

    private List<Output> finish(List<Output> outputs) {
        if (pendingReads.isEmpty()) return outputs;
        List<Output> all = new ArrayList<>(outputs);
        all.addAll(resolveReads());
        return List.copyOf(all);
    }

    private List<Output> resolveReads() {
        List<Output> outputs = new ArrayList<>();
        if (role != NodeRole.LEADER) {
            // Leadership lost: we can no longer prove these reads are up to date.
            for (PendingRead read : pendingReads) outputs.add(new Output.ReadFailed(read.readId()));
            pendingReads.clear();
            return outputs;
        }
        var iterator = pendingReads.iterator();
        while (iterator.hasNext()) {
            PendingRead read = iterator.next();
            Set<Integer> confirmed = new HashSet<>();
            confirmed.add(id); // the leader itself (counts only if it is a voter)
            for (int peer : otherVoters()) if (ackedRound.getOrDefault(peer, 0L) >= read.readRound()) confirmed.add(peer);
            if (configuration().isQuorum(confirmed) && lastApplied >= read.readIndex()) {
                outputs.add(new Output.ReadReady(read.readId(), read.readIndex()));
                iterator.remove();
            }
        }
        return outputs;
    }

    private List<Output> tickInternal() {
        electionElapsed++;
        leaderContactElapsed++;
        if (role == NodeRole.LEADER) {
            heartbeatElapsed++;
            if (heartbeatElapsed >= heartbeatTicks) {
                heartbeatElapsed = 0;
                return broadcastAppend();
            }
            return List.of();
        }
        if (electionElapsed < electionTimeoutTicks) return List.of();
        electionElapsed = 0;
        if (leaderContactElapsed < electionTimeoutTicks) return List.of();
        if (!configuration().isVoter(id)) return List.of(); // learners and removed servers never campaign
        return beginPreVote();
    }

    private List<Output> proposeInternal(byte[] command) {
        if (role != NodeRole.LEADER) return List.of();
        LogEntry entry = new LogEntry(lastLogIndex() + 1, currentTerm, command);
        log.add(entry);
        List<Output> outputs = new ArrayList<>();
        outputs.add(new Output.AppendToLog(List.of(entry)));
        matchIndex.put(id, entry.index());
        outputs.addAll(broadcastAppend());
        outputs.addAll(advanceCommit());
        return List.copyOf(outputs);
    }

    private List<Output> stepInternal(int from, Message message) {
        // Any server may talk to us: a new server learns the leader before it is in our config.
        if (from == id) return List.of();
        if (message instanceof Message.PreVoteRequest request) return onPreVoteRequest(from, request);
        if (message instanceof Message.PreVoteReply reply) return onPreVoteReply(from, reply);
        if (message instanceof Message.VoteRequest request) return onVoteRequest(from, request);
        if (message instanceof Message.VoteReply reply) return onVoteReply(from, reply);
        if (message instanceof Message.AppendRequest request) return onAppendRequest(from, request);
        if (message instanceof Message.AppendReply reply) return onAppendReply(from, reply);
        if (message instanceof Message.InstallSnapshotRequest request) return onInstallSnapshot(from, request);
        if (message instanceof Message.InstallSnapshotReply reply) return onInstallSnapshotReply(from, reply);
        throw new IllegalArgumentException("unsupported message: " + message.getClass());
    }

    private List<Output> beginPreVote() {
        role = NodeRole.FOLLOWER;
        preVoteRound = true;
        preVotes.clear();
        preVotes.add(id);
        List<Output> outputs = new ArrayList<>();
        for (int peer : otherVoters()) {
            outputs.add(new Output.Send(peer, new Message.PreVoteRequest(currentTerm + 1, id,
                    lastLogIndex(), lastLogTerm())));
        }
        if (configuration().isQuorum(preVotes)) outputs.addAll(beginElection());
        return List.copyOf(outputs);
    }

    private List<Output> onPreVoteRequest(int from, Message.PreVoteRequest request) {
        boolean granted = role != NodeRole.LEADER
                && request.term() >= currentTerm + 1
                && leaderContactElapsed >= electionTimeoutTicks
                && candidateLogIsAtLeastAsCurrent(request.lastLogTerm(), request.lastLogIndex());
        return List.of(new Output.Send(from, new Message.PreVoteReply(currentTerm, request.term(), granted)));
    }

    private List<Output> onPreVoteReply(int from, Message.PreVoteReply reply) {
        if (!preVoteRound || reply.prospectiveTerm() != currentTerm + 1 || !configuration().isVoter(from)) {
            return List.of();
        }
        if (reply.term() > currentTerm) return stepDown(reply.term());
        if (!reply.granted()) return List.of();
        preVotes.add(from);
        if (!configuration().isQuorum(preVotes)) return List.of();
        preVoteRound = false;
        return beginElection();
    }

    private List<Output> beginElection() {
        role = NodeRole.CANDIDATE;
        currentTerm++;
        votedFor = id;
        leaderId = null;
        votes.clear();
        votes.add(id);
        electionElapsed = 0;
        List<Output> outputs = new ArrayList<>();
        outputs.add(persistHardState());
        for (int peer : otherVoters()) {
            outputs.add(new Output.Send(peer, new Message.VoteRequest(currentTerm, id,
                    lastLogIndex(), lastLogTerm())));
        }
        if (configuration().isQuorum(votes)) outputs.addAll(becomeLeader());
        return List.copyOf(outputs);
    }

    private List<Output> onVoteRequest(int from, Message.VoteRequest request) {
        if (request.term() < currentTerm) {
            return List.of(new Output.Send(from, new Message.VoteReply(currentTerm, false)));
        }
        List<Output> outputs = new ArrayList<>();
        boolean termChanged = request.term() > currentTerm;
        if (termChanged) {
            currentTerm = request.term();
            votedFor = null;
            leaderId = null;
            role = NodeRole.FOLLOWER;
            preVoteRound = false;
        }
        boolean grant = (votedFor == null || votedFor == request.candidateId())
                && candidateLogIsAtLeastAsCurrent(request.lastLogTerm(), request.lastLogIndex());
        if (grant) {
            votedFor = request.candidateId();
            electionElapsed = 0;
            leaderContactElapsed = 0;
        }
        if (termChanged || grant) outputs.add(persistHardState());
        outputs.add(new Output.Send(from, new Message.VoteReply(currentTerm, grant)));
        return List.copyOf(outputs);
    }

    private List<Output> onVoteReply(int from, Message.VoteReply reply) {
        if (reply.term() > currentTerm) return stepDown(reply.term());
        if (role != NodeRole.CANDIDATE || reply.term() != currentTerm || !reply.granted()) return List.of();
        if (!configuration().isVoter(from)) return List.of();
        votes.add(from);
        return configuration().isQuorum(votes) ? becomeLeader() : List.of();
    }

    private List<Output> becomeLeader() {
        role = NodeRole.LEADER;
        leaderId = id;
        heartbeatElapsed = 0;
        nextIndex.clear();
        matchIndex.clear();
        ackedRound.clear();
        transfers.clear();
        transferOffset.clear();
        for (int peer : replicationTargets()) {
            nextIndex.put(peer, lastLogIndex() + 1);
            matchIndex.put(peer, 0L);
        }
        // Raft section 8: commit a no-op from the new term right away. Until an entry of the current
        // term commits, the leader cannot know which earlier entries are committed, so ReadIndex
        // would have to wait for an unrelated client write.
        LogEntry noOp = new LogEntry(lastLogIndex() + 1, currentTerm, new byte[0]);
        log.add(noOp);
        matchIndex.put(id, noOp.index());
        List<Output> outputs = new ArrayList<>();
        outputs.add(new Output.AppendToLog(List.of(noOp)));
        outputs.addAll(broadcastAppend());
        outputs.addAll(advanceCommit());
        return List.copyOf(outputs);
    }

    /** Common handling for any request from a leader whose term is not stale. Returns true if the term changed. */
    private boolean acceptLeader(int from, long term) {
        boolean termChanged = term > currentTerm;
        if (termChanged) {
            currentTerm = term;
            votedFor = null;
        }
        leaderId = from;
        role = NodeRole.FOLLOWER;
        preVoteRound = false;
        electionElapsed = 0;
        leaderContactElapsed = 0;
        return termChanged;
    }

    private List<Output> onAppendRequest(int from, Message.AppendRequest request) {
        if (request.term() < currentTerm) {
            return List.of(new Output.Send(from, new Message.AppendReply(currentTerm, false,
                    0, lastLogIndex() + 1, -1, request.readRound())));
        }
        List<Output> outputs = new ArrayList<>();
        boolean termChanged = acceptLeader(from, request.term());

        long previousIndex = request.previousIndex();
        long previousTerm = request.previousTerm();
        List<LogEntry> entries = request.entries();
        if (previousIndex < snapshot.index()) {
            // Entries up to our snapshot are committed here, so they equal the leader's (Log Matching).
            // Skip them and continue as if the request started at the snapshot.
            int skip = (int) Math.min(entries.size(), snapshot.index() - previousIndex);
            entries = entries.subList(skip, entries.size());
            previousIndex = snapshot.index();
            previousTerm = snapshot.term();
        }

        long localTerm = termAt(previousIndex);
        if (localTerm < 0 || localTerm != previousTerm) {
            long conflictTerm = localTerm;
            long conflictIndex = localTerm < 0 ? lastLogIndex() + 1 : firstIndexOfTerm(localTerm);
            if (termChanged) outputs.add(persistHardState());
            outputs.add(new Output.Send(from, new Message.AppendReply(currentTerm, false, 0,
                    conflictIndex, conflictTerm, request.readRound())));
            return List.copyOf(outputs);
        }

        int appendFrom = 0;
        while (appendFrom < entries.size()) {
            LogEntry incoming = entries.get(appendFrom);
            if (incoming.index() > lastLogIndex()) break;
            if (termAt(incoming.index()) != incoming.term()) {
                if (incoming.index() <= commitIndex) {
                    throw new IllegalStateException("leader attempted to overwrite a committed entry");
                }
                truncateFrom(incoming.index());   // also reverts configurations from the removed suffix
                outputs.add(new Output.TruncateLog(incoming.index()));
                break;
            }
            appendFrom++;
        }
        if (appendFrom < entries.size()) {
            List<LogEntry> suffix = entries.subList(appendFrom, entries.size());
            log.addAll(suffix);
            for (LogEntry entry : suffix) if (entry.isConfig()) configEntries.put(entry.index(), entry.configuration());
            outputs.add(new Output.AppendToLog(suffix));
        }
        // Raft Figure 2: commitIndex = min(leaderCommit, index of last NEW entry). Entries after it
        // were not checked against the leader's log in this request, so they must not be committed.
        long lastVerified = previousIndex + entries.size();
        long newCommit = Math.min(request.leaderCommit(), lastVerified);
        if (newCommit > commitIndex) {
            commitIndex = newCommit;
            if (!termChanged) outputs.add(persistHardState());
            outputs.addAll(applyCommitted());
        }
        if (termChanged) outputs.add(0, persistHardState());
        outputs.add(new Output.Send(from, new Message.AppendReply(currentTerm, true,
                lastVerified, 0, 0, request.readRound())));
        return List.copyOf(outputs);
    }

    private List<Output> onAppendReply(int from, Message.AppendReply reply) {
        if (reply.term() > currentTerm) return stepDown(reply.term());
        if (role != NodeRole.LEADER || reply.term() != currentTerm) return List.of();
        if (!configuration().isMember(from)) return List.of();   // removed server: stop talking to it
        // Any reply in our term (success or log mismatch) proves the peer still accepted us as
        // leader when it handled the request that carried reply.readRound().
        ackedRound.merge(from, reply.readRound(), Math::max);
        if (reply.success()) {
            // Max: a delayed or duplicated older reply must never move matchIndex backwards.
            matchIndex.merge(from, reply.matchIndex(), Math::max);
            nextIndex.put(from, Math.max(nextIndex.getOrDefault(from, 1L), reply.matchIndex() + 1));
        } else {
            long next = reply.conflictIndex();
            if (reply.conflictTerm() >= 0) {
                for (long index = lastLogIndex(); index > snapshot.index(); index--) {
                    if (termAt(index) == reply.conflictTerm()) { next = index + 1; break; }
                }
            }
            // Never go below what the peer is known to have.
            nextIndex.put(from, Math.max(matchIndex.getOrDefault(from, 0L) + 1, Math.max(1, next)));
        }
        List<Output> outputs = new ArrayList<>(advanceCommit());
        if (role == NodeRole.LEADER) outputs.addAll(maybePromote(from));
        // After a rejection always retry (backtracking). After a success, send more only if the
        // follower still lacks entries: answering every reply with another (empty) request made each
        // heartbeat start a ping-pong that never ended, and every duplicated message forked another.
        boolean behind = nextIndex.getOrDefault(from, lastLogIndex() + 1) <= lastLogIndex();
        if (role == NodeRole.LEADER && configuration().isMember(from) && (!reply.success() || behind)) {
            outputs.addAll(sendAppend(from));
        }
        return List.copyOf(outputs);
    }

    // ---------------- InstallSnapshot (Raft section 7, Figure 13) ----------------

    private List<Output> onInstallSnapshot(int from, Message.InstallSnapshotRequest request) {
        if (request.term() < currentTerm) {
            return List.of(new Output.Send(from, new Message.InstallSnapshotReply(currentTerm,
                    request.lastIncludedIndex(), 0, false, request.readRound())));
        }
        List<Output> outputs = new ArrayList<>();
        if (acceptLeader(from, request.term())) outputs.add(persistHardState());

        boolean sameSnapshot = receiving != null && receivingIndex == request.lastIncludedIndex()
                && receivingTerm == request.lastIncludedTerm();
        if (request.offset() == 0) {
            receiving = new ByteArrayOutputStream();
            receivingIndex = request.lastIncludedIndex();
            receivingTerm = request.lastIncludedTerm();
        } else if (!sameSnapshot || request.offset() != receiving.size()) {
            // Out of order, duplicated, or we restarted: ask for a resend from what we have.
            long expected = sameSnapshot ? receiving.size() : 0;
            outputs.add(new Output.Send(from, new Message.InstallSnapshotReply(currentTerm,
                    request.lastIncludedIndex(), expected, false, request.readRound())));
            return List.copyOf(outputs);
        }
        receiving.writeBytes(request.data());
        if (!request.done()) {
            outputs.add(new Output.Send(from, new Message.InstallSnapshotReply(currentTerm,
                    request.lastIncludedIndex(), receiving.size(), false, request.readRound())));
            return List.copyOf(outputs);
        }

        Snapshot received = new Snapshot(receivingIndex, receivingTerm, request.configuration(), receiving.toByteArray());
        receiving = null;
        if (received.index() > commitIndex) outputs.addAll(installSnapshot(received));
        // else: we already have everything it covers; nothing to do.
        outputs.add(new Output.Send(from, new Message.InstallSnapshotReply(currentTerm,
                received.index(), received.size(), true, request.readRound())));
        return List.copyOf(outputs);
    }

    private List<Output> installSnapshot(Snapshot received) {
        List<Output> outputs = new ArrayList<>();
        if (termAt(received.index()) == received.term()) {
            // Our log contains the snapshot's last entry: keep the entries that follow it.
            log.subList(0, (int) (received.index() - snapshot.index())).clear();
            configEntries.headMap(received.index(), true).clear();
        } else {
            // Our log disagrees with (or is shorter than) the snapshot: discard all of it.
            if (lastLogIndex() > commitIndex) outputs.add(new Output.TruncateLog(commitIndex + 1));
            log.clear();
            configEntries.clear();
        }
        snapshot = received;
        commitIndex = received.index();
        lastApplied = received.index();
        outputs.add(new Output.ApplySnapshot(received));
        outputs.add(persistHardState());
        return outputs;
    }

    private List<Output> onInstallSnapshotReply(int from, Message.InstallSnapshotReply reply) {
        if (reply.term() > currentTerm) return stepDown(reply.term());
        if (role != NodeRole.LEADER || reply.term() != currentTerm) return List.of();
        if (!configuration().isMember(from)) return List.of();
        ackedRound.merge(from, reply.readRound(), Math::max);
        Snapshot pinned = transfers.get(from);
        if (pinned == null || reply.lastIncludedIndex() != pinned.index()) return List.of(); // stale reply
        if (reply.installed()) {
            transfers.remove(from);
            transferOffset.remove(from);
            matchIndex.merge(from, pinned.index(), Math::max);
            nextIndex.put(from, Math.max(nextIndex.getOrDefault(from, 1L), pinned.index() + 1));
            List<Output> outputs = new ArrayList<>(advanceCommit());
            if (role == NodeRole.LEADER) outputs.addAll(maybePromote(from));
            if (role == NodeRole.LEADER) outputs.addAll(sendAppend(from));
            return List.copyOf(outputs);
        }
        transferOffset.put(from, reply.nextOffset());
        return sendAppend(from);
    }

    private List<Output> sendSnapshotChunk(int peer) {
        Snapshot pinned = transfers.computeIfAbsent(peer, p -> snapshot);
        long offset = transferOffset.getOrDefault(peer, 0L);
        if (offset > pinned.size()) offset = 0;
        int end = (int) Math.min(pinned.size(), offset + snapshotChunkBytes);
        byte[] chunk = Arrays.copyOfRange(pinned.data(), (int) offset, end);
        return List.of(new Output.Send(peer, new Message.InstallSnapshotRequest(currentTerm, id,
                pinned.index(), pinned.term(), offset, chunk, end == pinned.size(), readRound, pinned.configuration())));
    }

    // ---------------- commit / apply / replication ----------------

    private List<Output> advanceCommit() {
        for (long candidate = lastLogIndex(); candidate > commitIndex; candidate--) {
            if (termAt(candidate) != currentTerm) continue;
            // Voters of the current configuration that store the entry. The leader counts only if
            // it is still a voter (a leader removing itself does not count itself).
            Set<Integer> replicas = new HashSet<>();
            replicas.add(id);
            for (int peer : otherVoters()) if (matchIndex.getOrDefault(peer, 0L) >= candidate) replicas.add(peer);
            if (configuration().isQuorum(replicas)) {
                commitIndex = candidate;
                List<Output> outputs = new ArrayList<>();
                outputs.add(persistHardState());
                outputs.addAll(applyCommitted());
                stepDownIfRemoved();
                return List.copyOf(outputs);
            }
        }
        return List.of();
    }

    private List<Output> applyCommitted() {
        if (commitIndex <= lastApplied) return List.of();
        List<LogEntry> committed = new ArrayList<>();
        while (lastApplied < commitIndex) committed.add(entryAt(++lastApplied));
        return List.of(new Output.ApplyEntries(committed));
    }

    private List<Output> broadcastAppend() {
        List<Output> outputs = new ArrayList<>();
        for (int peer : replicationTargets()) outputs.addAll(sendAppend(peer));
        return List.copyOf(outputs);
    }

    private List<Output> sendAppend(int peer) {
        long next = nextIndex.getOrDefault(peer, lastLogIndex() + 1);
        // The entries this peer needs were compacted (or a transfer is under way): send the snapshot.
        if (next <= snapshot.index() || transfers.containsKey(peer)) return sendSnapshotChunk(peer);
        long previous = next - 1;
        long last = Math.min(lastLogIndex(), previous + maxEntriesPerAppend);
        List<LogEntry> batch = next <= last
                ? List.copyOf(log.subList(offsetOf(next), offsetOf(last) + 1)) : List.of();
        return List.of(new Output.Send(peer, new Message.AppendRequest(currentTerm, id,
                previous, termAt(previous), batch, commitIndex, readRound)));
    }

    private List<Output> stepDown(long newTerm) {
        currentTerm = newTerm;
        votedFor = null;
        leaderId = null;
        role = NodeRole.FOLLOWER;
        preVoteRound = false;
        return List.of(persistHardState());
    }

    // ---------------- offset-log helpers ----------------

    private int offsetOf(long index) { return (int) (index - snapshot.index() - 1); }
    private LogEntry entryAt(long index) { return log.get(offsetOf(index)); }
    private void truncateFrom(long index) {
        while (lastLogIndex() >= index) log.remove(log.size() - 1);
        configEntries.tailMap(index, true).clear();
    }
    private Output.PersistHardState persistHardState() { return new Output.PersistHardState(currentTerm, votedFor, commitIndex); }
    private boolean candidateLogIsAtLeastAsCurrent(long term, long index) {
        return term > lastLogTerm() || (term == lastLogTerm() && index >= lastLogIndex());
    }
    /** Term of the entry at {@code index}; the snapshot's term at its index; -1 if unknown or compacted. */
    private long termAt(long index) {
        if (index == snapshot.index()) return snapshot.term();
        if (index < snapshot.index() || index > lastLogIndex()) return -1;
        return entryAt(index).term();
    }
    private long firstIndexOfTerm(long term) {
        for (LogEntry entry : log) if (entry.term() == term) return entry.index();
        return snapshot.index() + 1;
    }
    private long lastLogIndex() { return snapshot.index() + log.size(); }
    private long lastLogTerm() { return log.isEmpty() ? snapshot.term() : log.get(log.size() - 1).term(); }

    public NodeRole role() { return role; }
    public long currentTerm() { return currentTerm; }
    public Integer votedFor() { return votedFor; }
    public Integer leaderId() { return leaderId; }
    public int id() { return id; }
    public long commitIndex() { return commitIndex; }
    public long lastApplied() { return lastApplied; }
    public long lastIndex() { return lastLogIndex(); }
    public Snapshot snapshot() { return snapshot; }
    /** Number of entries still held in memory (not yet compacted). */
    public int logSize() { return log.size(); }
    /** Entries after the snapshot (the in-memory log). */
    public List<LogEntry> logEntries() { return List.copyOf(log); }
}
