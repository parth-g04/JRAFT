package jraft.raftcore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic Raft protocol state. It owns no threads, I/O, or clock. */
public final class RaftCore {
    private final int id;
    private final List<Integer> peers;
    private final int electionTimeoutTicks;
    private final int heartbeatTicks;
    private final List<LogEntry> log = new ArrayList<>();
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

    public RaftCore(int id, List<Integer> peers, int electionTimeoutTicks, int heartbeatTicks) {
        if (electionTimeoutTicks < 1 || heartbeatTicks < 1 || heartbeatTicks >= electionTimeoutTicks) {
            throw new IllegalArgumentException("timeouts must be positive and heartbeat shorter than election timeout");
        }
        this.id = id;
        this.peers = peers.stream().filter(peer -> peer != id).distinct().sorted().toList();
        this.electionTimeoutTicks = electionTimeoutTicks;
        this.heartbeatTicks = heartbeatTicks;
    }

    public RaftCore(int id, List<Integer> peers, int electionTimeoutTicks, int heartbeatTicks,
                    long currentTerm, Integer votedFor, long commitIndex, List<LogEntry> initialLog) {
        this(id, peers, electionTimeoutTicks, heartbeatTicks);
        if (currentTerm < 0 || commitIndex < 0 || commitIndex > initialLog.size()) {
            throw new IllegalArgumentException("invalid recovered hard state");
        }
        for (int index = 0; index < initialLog.size(); index++) {
            LogEntry entry = initialLog.get(index);
            if (entry.index() != index + 1L || entry.term() > currentTerm) {
                throw new IllegalArgumentException("recovered log must be contiguous and not from a future term");
            }
        }
        this.currentTerm = currentTerm;
        this.votedFor = votedFor;
        this.commitIndex = commitIndex;
        this.lastApplied = commitIndex;
        this.log.addAll(initialLog);
    }

    public List<Output> tick() {
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
        return beginPreVote();
    }

    public List<Output> propose(byte[] command) {
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

    public List<Output> step(int from, Message message) {
        if (!peers.contains(from)) return List.of();
        if (message instanceof Message.PreVoteRequest request) return onPreVoteRequest(from, request);
        if (message instanceof Message.PreVoteReply reply) return onPreVoteReply(from, reply);
        if (message instanceof Message.VoteRequest request) return onVoteRequest(from, request);
        if (message instanceof Message.VoteReply reply) return onVoteReply(from, reply);
        if (message instanceof Message.AppendRequest request) return onAppendRequest(from, request);
        if (message instanceof Message.AppendReply reply) return onAppendReply(from, reply);
        throw new IllegalArgumentException("unsupported message: " + message.getClass());
    }

    private List<Output> beginPreVote() {
        role = NodeRole.FOLLOWER;
        preVoteRound = true;
        preVotes.clear();
        preVotes.add(id);
        List<Output> outputs = new ArrayList<>();
        for (int peer : peers) {
            outputs.add(new Output.Send(peer, new Message.PreVoteRequest(currentTerm + 1, id,
                    lastLogIndex(), lastLogTerm())));
        }
        if (hasMajority(preVotes.size())) outputs.addAll(beginElection());
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
        if (!preVoteRound || reply.prospectiveTerm() != currentTerm + 1 || !peers.contains(from)) {
            return List.of();
        }
        if (reply.term() > currentTerm) return stepDown(reply.term());
        if (!reply.granted()) return List.of();
        preVotes.add(from);
        if (!hasMajority(preVotes.size())) return List.of();
        preVoteRound = false;
        return beginElection();
    }

    private List<Output> beginElection() {
        role = NodeRole.CANDIDATE;
        currentTerm++;
        votedFor = id;
        votes.clear();
        votes.add(id);
        electionElapsed = 0;
        List<Output> outputs = new ArrayList<>();
        outputs.add(persistHardState());
        for (int peer : peers) {
            outputs.add(new Output.Send(peer, new Message.VoteRequest(currentTerm, id,
                    lastLogIndex(), lastLogTerm())));
        }
        if (hasMajority(votes.size())) outputs.addAll(becomeLeader());
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
        votes.add(from);
        return hasMajority(votes.size()) ? becomeLeader() : List.of();
    }

    private List<Output> becomeLeader() {
        role = NodeRole.LEADER;
        heartbeatElapsed = 0;
        nextIndex.clear();
        matchIndex.clear();
        for (int peer : peers) {
            nextIndex.put(peer, lastLogIndex() + 1);
            matchIndex.put(peer, 0L);
        }
        matchIndex.put(id, lastLogIndex());
        return broadcastAppend();
    }

    private List<Output> onAppendRequest(int from, Message.AppendRequest request) {
        if (request.term() < currentTerm) {
            return List.of(new Output.Send(from, new Message.AppendReply(currentTerm, false,
                    0, lastLogIndex() + 1, -1)));
        }
        List<Output> outputs = new ArrayList<>();
        boolean termChanged = request.term() > currentTerm;
        if (termChanged) {
            currentTerm = request.term();
            votedFor = null;
        }
        role = NodeRole.FOLLOWER;
        preVoteRound = false;
        electionElapsed = 0;
        leaderContactElapsed = 0;
        long localTerm = termAt(request.previousIndex());
        if (localTerm < 0 || localTerm != request.previousTerm()) {
            long conflictTerm = localTerm;
            long conflictIndex = localTerm < 0 ? lastLogIndex() + 1 : firstIndexOfTerm(localTerm);
            if (termChanged) outputs.add(persistHardState());
            outputs.add(new Output.Send(from, new Message.AppendReply(currentTerm, false, 0,
                    conflictIndex, conflictTerm)));
            return List.copyOf(outputs);
        }

        int appendFrom = 0;
        while (appendFrom < request.entries().size()) {
            LogEntry incoming = request.entries().get(appendFrom);
            if (incoming.index() > lastLogIndex()) break;
            if (termAt(incoming.index()) != incoming.term()) {
                if (incoming.index() <= commitIndex) {
                    throw new IllegalStateException("leader attempted to overwrite a committed entry");
                }
                truncateFrom(incoming.index());
                outputs.add(new Output.TruncateLog(incoming.index()));
                break;
            }
            appendFrom++;
        }
        if (appendFrom < request.entries().size()) {
            List<LogEntry> suffix = request.entries().subList(appendFrom, request.entries().size());
            log.addAll(suffix);
            outputs.add(new Output.AppendToLog(suffix));
        }
        long newCommit = Math.min(request.leaderCommit(), lastLogIndex());
        if (newCommit > commitIndex) {
            commitIndex = newCommit;
            if (!termChanged) outputs.add(persistHardState());
            outputs.addAll(applyCommitted());
        }
        if (termChanged) outputs.add(0, persistHardState());
        outputs.add(new Output.Send(from, new Message.AppendReply(currentTerm, true,
                request.previousIndex() + request.entries().size(), 0, 0)));
        return List.copyOf(outputs);
    }

    private List<Output> onAppendReply(int from, Message.AppendReply reply) {
        if (reply.term() > currentTerm) return stepDown(reply.term());
        if (role != NodeRole.LEADER || reply.term() != currentTerm) return List.of();
        if (reply.success()) {
            matchIndex.put(from, reply.matchIndex());
            nextIndex.put(from, reply.matchIndex() + 1);
        } else {
            long next = reply.conflictIndex();
            if (reply.conflictTerm() >= 0) {
                for (long index = lastLogIndex(); index >= 1; index--) {
                    if (termAt(index) == reply.conflictTerm()) { next = index + 1; break; }
                }
            }
            nextIndex.put(from, Math.max(1, next));
        }
        List<Output> outputs = new ArrayList<>(advanceCommit());
        outputs.addAll(sendAppend(from));
        return List.copyOf(outputs);
    }

    private List<Output> advanceCommit() {
        for (long candidate = lastLogIndex(); candidate > commitIndex; candidate--) {
            if (termAt(candidate) != currentTerm) continue;
            int replicas = 1;
            for (int peer : peers) if (matchIndex.getOrDefault(peer, 0L) >= candidate) replicas++;
            if (hasMajority(replicas)) {
                commitIndex = candidate;
                List<Output> outputs = new ArrayList<>();
                outputs.add(persistHardState());
                outputs.addAll(applyCommitted());
                return List.copyOf(outputs);
            }
        }
        return List.of();
    }

    private List<Output> applyCommitted() {
        if (commitIndex <= lastApplied) return List.of();
        List<LogEntry> committed = new ArrayList<>();
        while (lastApplied < commitIndex) committed.add(log.get((int) lastApplied++));
        return List.of(new Output.ApplyEntries(committed));
    }

    private List<Output> broadcastAppend() {
        List<Output> outputs = new ArrayList<>();
        for (int peer : peers) outputs.addAll(sendAppend(peer));
        return List.copyOf(outputs);
    }

    private List<Output> sendAppend(int peer) {
        long next = nextIndex.getOrDefault(peer, lastLogIndex() + 1);
        long previous = next - 1;
        List<LogEntry> suffix = next <= lastLogIndex()
                ? List.copyOf(log.subList((int) next - 1, log.size())) : List.of();
        return List.of(new Output.Send(peer, new Message.AppendRequest(currentTerm, id,
                previous, termAt(previous), suffix, commitIndex)));
    }

    private List<Output> stepDown(long newTerm) {
        currentTerm = newTerm;
        votedFor = null;
        role = NodeRole.FOLLOWER;
        preVoteRound = false;
        return List.of(persistHardState());
    }

    private void truncateFrom(long index) { while (lastLogIndex() >= index) log.remove(log.size() - 1); }
    private Output.PersistHardState persistHardState() { return new Output.PersistHardState(currentTerm, votedFor, commitIndex); }
    private boolean candidateLogIsAtLeastAsCurrent(long term, long index) {
        return term > lastLogTerm() || (term == lastLogTerm() && index >= lastLogIndex());
    }
    private long termAt(long index) { return index == 0 ? 0 : index < 0 || index > lastLogIndex() ? -1 : log.get((int) index - 1).term(); }
    private long firstIndexOfTerm(long term) { for (LogEntry entry : log) if (entry.term() == term) return entry.index(); return lastLogIndex() + 1; }
    private long lastLogIndex() { return log.size(); }
    private long lastLogTerm() { return log.isEmpty() ? 0 : log.get(log.size() - 1).term(); }
    private boolean hasMajority(int count) { return count >= (peers.size() + 1) / 2 + 1; }

    public NodeRole role() { return role; }
    public long currentTerm() { return currentTerm; }
    public Integer votedFor() { return votedFor; }
    public long commitIndex() { return commitIndex; }
    public long lastApplied() { return lastApplied; }
    public List<LogEntry> logEntries() { return List.copyOf(log); }
}
