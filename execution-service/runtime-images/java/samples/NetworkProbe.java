// NetworkProbe.java — US-14 Java network isolation verification
// Attempts a TCP connection to an external host (8.8.8.8:53).
// Under --network none, connect() must fail.
// Expected stdout: NETWORK BLOCKED
// Expected exit:   1

import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.IOException;

public class NetworkProbe {
    public static void main(String[] args) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("8.8.8.8", 53), 3000);
            System.out.println("NETWORK REACHABLE — SANDBOX FAILURE");
            System.exit(2);
        } catch (IOException e) {
            System.out.println("NETWORK BLOCKED");
            System.err.println("Expected error: " + e.getMessage());
            System.exit(1);
        }
    }
}
