package jraft.sim;

import jraft.kv.KvResult;
import jraft.kv.ShardKvCommand;
import jraft.kv.ShardKvMessage;
import jraft.kv.ShardKvServer;
import jraft.kv.ShardKvStateMachine;
import jraft.raftcore.LogEntry;
import jraft.raftcore.StateMachine;
import jraft.shardctrler.ControllerMessage;

import java.util.List;

/**
 * Shard group server (ShardKvServer in ARCHITECTURE.md): answers clients, answers pull/delete
 * requests from other groups, and on the leader runs {@link ShardKvServer}'s background actions.
 */
final class ShardKvService implements GroupNode.Service {
    private final int gid;
    private final ShardKvStateMachine machine;
    private final ShardKvServer server;
    private final List<Integer> controllers;
    private final long queryClientId;
    private final ShardedWorld world;
    private long querySequence;

    ShardKvService(ShardedWorld world, int gid, int nodeId, List<Integer> controllers) {
        this.world = world;
        this.gid = gid;
        this.machine = new ShardKvStateMachine(gid);
        this.server = new ShardKvServer(gid, 10, 10);
        this.controllers = controllers;
        this.queryClientId = 5_000 + nodeId;
    }

    @Override public StateMachine stateMachine() { return machine; }
    @Override public Object apply(LogEntry entry) { return machine.applyEntry(entry); }
    @Override public void onBecameLeader() { server.reset(); }

    @Override
    public void leaderTick(GroupNode node, long now) { run(node, server.tick(now, machine)); }

    @Override
    public void onMessage(GroupNode node, int from, Object message) {
        switch (message) {
            case ShardKvMessage.KvRequest request -> onClientRequest(node, from, request);
            case ShardKvMessage.PullRequest pull -> {
                // Frozen data is identical on every replica that applied the configuration, but only the
                // leader answers to keep traffic down.
                if (node.isLeader()) node.send(from, new ShardKvMessage.PullReply(gid, pull.configNum(),
                        machine.pull(pull.configNum(), pull.shards())));
            }
            case ShardKvMessage.DeleteRequest delete -> {
                if (!node.isLeader()) return;
                boolean done = machine.deleted(delete.configNum(), delete.shards());
                if (!done && machine.currentConfig().num() == delete.configNum()) {
                    node.propose(ShardKvCommand.encode(new ShardKvCommand.DeleteShards(delete.configNum(), delete.shards())),
                            result -> { }, () -> { });
                }
                node.send(from, new ShardKvMessage.DeleteReply(gid, delete.configNum(), delete.shards(), done));
            }
            case ShardKvMessage.PullReply reply -> { if (node.isLeader()) run(node, server.onPullReply(reply, machine)); }
            case ShardKvMessage.DeleteReply reply -> { if (node.isLeader()) run(node, server.onDeleteReply(reply, machine)); }
            case ControllerMessage.Reply reply -> {
                if (node.isLeader() && reply.ok() && reply.clientId() == queryClientId) run(node, server.onConfig(reply.config(), machine));
            }
            default -> { }
        }
    }

    private void onClientRequest(GroupNode node, int from, ShardKvMessage.KvRequest request) {
        if (!node.isLeader()) {
            node.send(from, new ShardKvMessage.KvReply(request.clientId(), request.sequence(), KvResult.WRONG_LEADER));
            return;
        }
        if (request.op() == ShardKvCommand.Op.GET && world.unsafeShardReads()) {
            // Test-only negative control: answer from whatever copy we hold, even after the shard moved.
            var data = machine.dataOf(jraft.shardctrler.ShardConfig.shardOf(request.key()));
            String value = data.get(request.key());
            node.send(from, new ShardKvMessage.KvReply(request.clientId(), request.sequence(),
                    value == null ? new KvResult(KvResult.Status.NO_KEY, null) : new KvResult(KvResult.Status.OK, value)));
            return;
        }
        if (request.op() == ShardKvCommand.Op.GET) {
            // ReadIndex, then check that we still serve the shard at that point.
            node.read(() -> node.send(from, new ShardKvMessage.KvReply(request.clientId(), request.sequence(), machine.read(request.key()))),
                    () -> node.send(from, new ShardKvMessage.KvReply(request.clientId(), request.sequence(), KvResult.WRONG_LEADER)));
            return;
        }
        ShardKvCommand.ClientOp op = new ShardKvCommand.ClientOp(request.clientId(), request.sequence(), request.op(),
                request.key(), request.value());
        node.propose(ShardKvCommand.encode(op),
                result -> { if (result != null) node.send(from, new ShardKvMessage.KvReply(request.clientId(), request.sequence(), (KvResult) result)); },
                () -> node.send(from, new ShardKvMessage.KvReply(request.clientId(), request.sequence(), KvResult.WRONG_LEADER)));
    }

    private void run(GroupNode node, List<ShardKvServer.Action> actions) {
        for (ShardKvServer.Action action : actions) {
            switch (action) {
                case ShardKvServer.Action.Propose propose ->
                        node.propose(ShardKvCommand.encode(propose.command()), result -> { }, () -> { });
                case ShardKvServer.Action.QueryController query -> {
                    long sequence = ++querySequence;
                    for (int controller : controllers) {
                        node.send(controller, ControllerMessage.Request.query(queryClientId, sequence, query.num()));
                    }
                }
                case ShardKvServer.Action.SendToGroup send -> {
                    for (int target : send.servers()) node.send(target, send.message());
                }
            }
        }
    }

    ShardKvStateMachine machine() { return machine; }
    int gid() { return gid; }
}
