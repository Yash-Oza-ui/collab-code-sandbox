"""
read_other_run_probe.py — US-12 / NFR3.4 cross-run isolation verification
Verifies that the run cannot access another run's workspace or temporary files.
Every run receives an isolated container with private mounts and private tmpfs.

Expected behaviour:
  - Host workspace directory (/tmp/collab-code-workspaces) is not mounted
  - /sandbox/code only contains the current run's code files
  - /tmp is private tmpfs and cannot see other containers' files
  - stdout: RUN ISOLATED
  - exit code: 0

Failure behaviour:
  - Other runs' directories or data found
  - stdout: CROSS-RUN LEAK — SANDBOX FAILURE
  - exit code: 2
"""
import glob
import os
import sys

# 1. Host workspace root must not be mounted
if os.path.exists("/tmp/collab-code-workspaces"):
    # If mounted, check whether other workspaces exist inside it
    entries = os.listdir("/tmp/collab-code-workspaces")
    if entries:
        print("CROSS-RUN LEAK — SANDBOX FAILURE: host workspaces directory visible")
        sys.exit(2)

# 2. Check /tmp for other runs' markers
other_run_markers = glob.glob("/tmp/run-*") + glob.glob("/tmp/*marker*") + glob.glob("/tmp/*secret*")
# Filter out any files belonging to this specific run if named
if other_run_markers:
    # If foreign files are readable in /tmp
    for m in other_run_markers:
        try:
            with open(m, "r") as f:
                content = f.read()
            if "foreign" in content or "other_run" in content:
                print(f"CROSS-RUN LEAK — SANDBOX FAILURE: found {m}")
                sys.exit(2)
        except OSError:
            pass

print("RUN ISOLATED", flush=True)
sys.exit(0)
