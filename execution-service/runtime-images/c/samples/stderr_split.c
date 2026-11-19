/* stderr_split.c — US-14 C stdout/stderr separation verification
 * Expected stdout:   STDOUT line
 * Expected stderr:   STDERR line
 * Expected exit:     0
 */

#include <stdio.h>

int main(void) {
    fprintf(stdout, "STDOUT line\n");
    fprintf(stderr, "STDERR line\n");
    return 0;
}
