// stdin_echo.cpp — US-14 C++ stdin verification program
// Reads lines from stdin and writes them back to stdout (uppercased).
// Expected input:   hello sandbox
// Expected output:  HELLO SANDBOX
// Expected exit:    0

#include <iostream>
#include <string>
#include <algorithm>
#include <cctype>

int main() {
    std::string line;
    while (std::getline(std::cin, line)) {
        for (char &c : line) {
            c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
        }
        std::cout << line << std::endl;
    }
    return 0;
}
