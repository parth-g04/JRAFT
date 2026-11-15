package jraft.sim;

import jraft.shardctrler.ControllerCommand;
import jraft.shardctrler.ControllerMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** Administrator: sends Join/Leave/Move to the controller one at a time, retrying with the same sequence. */
public final class ShardedAdmin implements ShardedWorld.Client {
    public static final long CLIENT_ID = 2_000;
    private static final int RETRY_TICKS = 10;

    private final ShardedWorld world;
    private final Deque<ControllerCommand> queue = new ArrayDeque<>();
    private final SortedSet<Integer> joined = new TreeSet<>();
    private long nextSequence;
    private ControllerCommand inFlight;
    private long sentAt;
    private int completed;

    public ShardedAdmin(ShardedWorld world) {
        this.world = world;
        world.register(CLIENT_ID, this);
    }

    public void join(int gid) {
        TreeMap<Integer, List<Integer>> groups = new TreeMap<>();
        groups.put(gid, ShardedWorld.serversOf(gid));
        queue.add(new ControllerCommand.Join(CLIENT_ID, ++nextSequence, groups));
        joined.add(gid);
    }

    public void leave(int gid) {
        queue.add(new ControllerCommand.Leave(CLIENT_ID, ++nextSequence, List.of(gid)));
        joined.remove(gid);
    }

    public void move(int shard, int gid) { queue.add(new ControllerCommand.Move(CLIENT_ID, ++nextSequence, shard, gid)); }

    /** Groups that are (or will be, once queued commands finish) in the configuration. */
    public SortedSet<Integer> joined() { return new TreeSet<>(joined); }
    public boolean idle() { return inFlight == null && queue.isEmpty(); }
    public int completed() { return completed; }

    @Override
    public void tick(long now) {
        if (inFlight == null && !queue.isEmpty()) {
            inFlight = queue.poll();
            send(now);
        } else if (inFlight != null && now - sentAt >= RETRY_TICKS) {
            send(now);
        }
    }

    @Override
    public void onMessage(int from, Object message) {
        if (message instanceof ControllerMessage.Reply reply && reply.ok() && inFlight != null
                && reply.sequence() == inFlight.sequence()) {
            inFlight = null;
            completed++;
        }
    }

    private void send(long now) {
        sentAt = now;
        for (int controller : ShardedWorld.CONTROLLERS) {
            world.sendApp((int) CLIENT_ID, controller,
                    new ControllerMessage.Request(CLIENT_ID, inFlight.sequence(), inFlight, -1));
        }
    }
}
