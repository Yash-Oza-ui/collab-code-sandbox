// stdin_echo.cpp — T-14 C++ stdin echo verification program
#include <iostream>
#include <string>
#include <algorithm>
#include <cctype>

int main() {
    std::string line;
    while (std::getline(std::cin, line)) {
        std::transform(line.begin(), line.end(), line.begin(), [](unsigned char c) {
            return std::toupper(c);
        });
        std::cout << line << std::endl;
    }
    return 0;
}
