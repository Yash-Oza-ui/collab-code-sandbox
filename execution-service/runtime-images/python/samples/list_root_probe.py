"""
list_root_probe.py — US-12 / NFR3.4 host isolation verification
Inspects the container root filesystem (/) to ensure no host directories,
host user data, or sensitive Docker/system sockets are mounted.

Expected behaviour:
  - No host root directories mounted (e.g. host user homes, host docker socket)
  - Sensitive files like /etc/shadow or /root are not readable
  - stdout: ROOT ISOLATED
  - exit code: 0

Failure behaviour:
  - Host files or sockets accessible
  - stdout: HOST LEAK — SANDBOX FAILURE
  - exit code: 2
"""
import os
import sys

# 1. Check for dangerous host mounts
dangerous_paths = [
    "/var/run/docker.sock",
    "/run/docker.sock",
    "/host",
    "/host_root",
]

for p in dangerous_paths:
    if os.path.exists(p):
        print(f"HOST LEAK — SANDBOX FAILURE: {p} is accessible")
        sys.exit(2)

# 2. Check that sensitive system files cannot be read by non-root user
sensitive_files = ["/etc/shadow", "/root/.bashrc", "/root/.ssh"]
for sf in sensitive_files:
    if os.path.exists(sf):
        try:
            with open(sf, "rb") as f:
                f.read(1)
            print(f"HOST LEAK — SANDBOX FAILURE: {sf} is readable")
            sys.exit(2)
        except (PermissionError, OSError):
            pass

print("ROOT ISOLATED", flush=True)
sys.exit(0)
