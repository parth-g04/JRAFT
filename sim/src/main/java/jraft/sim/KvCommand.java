package jraft.sim;

public record KvCommand(long clientId, long sequence, Operation operation, String key, String value) {
    public enum Operation { GET, PUT, APPEND, DELETE }
}
