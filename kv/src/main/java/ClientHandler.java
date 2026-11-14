import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final KeyValueStore store;

    public ClientHandler(Socket socket, KeyValueStore store) {
        this.socket = socket;
        this.store = store;
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                String[] parts = inputLine.split(" ");
                String command = parts[0].toUpperCase();

                if (command.equals("PUT")) {
                    store.put(parts[1], parts[2]);
                    out.println("OK");
                } 
                else if (command.equals("GET")) {
                    String result = store.get(parts[1]);
                    out.println(result == null ? "NULL" : result);
                } 
                else if (command.equals("VOTE_REQUEST")) {

                    if (Server.state == RaftState.LEADER) {
                        out.println("VOTE_DENIED");
                        System.out.println("Denied vote request (I am already Leader).");
                    } else {
                        out.println("VOTE_GRANTED");
                        System.out.println("Voted for a candidate.");
                        Server.lastHeartbeatTime = System.currentTimeMillis();
                    }
                }
                else if (command.equals("HEARTBEAT")) {
                    Server.lastHeartbeatTime = System.currentTimeMillis();
                    
                    if (Server.state == RaftState.CANDIDATE) {
                        Server.state = RaftState.FOLLOWER;
                        System.out.println("Stepping down to FOLLOWER (Leader is alive).");
                    }
                }

            }
            socket.close();
        } catch (IOException e) {

        }
    }
}