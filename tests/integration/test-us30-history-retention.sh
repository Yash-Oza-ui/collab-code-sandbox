#!/usr/bin/env bash
# US-30 integration: Spring configuration, scheduled sweep and shared room deletion.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if command -v mvn >/dev/null 2>&1; then
  (cd "$root/sync-service" && mvn -B -Dtest=RunHistoryRetentionIntegrationTest test)
elif [[ -x "$root/execution-service/mvnw" ]]; then
  "$root/execution-service/mvnw" -B -f "$root/sync-service/pom.xml" -Dtest=RunHistoryRetentionIntegrationTest test
else
  echo "SKIP us30-history-retention: Maven and the repository Maven wrapper are unavailable"
  exit 0
fi
