package jraft.shardctrler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.TreeMap;
import jraft.raftcore.LogEntry;
import org.junit.jupiter.api.Test;

class ControllerStateMachineTest {
    private long index;

    private LogEntry entry(ControllerCommand command) {
        return new LogEntry(++index, 1, ControllerStateMachine.encode(command));
    }

    private static TreeMap<Integer, List<Integer>> group(int gid, Integer... servers) {
        TreeMap<Integer, List<Integer>> groups = new TreeMap<>();
        groups.put(gid, List.of(servers));
        return groups;
    }

    @Test
    void joinLeaveAndMoveProduceNumberedConfigurations() {
        ControllerStateMachine controller = new ControllerStateMachine();
        controller.apply(entry(new ControllerCommand.Join(1, 1, group(100, 11, 12, 13))));
        controller.apply(entry(new ControllerCommand.Join(1, 2, group(101, 21, 22, 23))));
        controller.apply(entry(new ControllerCommand.Move(1, 3, 0, 101)));
        controller.apply(entry(new ControllerCommand.Leave(1, 4, List.of(100))));

        assertThat(controller.latest().num()).isEqualTo(4);
        assertThat(controller.query(1).shards()).containsOnly(100);
        assertThat(controller.query(2).shardsOf(100)).hasSize(8);
        assertThat(controller.query(3).owner(0)).isEqualTo(101);
        assertThat(controller.query(4).shards()).containsOnly(101);
        assertThat(controller.query(4).groups()).containsOnlyKeys(101);
        assertThat(controller.query(-1)).isEqualTo(controller.latest());
        assertThat(controller.query(99)).isEqualTo(controller.latest());
        assertThat(controller.query(0)).isEqualTo(ShardConfig.INITIAL);
    }

    @Test
    void duplicatedJoinCreatesOnlyOneConfiguration() {
        ControllerStateMachine controller = new ControllerStateMachine();
        ControllerCommand join = new ControllerCommand.Join(7, 1, group(100, 11));
        controller.apply(entry(join));
        controller.apply(entry(join));       // the admin retried through a new leader
        assertThat(controller.latest().num()).isEqualTo(1);
        assertThat(controller.applied(7, 1)).isTrue();
    }

    @Test
    void moveToUnknownGroupIsIgnored() {
        ControllerStateMachine controller = new ControllerStateMachine();
        controller.apply(entry(new ControllerCommand.Join(1, 1, group(100, 11))));
        controller.apply(entry(new ControllerCommand.Move(1, 2, 3, 555)));
        assertThat(controller.latest().num()).isEqualTo(1);
    }

    @Test
    void snapshotRoundTripsAllConfigurationsAndSessions() {
        ControllerStateMachine controller = new ControllerStateMachine();
        controller.apply(entry(new ControllerCommand.Join(1, 1, group(100, 11, 12))));
        controller.apply(entry(new ControllerCommand.Join(2, 1, group(101, 21))));
        ControllerStateMachine restored = new ControllerStateMachine();
        restored.restore(controller.snapshot());

        assertThat(restored.query(1)).isEqualTo(controller.query(1));
        assertThat(restored.latest()).isEqualTo(controller.latest());
        assertThat(restored.applied(2, 1)).isTrue();
        assertThat(restored.snapshot()).isEqualTo(controller.snapshot());
    }

    @Test
    void commandsRoundTripThroughTheirEncoding() {
        ControllerCommand join = new ControllerCommand.Join(5, 9, group(100, 1, 2, 3));
        assertThat(ControllerCommand.decode(ControllerCommand.encode(join))).isEqualTo(join);
        ControllerCommand leave = new ControllerCommand.Leave(5, 10, List.of(100, 101));
        assertThat(ControllerCommand.decode(ControllerCommand.encode(leave))).isEqualTo(leave);
        ControllerCommand move = new ControllerCommand.Move(5, 11, 4, 101);
        assertThat(ControllerCommand.decode(ControllerCommand.encode(move))).isEqualTo(move);
    }
}
