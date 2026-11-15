package jraft.sim;

/** Client -> server. (clientId, sequence) identifies the operation across retries (ARCHITECTURE.md section 3). */
public record ClientRequest(long clientId, long sequence, KvCommand.Operation operation, String key, String value) {
    public KvCommand toCommand() { return new KvCommand(clientId, sequence, operation, key, value); }
}
