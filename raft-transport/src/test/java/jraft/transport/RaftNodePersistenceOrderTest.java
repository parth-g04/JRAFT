package jraft.transport;

import static org.assertj.core.api.Assertions.assertThat;

import jraft.raftcore.Message;
import jraft.raftcore.Output;
import jraft.raftcore.RaftCore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RaftNodePersistenceOrderTest {
    @Test
    void persistsTermAndVoteBeforeSendingRequestVote() {
        List<String> events = new ArrayList<>();
        RaftCore core = new RaftCore(1, List.of(2, 3), 3, 1);
        RaftNode node = new RaftNode(core, (peer, message) -> events.add("send"), new OutputHandler() {
            @Override public void persistHardState(long term, Integer votedFor, long commitIndex) { events.add("persist"); }
            @Override public void appendToLog(List<jraft.raftcore.LogEntry> entries) { }
            @Override public void truncateLog(long fromIndex) { }
            @Override public void applyEntries(List<jraft.raftcore.LogEntry> entries) { }
            @Override public void saveSnapshot(jraft.raftcore.Snapshot snapshot) { }
            @Override public void installSnapshot(jraft.raftcore.Snapshot snapshot) { }
        });
        node.tick(); node.tick(); node.tick();
        events.clear();
        node.receive(2, new Message.PreVoteReply(0, 1, true));

        assertThat(events).containsExactly("persist", "send", "send");
    }
}
