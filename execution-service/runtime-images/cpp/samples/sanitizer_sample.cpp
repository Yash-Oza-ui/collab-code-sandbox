// sanitizer_sample.cpp — US-14 / US-73 sanitizer toolchain verification
// Compiles with -fsanitize=address,undefined to confirm sanitizer runtime support is present.

#include <iostream>

int main() {
    std::cout << "SANITIZER_SUPPORT_OK" << std::endl;
    return 0;
}
