// compile_error.cpp — US-14 C++ compile error verification
// Contains invalid syntax to verify the compile stage returns compile_error.

#include <iostream>

int main() {
    this_is_an_intentional_compile_error_with_no_semicolon
    return 0;
}
