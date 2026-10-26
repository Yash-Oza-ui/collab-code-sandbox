"""
exit_nonzero.py — T-14 exit-code preservation verification
Exits immediately with code 42 to verify the container runner
propagates nonzero exit codes faithfully.

Expected stdout:    (empty)
Expected stderr:    (empty)
Expected exit code: 42
"""
import sys

sys.exit(42)
