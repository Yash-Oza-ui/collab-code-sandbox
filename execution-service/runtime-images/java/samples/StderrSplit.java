// StderrSplit.java — US-14 Java stdout/stderr separation verification
// Expected stdout:   STDOUT line
// Expected stderr:   STDERR line
// Expected exit:     0

public class StderrSplit {
    public static void main(String[] args) {
        System.out.println("STDOUT line");
        System.err.println("STDERR line");
    }
}
