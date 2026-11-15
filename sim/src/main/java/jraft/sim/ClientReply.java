package jraft.sim;

/** Server -> client. {@code leaderHint} is set on WRONG_LEADER when the server knows the leader. */
public record ClientReply(long clientId, long sequence, Status status, String value, Integer leaderHint) {
    public enum Status { OK, NO_KEY, WRONG_LEADER }
}
