#!/usr/bin/env bash
# US-45 integration test: manual module save, dirty flag, permissions, and module-updated broadcast.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-http://localhost:8081}"

echo "=== US-45: Save a module manually ==="
node "$ROOT_DIR/client/tests/integration/us45-save-module.mjs"