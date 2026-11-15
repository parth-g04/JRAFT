package jraft.kv;

import jraft.shardctrler.ShardConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Background work of a shard group's leader, as a pure state machine like RaftCore: it receives
 * ticks and replies and returns actions for the driver to execute. It never changes the state
 * machine directly; everything goes through the Raft log as a proposal, so all replicas agree.
 *
 * <ul>
 *   <li>Poll the controller for configuration current+1 (only when no migration is in progress)
 *       and propose Reconfigure.</li>
 *   <li>For PULLING shards, ask the previous owner for the data (pull-based migration) and propose
 *       InsertShards with the reply.</li>
 *   <li>For GCING shards, ask the previous owner to delete its copy; when it confirms, propose GcDone.</li>
 * </ul>
 * Requests are simply repeated every {@code retryTicks} until the state machine shows they took
 * effect; every proposal is idempotent, so duplicates are harmless.
 */
public final class ShardKvServer {
    public sealed interface Action {
        record Propose(ShardKvCommand command) implements Action { }
        record QueryController(int num) implements Action { }
        record SendToGroup(int gid, List<Integer> servers, ShardKvMessage message) implements Action { }
    }

    private final int gid;
    private final int pollTicks;
    private final int retryTicks;
    private long nextPoll;
    private long nextPull;
    private long nextGc;

    public ShardKvServer(int gid, int pollTicks, int retryTicks) {
        this.gid = gid;
        this.pollTicks = pollTicks;
        this.retryTicks = retryTicks;
    }

    /** Called every tick while this node is the group's leader. */
    public List<Action> tick(long now, ShardKvStateMachine machine) {
        List<Action> actions = new ArrayList<>();
        if (machine.stable() && now >= nextPoll) {
            actions.add(new Action.QueryController(machine.currentConfig().num() + 1));
            nextPoll = now + pollTicks;
        }
        if (now >= nextPull) {
            for (Map.Entry<Integer, List<Integer>> owner
                    : machine.shardsByPreviousOwner(ShardKvStateMachine.ShardStatus.PULLING).entrySet()) {
                actions.add(new Action.SendToGroup(owner.getKey(), machine.previousConfig().serversOf(owner.getKey()),
                        new ShardKvMessage.PullRequest(gid, machine.currentConfig().num(), owner.getValue())));
            }
            nextPull = now + retryTicks;
        }
        if (now >= nextGc) {
            for (Map.Entry<Integer, List<Integer>> owner
                    : machine.shardsByPreviousOwner(ShardKvStateMachine.ShardStatus.GCING).entrySet()) {
                actions.add(new Action.SendToGroup(owner.getKey(), machine.previousConfig().serversOf(owner.getKey()),
                        new ShardKvMessage.DeleteRequest(gid, machine.currentConfig().num(), owner.getValue())));
            }
            nextGc = now + retryTicks;
        }
        return actions;
    }

    /** The controller answered a query. Only the exact next configuration is proposed. */
    public List<Action> onConfig(ShardConfig config, ShardKvStateMachine machine) {
        if (config.num() != machine.currentConfig().num() + 1 || !machine.stable()) return List.of();
        return List.of(new Action.Propose(new ShardKvCommand.Reconfigure(config)));
    }

    public List<Action> onPullReply(ShardKvMessage.PullReply reply, ShardKvStateMachine machine) {
        if (reply.shards() == null || reply.configNum() != machine.currentConfig().num()) return List.of();
        return List.of(new Action.Propose(new ShardKvCommand.InsertShards(reply.configNum(), reply.shards())));
    }

    public List<Action> onDeleteReply(ShardKvMessage.DeleteReply reply, ShardKvStateMachine machine) {
        if (!reply.done() || reply.configNum() != machine.currentConfig().num()) return List.of();
        return List.of(new Action.Propose(new ShardKvCommand.GcDone(reply.configNum(), reply.shards())));
    }

    /** Leadership changed: poll and retry right away. */
    public void reset() {
        nextPoll = 0;
        nextPull = 0;
        nextGc = 0;
    }
}
