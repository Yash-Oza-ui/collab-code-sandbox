#!/usr/bin/env bash
# US-10 integration test: real Docker run-stage time and peak-memory metrics.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SERVICE="execution-service"
if ! service_present "$SERVICE"; then
  skip "$SERVICE" "(no Dockerfile)"
  exit 0
fi

BASE="$(service_url "$SERVICE")"
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  fail "health" "$BASE/actuator/health -> $health_code (service not up?)"
  exit 1
fi
pass "health" "$BASE/actuator/health -> 200"

new_uuid() {
  printf -v REPLY '%04x%04x-%04x-4%03x-%x%03x-%04x%04x%04x' \
    $RANDOM $RANDOM $RANDOM $((RANDOM % 4096)) $((8 + RANDOM % 4)) $((RANDOM % 4096)) \
    $RANDOM $RANDOM $RANDOM
}

post_run() {
  local code="$1"
  local request_id session_id body output status
  new_uuid; request_id="$REPLY"
  new_uuid; session_id="$REPLY"
  body="$(python3 - "$request_id" "$session_id" "$code" <<'PY'
import json, sys
print(json.dumps({
    "request_id": sys.argv[1],
    "room_id": "room-us10-metrics",
    "session_id": sys.argv[2],
    "language": "python",
    "source": {
        "kind": "inline",
        "files": [{"name": "main.py", "content": sys.argv[3]}],
        "entry": "main.py"
    }
}))
PY
)"
  output="$(mktemp)"
  status="$(curl -sS -o "$output" -w '%{http_code}' --max-time 20 \
    -X POST "$BASE/v1/runs" \
    -H "Content-Type: application/json" \
    -d "$body" 2>/dev/null || echo "000")"
  if [ "$status" != "200" ]; then
    cat "$output"
    rm -f "$output"
    fail "run-http" "expected 200, got $status"
    return 1
  fi
  cat "$output"
  rm -f "$output"
}

assert_metrics() {
  local label="$1" response="$2" min_time="$3" min_memory="$4" expected_exit="$5"
  python3 - "$label" "$response" "$min_time" "$min_memory" "$expected_exit" <<'PY'
import json, sys
label, raw, min_time, min_memory, expected_exit = sys.argv[1:]
result = json.loads(raw)
time_ms = result["execution_time_ms"]
memory_mb = result["peak_memory_mb"]
assert isinstance(time_ms, (int, float)) and time_ms >= float(min_time), f"{label}: time {time_ms} < {min_time}"
assert isinstance(memory_mb, (int, float)) and memory_mb >= float(min_memory), f"{label}: memory {memory_mb} < {min_memory}"
assert result["exit_code"] == int(expected_exit), f"{label}: exit {result['exit_code']} != {expected_exit}"
print(f"PASS  {label:18s} {time_ms:.1f} ms, {memory_mb:.1f} MB, exit {result['exit_code']}")
PY
}

# Container startup must not inflate a 1.2-second user program's execution time.
sleep_result="$(post_run $'import time\ntime.sleep(1.2)\n')"
assert_metrics "sleep-run" "$sleep_result" 1000 0.1 0

# Retain a 50 MB allocation long enough for the cgroup peak counter to observe it.
memory_result="$(post_run $'import time\ndata = bytearray(50 * 1024 * 1024)\ntime.sleep(0.8)\nprint(len(data))\n')"
assert_metrics "memory-run" "$memory_result" 500 40 0

# Failed user programs must still carry the measurements collected before exit.
failure_result="$(post_run $'raise RuntimeError("US-10 metrics failure test")\n')"
python3 - "$failure_result" <<'PY'
import json, sys
r = json.loads(sys.argv[1])
assert r["exit_code"] != 0, f"expected non-zero exit, got {r['exit_code']}"
assert r["failure_kind"] == "non_zero_exit", f"expected failure_kind non_zero_exit, got {r['failure_kind']}"
assert r["execution_time_ms"] > 0, f"expected execution_time_ms > 0, got {r['execution_time_ms']}"
assert r["peak_memory_mb"] > 0, f"expected peak_memory_mb > 0, got {r['peak_memory_mb']}"
print(f"PASS  failed-run-metrics {r['execution_time_ms']:.1f} ms, {r['peak_memory_mb']:.1f} MB, exit {r['exit_code']}")
PY

# Syntax errors must still measure the interpreter startup and report compile_error.
syntax_result="$(post_run $'def broken(:\n    pass\n')"
python3 - "$syntax_result" <<'PY'
import json, sys
r = json.loads(sys.argv[1])
assert r["failure_kind"] == "compile_error", f"expected failure_kind compile_error, got {r['failure_kind']}"
assert r["execution_time_ms"] > 0, f"expected execution_time_ms > 0, got {r['execution_time_ms']}"
assert r["peak_memory_mb"] > 0, f"expected peak_memory_mb > 0, got {r['peak_memory_mb']}"
assert r["compile_time_ms"] is None, f"expected compile_time_ms None, got {r['compile_time_ms']}"
print(f"PASS  syntax-err-metrics {r['execution_time_ms']:.1f} ms, {r['peak_memory_mb']:.1f} MB, failure compile_error")
PY

echo "US-10 RUN-METRICS TEST PASSED"
