package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;

import jraft.raftcore.LogEntry;
import org.junit.jupiter.api.Test;

class KvStateMachineTest {
    @Test
    void duplicatedAppendCommittedTwiceIsAppliedOnce() {
        // A client retried the same APPEND (same clientId/sequence) and both copies were committed,
        // e.g. once by the old leader and once by the new one.
        KvStateMachine machine = new KvStateMachine();
        byte[] append = KvCommandCodec.encode(new KvCommand(7, 1, KvCommand.Operation.APPEND, "x", "a;"));
        machine.apply(new LogEntry(1, 1, append));
        machine.apply(new LogEntry(2, 1, new byte[0]));    // leader no-op is ignored
        machine.apply(new LogEntry(3, 2, append));

        assertThat(machine.snapshotView()).containsEntry("x", "a;");
        assertThat(machine.cachedResult(7, 1)).isEqualTo(new KvStateMachine.Result(null, true));
    }

    @Test
    void olderSequenceIsIgnoredAfterClientMovedOn() {
        KvStateMachine machine = new KvStateMachine();
        machine.apply(entry(1, 7, 1, KvCommand.Operation.PUT, "x", "one"));
        machine.apply(entry(2, 7, 2, KvCommand.Operation.PUT, "x", "two"));
        machine.apply(entry(3, 7, 1, KvCommand.Operation.PUT, "x", "one")); // late duplicate of seq 1

        assertThat(machine.snapshotView()).containsEntry("x", "two");
        assertThat(machine.alreadyApplied(7, 1)).isTrue();
        assertThat(machine.cachedResult(7, 1)).isNull();   // only the latest reply is cached
        assertThat(machine.cachedResult(7, 2)).isNotNull();
    }

    @Test
    void sessionsAreIndependentPerClientAndRebuiltByReplay() {
        LogEntry[] log = {
                entry(1, 1, 1, KvCommand.Operation.APPEND, "x", "a"),
                entry(2, 2, 1, KvCommand.Operation.APPEND, "x", "b"),
                entry(3, 1, 1, KvCommand.Operation.APPEND, "x", "a"),
                entry(4, 2, 2, KvCommand.Operation.APPEND, "x", "c"),
        };
        KvStateMachine first = new KvStateMachine();
        KvStateMachine replayed = new KvStateMachine();
        for (LogEntry entry : log) first.apply(entry);
        for (LogEntry entry : log) replayed.apply(entry);

        assertThat(first.snapshotView()).containsEntry("x", "abc");
        assertThat(replayed.snapshotView()).isEqualTo(first.snapshotView());
        assertThat(replayed.sessionsView()).isEqualTo(first.sessionsView());
    }

    private static LogEntry entry(long index, long client, long sequence, KvCommand.Operation op, String key, String value) {
        return new LogEntry(index, 1, KvCommandCodec.encode(new KvCommand(client, sequence, op, key, value)));
    }
}
