// network_probe.cpp — US-14 C++ network isolation verification
// Attempts a TCP connection to an external host (8.8.8.8:53).
// Under --network none, connect() must fail.
// Expected stdout: NETWORK BLOCKED
// Expected exit:   1

#include <iostream>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstring>
#include <cerrno>

int main() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        std::cout << "NETWORK BLOCKED" << std::endl;
        std::cerr << "Expected error: " << std::strerror(errno) << std::endl;
        return 1;
    }

    struct sockaddr_in server;
    std::memset(&server, 0, sizeof(server));
    server.sin_family = AF_INET;
    server.sin_port = htons(53);
    inet_pton(AF_INET, "8.8.8.8", &server.sin_addr);

    if (connect(sock, (struct sockaddr *)&server, sizeof(server)) < 0) {
        close(sock);
        std::cout << "NETWORK BLOCKED" << std::endl;
        std::cerr << "Expected error: " << std::strerror(errno) << std::endl;
        return 1;
    }

    close(sock);
    std::cout << "NETWORK REACHABLE — SANDBOX FAILURE" << std::endl;
    return 2;
}
