package jraft.raftcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RaftCoreSnapshotTest {
    private static final byte[] STATE = "state-machine-bytes-0123456789".getBytes();

    /** Leader 1 with entries 1..n committed (no-op at 1) and acknowledged by peer 2. */
    private static RaftCore leaderWithCommitted(int commands) {
        RaftCore node = new RaftCore(1, List.of(2, 3), 3, 1).configure(4, 8);
        node.tick(); node.tick(); node.tick();
        node.step(2, new Message.PreVoteReply(0, 1, true));
        node.step(2, new Message.VoteReply(1, true));
        for (int i = 0; i < commands; i++) node.propose(new byte[]{(byte) i});
        node.step(2, new Message.AppendReply(1, true, node.lastIndex(), 0, 0));
        return node;
    }

    @Test
    void compactionDropsThePrefixAndKeepsIndexesStable() {
        RaftCore node = leaderWithCommitted(9);           // log 1..10
        List<Output> outputs = node.compact(6, STATE);

        assertThat(outputs).containsExactly(new Output.SaveSnapshot(new Snapshot(6, 1, STATE)));
        assertThat(node.snapshot().index()).isEqualTo(6);
        assertThat(node.logSize()).isEqualTo(4);
        assertThat(node.lastIndex()).isEqualTo(10);
        assertThat(node.logEntries().getFirst().index()).isEqualTo(7);
    }

    @Test
    void cannotCompactUnappliedEntries() {
        RaftCore node = leaderWithCommitted(2);
        node.propose(new byte[]{9});                      // index 4, not committed
        assertThatThrownBy(() -> node.compact(4, STATE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laggingFollowerReceivesTheSnapshotInChunksThenEntries() {
        RaftCore leader = leaderWithCommitted(9);
        leader.compact(8, STATE);
        // Peer 3 has nothing: its next index (11) fails, it reports an empty log.
        List<Output> retry = leader.step(3, new Message.AppendReply(1, false, 0, 1, -1));
        Message.InstallSnapshotRequest first = onlyMessage(retry, Message.InstallSnapshotRequest.class);
        assertThat(first.offset()).isZero();
        assertThat(first.lastIncludedIndex()).isEqualTo(8);
        assertThat(first.data()).hasSize(8);              // chunk size configured to 8 bytes
        assertThat(first.done()).isFalse();

        // Feed the chunks to a real follower and relay its replies back.
        RaftCore follower = new RaftCore(3, List.of(1, 2), 3, 1);
        Message toFollower = first;
        List<Output> installed = List.of();
        for (int round = 0; round < 20; round++) {
            List<Output> followerOutputs = follower.step(1, toFollower);
            Message.InstallSnapshotReply reply = onlyMessage(followerOutputs, Message.InstallSnapshotReply.class);
            if (reply.installed()) { installed = followerOutputs; }
            List<Output> leaderOutputs = leader.step(3, reply);
            if (reply.installed()) {
                // After the snapshot the leader continues with normal entries from index 9.
                Message.AppendRequest append = onlyMessage(leaderOutputs, Message.AppendRequest.class);
                assertThat(append.previousIndex()).isEqualTo(8);
                assertThat(append.entries()).extracting(LogEntry::index).containsExactly(9L, 10L);
                break;
            }
            toFollower = onlyMessage(leaderOutputs, Message.InstallSnapshotRequest.class);
        }
        assertThat(installed).contains(new Output.ApplySnapshot(new Snapshot(8, 1, STATE)));
        assertThat(follower.snapshot().index()).isEqualTo(8);
        assertThat(follower.commitIndex()).isEqualTo(8);
        assertThat(follower.lastApplied()).isEqualTo(8);
        // ApplySnapshot (durable) precedes the reply that tells the leader it is installed.
        assertThat(indexOf(installed, Output.ApplySnapshot.class)).isLessThan(indexOf(installed, Output.Send.class));
    }

    @Test
    void outOfOrderChunkAsksForResendFromWhatWasReceived() {
        RaftCore follower = new RaftCore(3, List.of(1, 2), 3, 1);
        follower.step(1, chunk(8, 0, "abcd", false));
        List<Output> skipped = follower.step(1, chunk(8, 8, "ijkl", false));
        assertThat(onlyMessage(skipped, Message.InstallSnapshotReply.class).nextOffset()).isEqualTo(4);
        List<Output> duplicate = follower.step(1, chunk(8, 0, "abcd", false)); // restart from 0 is allowed
        assertThat(onlyMessage(duplicate, Message.InstallSnapshotReply.class).nextOffset()).isEqualTo(4);
    }

    @Test
    void snapshotReplacesAConflictingLogEntirely() {
        RaftCore follower = new RaftCore(3, List.of(1, 2), 3, 1);
        // Follower has uncommitted entries 1..3 from term 1.
        follower.step(2, new Message.AppendRequest(1, 2, 0, 0, entries(1, 3, 1), 0));
        List<Output> outputs = follower.step(1, chunk(2, 5, 0, "snap", true));

        assertThat(outputs).contains(new Output.TruncateLog(1));
        assertThat(indexOf(outputs, Output.TruncateLog.class)).isLessThan(indexOf(outputs, Output.ApplySnapshot.class));
        assertThat(follower.logSize()).isZero();
        assertThat(follower.lastIndex()).isEqualTo(5);
        assertThat(follower.commitIndex()).isEqualTo(5);
    }

    @Test
    void snapshotKeepsTheMatchingLogSuffix() {
        RaftCore follower = new RaftCore(3, List.of(1, 2), 3, 1);
        follower.step(1, new Message.AppendRequest(1, 1, 0, 0, entries(1, 6, 1), 2));
        List<Output> outputs = follower.step(1, chunk(1, 4, 0, "snap", true));

        assertThat(outputs).noneMatch(Output.TruncateLog.class::isInstance);
        assertThat(follower.snapshot().index()).isEqualTo(4);
        assertThat(follower.logEntries()).extracting(LogEntry::index).containsExactly(5L, 6L);
        assertThat(follower.commitIndex()).isEqualTo(4);
    }

    @Test
    void snapshotOlderThanCommitIndexIsIgnored() {
        RaftCore follower = new RaftCore(3, List.of(1, 2), 3, 1);
        follower.step(1, new Message.AppendRequest(1, 1, 0, 0, entries(1, 6, 1), 6));
        List<Output> outputs = follower.step(1, chunk(1, 4, 0, "snap", true));

        assertThat(outputs).noneMatch(Output.ApplySnapshot.class::isInstance);
        assertThat(onlyMessage(outputs, Message.InstallSnapshotReply.class).installed()).isTrue();
        assertThat(follower.lastIndex()).isEqualTo(6);
    }

    @Test
    void appendEntriesThatOverlapTheSnapshotAreAccepted() {
        RaftCore follower = new RaftCore(3, List.of(1, 2), 3, 1);
        follower.step(1, chunk(1, 4, 0, "snap", true));
        // Leader still thinks the follower is at 2: entries 3..6, previous index 2 (compacted here).
        List<Output> outputs = follower.step(1, new Message.AppendRequest(1, 1, 2, 1, entries(3, 6, 1), 6));

        Message.AppendReply reply = onlyMessage(outputs, Message.AppendReply.class);
        assertThat(reply.success()).isTrue();
        assertThat(reply.matchIndex()).isEqualTo(6);
        assertThat(follower.logEntries()).extracting(LogEntry::index).containsExactly(5L, 6L);
        assertThat(follower.commitIndex()).isEqualTo(6);
    }

    @Test
    void recoveryConstructorResumesAfterTheSnapshot() {
        Snapshot snapshot = new Snapshot(10, 2, STATE);
        RaftCore node = new RaftCore(1, List.of(2, 3), 3, 1, 3, 1, 4, snapshot, entries(11, 12, 3));

        assertThat(node.commitIndex()).isEqualTo(10);    // at least the snapshot
        assertThat(node.lastApplied()).isEqualTo(10);
        assertThat(node.lastIndex()).isEqualTo(12);
        assertThat(RaftCore.entriesAfter(snapshot, entries(5, 13, 3)))
                .extracting(LogEntry::index).containsExactly(11L, 12L, 13L);
        assertThat(RaftCore.entriesAfter(snapshot, entries(1, 8, 3))).isEmpty();
    }

    @Test
    void followerCommitsOnlyEntriesVerifiedByThisRequest() {
        // Regression: commitIndex used min(leaderCommit, lastLogIndex). With batched AppendEntries a
        // stale, unverified suffix could be committed. Raft uses the index of the last NEW entry.
        RaftCore follower = new RaftCore(3, List.of(1, 2), 3, 1);
        follower.step(2, new Message.AppendRequest(1, 2, 0, 0, entries(1, 3, 1), 0)); // stale term-1 suffix
        follower.step(1, new Message.AppendRequest(2, 1, 0, 0, entries(1, 1, 1), 3));  // verifies only index 1

        assertThat(follower.commitIndex()).isEqualTo(1);
    }

    @Test
    void largeCatchUpIsSentInBoundedBatches() {
        RaftCore leader = leaderWithCommitted(20);
        List<Output> outputs = leader.step(3, new Message.AppendReply(1, false, 0, 1, -1));
        assertThat(onlyMessage(outputs, Message.AppendRequest.class).entries()).hasSize(4);
    }

    // ---------------- helpers ----------------

    private static Message.InstallSnapshotRequest chunk(long index, long offset, String data, boolean done) {
        return chunk(1, index, offset, data, done);
    }

    private static Message.InstallSnapshotRequest chunk(long term, long index, long offset, String data, boolean done) {
        return new Message.InstallSnapshotRequest(term, 1, index, 1, offset, data.getBytes(), done, 0);
    }

    private static List<LogEntry> entries(long from, long to, long term) {
        List<LogEntry> list = new ArrayList<>();
        for (long i = from; i <= to; i++) list.add(new LogEntry(i, term, new byte[]{(byte) i}));
        return list;
    }

    private static <T extends Message> T onlyMessage(List<Output> outputs, Class<T> type) {
        List<T> found = outputs.stream().filter(Output.Send.class::isInstance).map(Output.Send.class::cast)
                .map(Output.Send::message).filter(type::isInstance).map(type::cast).toList();
        assertThat(found).as("exactly one %s in %s", type.getSimpleName(), outputs).hasSize(1);
        return found.getFirst();
    }

    private static int indexOf(List<Output> outputs, Class<?> type) {
        for (int i = 0; i < outputs.size(); i++) if (type.isInstance(outputs.get(i))) return i;
        return -1;
    }
}
