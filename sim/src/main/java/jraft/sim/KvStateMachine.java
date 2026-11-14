package jraft.sim;

import jraft.raftcore.LogEntry;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic single-group key/value state machine used by the simulator. */
public final class KvStateMachine {
    public record RequestId(long clientId, long sequence) { }
    public record Result(String value, boolean found) { }

    private final Map<String, String> values = new TreeMap<>();
    private final Map<RequestId, Result> results = new HashMap<>();

    public void apply(LogEntry entry) {
        KvCommand command = KvCommandCodec.decode(entry.command());
        RequestId id = new RequestId(command.clientId(), command.sequence());
        if (results.containsKey(id)) return;
        Result result;
        switch (command.operation()) {
            case GET -> result = new Result(values.get(command.key()), values.containsKey(command.key()));
            case PUT -> {
                values.put(command.key(), command.value());
                result = new Result(null, true);
            }
            case APPEND -> {
                values.merge(command.key(), command.value(), String::concat);
                result = new Result(null, true);
            }
            case DELETE -> {
                boolean found = values.containsKey(command.key());
                result = new Result(values.remove(command.key()), found);
            }
            default -> throw new IllegalStateException("unhandled operation");
        }
        results.put(id, result);
    }

    public Result result(long clientId, long sequence) { return results.get(new RequestId(clientId, sequence)); }
    public Map<String, String> snapshotView() { return Map.copyOf(values); }
}
