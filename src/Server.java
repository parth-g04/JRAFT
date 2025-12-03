import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Server {
    private final int myPort;
    private final List<Integer> peerPorts;
    private final KeyValueStore store;

    public static RaftState state = RaftState.FOLLOWER;

    public static volatile long lastHeartbeatTime = System.currentTimeMillis();
    
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public Server(int port, List<Integer> peers, KeyValueStore store) {
        this.myPort = port;
        this.peerPorts = peers;
        this.store = store;
        
        // Check state every 500ms
        scheduler.scheduleAtFixedRate(this::runRaftCycle, 0, 500, TimeUnit.MILLISECONDS);
    }

    private void runRaftCycle() {
        long currentTime = System.currentTimeMillis();

        if (state == RaftState.LEADER) {
            // --- SPRINT 6: SEND HEARTBEATS ---
            System.out.println("[Leader] Sending Heartbeats...");
            
            for (Integer peerPort : peerPorts) {
                try {
                    Socket socket = new Socket("localhost", peerPort);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    out.println("HEARTBEAT"); // I AM ALIVE!
                    socket.close();
                } catch (Exception e) {
                    // Peer is offline, ignore.
                }
            }
            
        } else {
            // FOLLOWER LOGIC: Check for Timeout
            if (currentTime - lastHeartbeatTime > 3000) {
                System.out.println("[Timeout] Leader is dead! Starting election...");
                state = RaftState.CANDIDATE; 
                lastHeartbeatTime = currentTime; 
                
                // --- ELECTION LOGIC ---
                int votes = 1;
                for (Integer peerPort : peerPorts) {
                    try {
                        Socket socket = new Socket("localhost", peerPort);
                        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        
                        out.println("VOTE_REQUEST");
                        String reply = in.readLine();
                        if ("VOTE_GRANTED".equals(reply)) {
                            votes++;
                        }
                        socket.close();
                    } catch (Exception e) {}
                }
                
                if (votes >= 2) {
                    System.out.println(">>> I WON THE ELECTION! I am now the LEADER <<<");
                    state = RaftState.LEADER;
                }
            }
        }
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(myPort)) {
            System.out.println("Server " + myPort + " is listening");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket, store)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java Server <port>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        
        List<Integer> peers = new ArrayList<>();
        peers.add(5000);
        peers.add(5001);
        peers.add(5002);
        peers.remove(Integer.valueOf(port)); 

        KeyValueStore myStore = new InMemoryStore();
        Server myServer = new Server(port, peers, myStore);
        myServer.start();
    }
}