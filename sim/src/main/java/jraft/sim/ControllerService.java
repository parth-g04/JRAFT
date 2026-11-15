package jraft.sim;

import jraft.raftcore.LogEntry;
import jraft.raftcore.StateMachine;
import jraft.shardctrler.ControllerMessage;
import jraft.shardctrler.ControllerStateMachine;

/** Shard controller server: Join/Leave/Move through the log, Query through ReadIndex. */
final class ControllerService implements GroupNode.Service {
    private final ControllerStateMachine machine = new ControllerStateMachine();

    @Override public StateMachine stateMachine() { return machine; }

    @Override
    public Object apply(LogEntry entry) {
        machine.apply(entry);
        return Boolean.TRUE;
    }

    @Override
    public void onMessage(GroupNode node, int from, Object message) {
        if (!(message instanceof ControllerMessage.Request request)) return;
        if (!node.isLeader()) {
            node.send(from, new ControllerMessage.Reply(request.clientId(), request.sequence(), false, null));
            return;
        }
        if (request.command() == null) {
            node.read(
                    () -> node.send(from, new ControllerMessage.Reply(request.clientId(), request.sequence(), true,
                            machine.query(request.queryNum()))),
                    () -> node.send(from, new ControllerMessage.Reply(request.clientId(), request.sequence(), false, null)));
            return;
        }
        if (machine.applied(request.clientId(), request.sequence())) {   // retry of an applied command
            node.send(from, new ControllerMessage.Reply(request.clientId(), request.sequence(), true, machine.latest()));
            return;
        }
        node.propose(ControllerStateMachine.encode(request.command()),
                result -> node.send(from, new ControllerMessage.Reply(request.clientId(), request.sequence(), true, machine.latest())),
                () -> node.send(from, new ControllerMessage.Reply(request.clientId(), request.sequence(), false, null)));
    }

    ControllerStateMachine machine() { return machine; }
}
