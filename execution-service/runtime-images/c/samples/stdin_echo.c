/* stdin_echo.c — US-14 C stdin verification program
 * Reads characters from stdin and writes them back to stdout (uppercased).
 * Expected input:   hello sandbox
 * Expected output:  HELLO SANDBOX
 * Expected exit:    0
 */

#include <stdio.h>
#include <ctype.h>

int main(void) {
    int c;
    while ((c = getchar()) != EOF) {
        putchar(toupper((unsigned char)c));
    }
    return 0;
}
