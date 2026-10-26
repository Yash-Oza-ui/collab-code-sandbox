"""
network_probe.py — T-14 network isolation verification
Attempts a TCP connection to an external host. When the container is
started with --network none this will fail. The test expects a nonzero
exit code AND confirmation that no output was produced on stdout.

This script deliberately attempts a real external connection so the
test result is meaningful. It is intentionally NOT allowed to succeed.

Expected behaviour (with --network none):
  - socket.connect() raises OSError (no route to host / network unreachable)
  - stdout: NETWORK BLOCKED
  - stderr: contains the error message
  - exit code: 1

Expected behaviour (without network isolation, for control test):
  - socket.connect() may succeed or fail depending on host
  - This behaviour should NEVER be seen in a sandboxed run
"""
import socket
import sys

try:
    s = socket.create_connection(("8.8.8.8", 53), timeout=3)
    s.close()
    # If we reach here, network is reachable — sandbox failed
    print("NETWORK REACHABLE — SANDBOX FAILURE")
    sys.exit(2)
except OSError as e:
    # Expected path when --network none is used
    print("NETWORK BLOCKED", flush=True)
    print(f"Expected error: {e}", file=sys.stderr, flush=True)
    sys.exit(1)
