// CompileError.java — US-14 Java compile error verification
// Contains syntax error to verify compile stage reports compile_error.

public class CompileError {
    public static void main(String[] args) {
        this_is_an_intentional_compile_error_with_no_semicolon
    }
}
