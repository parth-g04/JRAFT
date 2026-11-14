package jraft.transport;

import jraft.raftcore.Message;

/** Network boundary used by the RaftNode driver. */
public interface Transport {
    void send(int peerId, Message message);
}
