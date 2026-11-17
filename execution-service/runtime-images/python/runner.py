#!/usr/bin/env python3
"""Trusted in-container supervisor for US-10 execution measurements.

The supervisor runs as a restricted root process so submitted code cannot
tamper with the private metrics file. It drops the child to UID/GID 1001
before executing user source.
"""

import os
import resource
import signal
import subprocess
import sys
import time

METRICS_FILE = "/sandbox/metrics/run-metrics.properties"
child = None
timed_out = False
termination_requested = False
started_ns = None


def drop_privileges():
    # Reset oom_score_adj for child so child is prioritized for OOM kill over supervisor
    try:
        with open("/proc/self/oom_score_adj", "w") as f:
            f.write("0")
    except Exception:
        pass

    try:
        os.setsid()
    except Exception:
        pass

    try:
        os.setgroups([])
    except (OSError, PermissionError):
        pass

    os.setgid(1001)
    os.setuid(1001)


def peak_memory_bytes():
    # Genuine cgroup high-water marks only:
    # 1. cgroup v2 peak memory (Linux 5.19+ / modern Docker / WSL2)
    # 2. cgroup v1 max usage (legacy Linux hosts)
    # Note: Instantaneous counters (memory.current, memory.usage_in_bytes) are intentionally
    # NOT checked here because reading them after execution misses peak usage if memory was
    # deallocated before process exit.
    if os.environ.get("COLLAB_SANDBOX_FORCE_RUSAGE") != "1":
        for path in (
            "/sys/fs/cgroup/memory.peak",
            "/sys/fs/cgroup/memory/memory.max_usage_in_bytes",
            "/sys/fs/cgroup/memory.max_usage_in_bytes",
        ):
            try:
                with open(path, "r", encoding="ascii") as stream:
                    value = int(stream.read().strip())
                if value > 0:
                    return value
            except (OSError, ValueError):
                pass

    # Genuine child-process high-water mark fallback:
    # getrusage(RUSAGE_CHILDREN).ru_maxrss is the peak Resident Set Size (RSS) of the child process
    # in KiB across its lifetime.
    try:
        return int(resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss * 1024)
    except (OSError, ValueError):
        return 0


def write_metrics():
    elapsed_ms = 0.0 if started_ns is None else (time.monotonic_ns() - started_ns) / 1_000_000.0
    peak_bytes = peak_memory_bytes()
    try:
        child_rss_bytes = int(resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss * 1024)
    except (OSError, ValueError):
        child_rss_bytes = 0

    temporary = METRICS_FILE + ".tmp"
    try:
        os.makedirs(os.path.dirname(METRICS_FILE), exist_ok=True)
        with open(temporary, "w", encoding="ascii") as stream:
            stream.write(f"execution_time_ms={elapsed_ms:.3f}\n")
            stream.write(f"peak_memory_bytes={peak_bytes}\n")
            stream.write(f"child_rss_bytes={child_rss_bytes}\n")
            stream.write(f"timed_out={str(timed_out).lower()}\n")
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.chmod(temporary, 0o644)
        except OSError:
            pass
        os.replace(temporary, METRICS_FILE)
    except OSError:
        # Missing metrics are treated as unavailable by the Java side.
        pass


def terminate_child():
    if child is None or child.poll() is not None:
        return
    try:
        os.killpg(child.pid, signal.SIGTERM)
    except OSError:
        child.terminate()
    try:
        child.wait(timeout=0.5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except OSError:
            child.kill()
        child.wait()


def handle_termination(_signum, _frame):
    global termination_requested
    termination_requested = True
    terminate_child()


# Protect supervisor from being the primary victim of the kernel OOM killer
try:
    with open("/proc/self/oom_score_adj", "w") as f:
        f.write("-999")
except Exception:
    pass

# Ensure /sandbox/metrics is root-only mode 0700
try:
    os.makedirs("/sandbox/metrics", exist_ok=True)
    os.chmod("/sandbox/metrics", 0o700)
except OSError:
    pass

signal.signal(signal.SIGTERM, handle_termination)
signal.signal(signal.SIGINT, handle_termination)

exit_code = 125
try:
    wall_time_seconds = float(sys.argv[1])
    entry_file = sys.argv[2]
    started_ns = time.monotonic_ns()
    child = subprocess.Popen(
        [sys.executable, "-u", entry_file],
        preexec_fn=drop_privileges,
    )

    if termination_requested:
        terminate_child()

    try:
        child.wait(timeout=wall_time_seconds)
        if child.returncode is not None and child.returncode < 0:
            exit_code = 128 + (-child.returncode)
        else:
            exit_code = child.returncode if child.returncode is not None else 125
    except subprocess.TimeoutExpired:
        timed_out = True
        terminate_child()
        exit_code = 124
finally:
    write_metrics()

sys.exit(exit_code)
