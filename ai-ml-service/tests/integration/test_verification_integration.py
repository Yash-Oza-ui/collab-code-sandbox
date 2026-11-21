"""Integration tests for optimization sandbox verification (US-66, FR36.3).

Tests end-to-end sandbox execution and verification against real Docker images
(collab-sandbox-python:0.1.0) under isolated resource constraints.
"""

import subprocess
import pytest

from app.complexity import MeasurementItem
from app.verification import compare_runtimes, FixVerificationResult


def test_real_docker_quadratic_vs_linear_fix():
    """Real run check in Docker: quadratic code vs linear fix verified improvement."""
    try:
        check = subprocess.run(
            ["docker", "image", "inspect", "collab-sandbox-python:0.1.0"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=5,
        )
        if check.returncode != 0:
            pytest.skip("collab-sandbox-python:0.1.0 not present; skipping real Docker check")
    except Exception:
        pytest.skip("Docker not available; skipping real Docker check")

    # Quadratic code
    quadratic_code = (
        "import sys, time\n"
        "n = int(sys.stdin.read().strip() or '100')\n"
        "t0 = time.perf_counter()\n"
        "total = 0\n"
        "for i in range(n):\n"
        "    for j in range(n):\n"
        "        total += 1\n"
        "t1 = time.perf_counter()\n"
        "print(f'{(t1 - t0) * 1000.0:.4f}')\n"
    )

    # Linear fix (same mathematical result total = n * n)
    linear_code = (
        "import sys, time\n"
        "n = int(sys.stdin.read().strip() or '100')\n"
        "t0 = time.perf_counter()\n"
        "total = n * n\n"
        "t1 = time.perf_counter()\n"
        "print(f'{(t1 - t0) * 1000.0:.4f}')\n"
    )

    sizes = [400, 800, 1600]
    repeats = 3

    quad_runtimes = []
    linear_runtimes = []

    # Run quadratic at largest size (1600)
    for _ in range(repeats):
        proc = subprocess.run(
            ["docker", "run", "--rm", "-i", "collab-sandbox-python:0.1.0", "python", "-c", quadratic_code],
            input="1600",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=10,
        )
        assert proc.returncode == 0
        quad_runtimes.append(float(proc.stdout.strip()))

    # Run linear fix at largest size (1600)
    for _ in range(repeats):
        proc = subprocess.run(
            ["docker", "run", "--rm", "-i", "collab-sandbox-python:0.1.0", "python", "-c", linear_code],
            input="1600",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=10,
        )
        assert proc.returncode == 0
        linear_runtimes.append(float(proc.stdout.strip()))

    import numpy as np

    orig_median = float(np.median(quad_runtimes))
    fix_median = float(np.median(linear_runtimes))

    # Verify that the linear fix is dramatically faster than the quadratic version
    verified, speedup, reason = compare_runtimes(orig_median, fix_median, noise_margin_pct=5.0)

    assert verified is True
    assert speedup is not None
    assert speedup > 50.0  # Linear vs O(N^2) at N=1600 is substantially faster
    assert "Verified improvement" in reason

