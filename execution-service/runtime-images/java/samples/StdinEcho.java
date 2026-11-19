// StdinEcho.java — US-14 Java stdin verification program
// Reads lines from stdin and writes them back to stdout (uppercased).
// Expected input:   hello sandbox
// Expected output:  HELLO SANDBOX
// Expected exit:    0

import java.util.Scanner;

public class StdinEcho {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            System.out.println(scanner.nextLine().toUpperCase());
        }
    }
}
