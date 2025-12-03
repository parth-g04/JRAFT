import java.io.*;
import java.net.*;

public class Client {
    public static void main(String[] args) {
        String hostname = "localhost";
        int port = 5000;

        try (Socket socket = new Socket(hostname, port)) {
            
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            System.out.println("Sending: PUT name Alex");
            out.println("PUT name Alex"); 
            String response1 = in.readLine(); 
            System.out.println("Server replied: " + response1);

            System.out.println("Sending: GET name");
            out.println("GET name");
            String response2 = in.readLine();
            System.out.println("Server replied: " + response2);

        } catch (IOException e) {
            System.out.println("Client Error: " + e.getMessage());
        }
    }
}