package jraft.sim;

import jraft.raftcore.LogEntry;

import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic single-group key/value state machine with client sessions (exactly-once writes).
 *
 * Each client has at most one outstanding request and numbers them 1, 2, 3, ... A client may
 * retry a request many times (to the same or a different leader), so the same command can be
 * committed to the log more than once. The session table remembers the last sequence applied per
 * client and its reply: a repeated sequence is not applied again and gets the cached reply.
 *
 * The session table is replicated state (it is rebuilt by replaying the log), so every replica
 * makes the same duplicate decisions. TreeMaps keep iteration deterministic.
 */
public final class KvStateMachine {
    public record Result(String value, boolean found) { }
    /** Last applied sequence for a client and the reply it produced. */
    public record Session(long lastSequence, Result lastResult) { }

    private final Map<String, String> values = new TreeMap<>();
    private final Map<Long, Session> sessions = new TreeMap<>();

    public void apply(LogEntry entry) {
        if (entry.command().length == 0) return; // leader no-op
        KvCommand command = KvCommandCodec.decode(entry.command());
        Session session = sessions.get(command.clientId());
        if (session != null && command.sequence() <= session.lastSequence()) return; // duplicate
        Result result = switch (command.operation()) {
            case GET -> read(command.key()); // GETs normally use ReadIndex and are not logged
            case PUT -> {
                values.put(command.key(), command.value());
                yield new Result(null, true);
            }
            case APPEND -> {
                values.merge(command.key(), command.value(), String::concat);
                yield new Result(null, true);
            }
            case DELETE -> {
                boolean found = values.containsKey(command.key());
                yield new Result(values.remove(command.key()), found);
            }
        };
        sessions.put(command.clientId(), new Session(command.sequence(), result));
    }

    /** Cached reply if {@code sequence} is the client's most recently applied request, else null. */
    public Result cachedResult(long clientId, long sequence) {
        Session session = sessions.get(clientId);
        return session != null && session.lastSequence() == sequence ? session.lastResult() : null;
    }

    /** True when this or a later request of the client has already been applied. */
    public boolean alreadyApplied(long clientId, long sequence) {
        Session session = sessions.get(clientId);
        return session != null && sequence <= session.lastSequence();
    }

    public Result read(String key) { return new Result(values.get(key), values.containsKey(key)); }
    public Map<String, String> snapshotView() { return Map.copyOf(values); }
    public Map<Long, Session> sessionsView() { return Map.copyOf(sessions); }
}
