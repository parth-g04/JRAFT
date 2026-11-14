import java.io.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStore implements KeyValueStore {

    private final ConcurrentHashMap<String, String> storage;
    private final String filename = "data.log"; // The hard drive file

    public InMemoryStore() {
        this.storage = new ConcurrentHashMap<>();

        loadFromDisk();
    }

    @Override
    public void put(String key, String value) {

        storage.put(key, value);
        System.out.println("[DB-Engine] Saved: " + key + " = " + value);
        
        appendToLog(key, value);
    }

    @Override
    public String get(String key) {
        return storage.get(key);
    }
    
    @Override
    public boolean containsKey(String key) {
        return storage.containsKey(key);
    }



    private void loadFromDisk() {
        File file = new File(filename);
        if (!file.exists()) {
            System.out.println("[DB-Engine] No previous data found. Starting fresh.");
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int count = 0;

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    storage.put(parts[0], parts[1]);
                    count++;
                }
            }
            System.out.println("[DB-Engine] Recovered " + count + " records from disk.");
        } catch (IOException e) {
            System.out.println("[DB-Engine] Error loading data: " + e.getMessage());
        }
    }

    private void appendToLog(String key, String value) {
        try (FileWriter writer = new FileWriter(filename, true)) {
            writer.write(key + "," + value + "\n");
            writer.flush(); // Force save to disk
        } catch (IOException e) {
            System.out.println("[DB-Engine] Error saving to disk: " + e.getMessage());
        }
    }
}