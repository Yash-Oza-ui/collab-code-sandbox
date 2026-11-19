/* compile_error.c — US-14 C compile error verification
 * Deliberately invalid syntax to verify compile stage reports compile_error.
 */

#include <stdio.h>

int main(void) {
    this_is_an_intentional_compile_error_with_no_semicolon
    return 0;
}
