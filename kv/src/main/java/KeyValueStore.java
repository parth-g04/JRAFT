public interface KeyValueStore {
    void put(String key,String value);
    String get(String key);
    boolean containsKey(String key);
}
