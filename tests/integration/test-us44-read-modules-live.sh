#!/usr/bin/env bash
# US-44 integration test: Observer reads every module live, receives module-updated, and resyncs on reconnect.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-http://localhost:8081}"

echo "=== US-44: Read every module live ==="
node "$ROOT_DIR/client/tests/integration/us44-read-modules-live.mjs"
