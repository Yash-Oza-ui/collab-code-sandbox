/* network_probe.c — US-14 C network isolation verification
 * Attempts a TCP connection to an external host (8.8.8.8:53).
 * Under --network none, connect() must fail.
 * Expected stdout: NETWORK BLOCKED
 * Expected exit:   1
 */

#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/socket.h>
#include <arpa/inet.h>

int main(void) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        printf("NETWORK BLOCKED\n");
        fprintf(stderr, "Expected error: %s\n", strerror(errno));
        return 1;
    }

    struct sockaddr_in server;
    memset(&server, 0, sizeof(server));
    server.sin_family = AF_INET;
    server.sin_port = htons(53);
    inet_pton(AF_INET, "8.8.8.8", &server.sin_addr);

    if (connect(sock, (struct sockaddr *)&server, sizeof(server)) < 0) {
        close(sock);
        printf("NETWORK BLOCKED\n");
        fprintf(stderr, "Expected error: %s\n", strerror(errno));
        return 1;
    }

    close(sock);
    printf("NETWORK REACHABLE — SANDBOX FAILURE\n");
    return 2;
}
