import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InMemoryStoreTest {
    @Test
    void storesAndReadsAValue() {
        KeyValueStore store = new InMemoryStore();

        store.put("name", "Alex");

        assertThat(store.get("name")).isEqualTo("Alex");
        assertThat(store.containsKey("name")).isTrue();
        assertThat(store.get("missing")).isNull();
    }
}
