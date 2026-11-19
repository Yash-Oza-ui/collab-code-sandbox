/* hello_world.c — US-14 C hello-world verification program
 * Prints greeting to stdout and reads nothing from stdin.
 * Expected output (exact):   Hello, Sandbox!
 * Expected stderr:           (empty)
 * Expected exit code:        0
 */

#include <stdio.h>

int main(void) {
    printf("Hello, Sandbox!\n");
    return 0;
}
