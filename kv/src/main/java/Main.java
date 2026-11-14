public class Main {
    public static void main(String[] args) {
        System.out.println("Starting J-Raft Database...");
        KeyValueStore myDB = new InMemoryStore();

        myDB.put("user1", "Rahul");
        myDB.put("language", "Java");

        String user = myDB.get("user1");
        System.out.println("Retrieved Value: " + user);


        String missing = myDB.get("ghost");
        System.out.println("Retrieved Missing: " + missing); 
    }
}