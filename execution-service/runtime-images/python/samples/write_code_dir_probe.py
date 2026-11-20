"""
write_code_dir_probe.py — US-12 / NFR3.3 read-only code directory verification
Attempts to write into the mounted code directory (/sandbox/code).
With --mount ...,readonly or read-only bind mount this must fail.

Expected behaviour:
  - open('/sandbox/code/probe_write.txt', 'w') raises OSError (Read-only file system)
  - stdout: WRITE BLOCKED
  - exit code: 1

Failure behaviour:
  - Write succeeds
  - stdout: WRITE SUCCEEDED — SANDBOX FAILURE
  - exit code: 2
"""
import sys

for path in ("/sandbox/code/probe_write.txt", "probe_write.txt"):
    try:
        with open(path, "w") as f:
            f.write("untrusted write\n")
        print("WRITE SUCCEEDED — SANDBOX FAILURE")
        sys.exit(2)
    except OSError as e:
        # Expected: Read-only file system (Errno 30) or Permission denied (Errno 13)
        pass

print("WRITE BLOCKED", flush=True)
sys.exit(1)
