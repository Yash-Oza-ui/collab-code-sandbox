"""
stdin_echo.py — T-14 stdin verification program
Reads every line from stdin and writes it back to stdout (uppercased).
The test passes "hello sandbox" and expects "HELLO SANDBOX".

Expected input (one line):  hello sandbox
Expected output:            HELLO SANDBOX
Expected stderr:            (empty)
Expected exit code:         0

If stdin is empty (EOF immediately), the program prints nothing and exits 0.
"""
import sys

for line in sys.stdin:
    print(line.rstrip().upper())
