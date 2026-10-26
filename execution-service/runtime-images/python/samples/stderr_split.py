"""
stderr_split.py — T-14 stdout/stderr independence verification
Writes one line to stdout and one line to stderr, then exits 0.
The test verifies they are captured independently.

Expected stdout:    STDOUT line
Expected stderr:    STDERR line
Expected exit code: 0
"""
import sys

print("STDOUT line")          # goes to stdout (fd 1)
print("STDERR line", file=sys.stderr)  # goes to stderr (fd 2)
