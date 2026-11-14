package jraft.sim;

import static org.assertj.core.api.Assertions.assertThat;

import jraft.raftcore.Message;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimNetworkTest {
    @Test
    void sameSeedProducesSameDeliverySchedule() {
        SimNetwork first = new SimNetwork(99, 0, 0, 5);
        SimNetwork second = new SimNetwork(99, 0, 0, 5);
        for (int i = 0; i < 10; i++) {
            first.send(0, 1, 2, new Message.VoteRequest(1, 1, 0, 0));
            second.send(0, 1, 2, new Message.VoteRequest(1, 1, 0, 0));
        }
        List<SimNetwork.Delivery> deliveries1 = first.drainDue(10);
        List<SimNetwork.Delivery> deliveries2 = second.drainDue(10);
        assertThat(deliveries1).isEqualTo(deliveries2);
    }

    @Test
    void partitionBlocksTrafficUntilHealed() {
        SimNetwork network = new SimNetwork(1, 0, 0, 0);
        network.partition(List.of(Set.of(1), Set.of(2, 3)));
        network.send(0, 1, 2, new Message.VoteRequest(1, 1, 0, 0));
        assertThat(network.drainDue(0)).isEmpty();

        network.heal();
        network.send(0, 1, 2, new Message.VoteRequest(1, 1, 0, 0));
        assertThat(network.drainDue(0)).hasSize(1);
    }

    @Test
    void partitionDropsMessagesAlreadyInFlight() {
        SimNetwork network = new SimNetwork(11, 0, 0, 5);
        network.send(0, 1, 2, new Message.VoteRequest(1, 1, 0, 0));
        network.partition(List.of(Set.of(1), Set.of(2, 3)));

        assertThat(network.drainDue(10)).isEmpty();
    }
}
