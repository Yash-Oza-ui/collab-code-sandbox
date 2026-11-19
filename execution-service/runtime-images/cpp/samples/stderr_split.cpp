// stderr_split.cpp — US-14 C++ stdout/stderr separation verification
// Expected stdout:   STDOUT line
// Expected stderr:   STDERR line
// Expected exit:     0

#include <iostream>

int main() {
    std::cout << "STDOUT line" << std::endl;
    std::cerr << "STDERR line" << std::endl;
    return 0;
}
