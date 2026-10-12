// stderr_split.cpp — T-14 C++ stdout/stderr independence verification
#include <iostream>

int main() {
    std::cout << "STDOUT line" << std::endl;
    std::cerr << "STDERR line" << std::endl;
    return 0;
}
