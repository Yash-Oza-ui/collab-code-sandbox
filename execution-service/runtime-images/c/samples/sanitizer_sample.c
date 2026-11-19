/* sanitizer_sample.c — US-14 / US-73 sanitizer toolchain verification
 * Compiles with -fsanitize=address,undefined to confirm sanitizer runtime support is present.
 */

#include <stdio.h>

int main(void) {
    printf("SANITIZER_SUPPORT_OK\n");
    return 0;
}
