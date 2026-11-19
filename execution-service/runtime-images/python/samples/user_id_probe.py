"""
user_id_probe.py — US-12 / NFR3.2 non-root execution verification
Checks that user code executes under a non-root user (UID != 0).
The sandboxed runtime runs as user 'sandbox' (UID 1001, GID 1001).

Expected behaviour:
  - os.getuid() != 0
  - stdout: NON-ROOT USER: UID=<uid> GID=<gid>
  - exit code: 0

Failure behaviour:
  - os.getuid() == 0 (root)
  - stdout: ROOT USER — SANDBOX FAILURE
  - exit code: 2
"""
import os
import sys

uid = os.getuid()
gid = os.getgid()

if uid == 0:
    print(f"ROOT USER — SANDBOX FAILURE: UID={uid} GID={gid}")
    sys.exit(2)

print(f"NON-ROOT USER: UID={uid} GID={gid}", flush=True)
sys.exit(0)
