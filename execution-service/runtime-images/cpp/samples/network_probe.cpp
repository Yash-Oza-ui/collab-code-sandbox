// network_probe.cpp — T-14 C++ network isolation verification
#include <iostream>
#include <cstring>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cerrno>

int main() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        std::cerr << "Expected error: failed to create socket (" << strerror(errno) << ")" << std::endl;
        std::cout << "NETWORK BLOCKED" << std::endl;
        return 1;
    }

    struct sockaddr_in serv_addr;
    std::memset(&serv_addr, 0, sizeof(serv_addr));
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(80);
    inet_pton(AF_INET, "1.1.1.1", &serv_addr.sin_addr);

    // Set a short timeout (1 second) so it fails fast if routed
    struct timeval tv;
    tv.tv_sec = 1;
    tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof(tv));

    int res = connect(sock, (struct sockaddr*)&serv_addr, sizeof(serv_addr));
    if (res < 0) {
        std::cerr << "Expected error: connection failed (" << strerror(errno) << ")" << std::endl;
        std::cout << "NETWORK BLOCKED" << std::endl;
        close(sock);
        return 1;
    }

    close(sock);
    std::cerr << "UNEXPECTED: network connection succeeded!" << std::endl;
    return 0;
}
