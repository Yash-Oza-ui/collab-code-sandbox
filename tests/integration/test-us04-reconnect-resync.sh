#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

source "$SCRIPT_DIR/lib.sh"

# Respect an explicitly provided backend URL.
SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"
export SYNC_SERVICE_URL

cd "$ROOT"

# Locate Node.js on Linux, Git Bash, or Windows.
if command -v node >/dev/null 2>&1; then
    NODE=node
elif command -v node.exe >/dev/null 2>&1; then
    NODE=node.exe
elif [ -x "/c/Program Files/nodejs/node.exe" ]; then
    NODE="/c/Program Files/nodejs/node.exe"
else
    echo "ERROR: Node.js is not available in this Bash environment."
    echo "Install Node.js in this environment or run the tests using PowerShell."
    exit 1
fi

echo "=== Node.js ==="
"$NODE" --version

echo "=== US-04 Pair-mode reconnect/resync ==="
"$NODE" client/tests/integration/us04-reconnect-resync.mjs

echo "=== US-04 Open-mode multi-module reconnect/resync ==="
"$NODE" client/tests/integration/us04-open-modules.mjs

echo "PASS: US-04 Pair and Open reconnect/resync"
