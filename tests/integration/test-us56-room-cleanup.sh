#!/usr/bin/env bash
# US-56 live smoke: Host departure ends an Open-mode room and triggers remote cleanup.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"
for service in sync-service execution-service ai-ml-service; do
  if ! service_present "$service"; then
    skip us56-room-cleanup "$service not built"
    exit 0
  fi
  base="$(service_url "$service")"
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base/$( [ "$service" = ai-ml-service ] && echo health || echo actuator/health )" || true)"
  if [ "$code" != 200 ]; then
    if [ "${CI:-false}" = true ]; then
      fail us56-room-cleanup "$service unavailable"
      exit 1
    fi
    skip us56-room-cleanup "$service not running"
    exit 0
  fi
done
SYNC_URL="$(service_url sync-service)" EXEC_URL="$(service_url execution-service)" AI_URL="$(service_url ai-ml-service)" \
INTERNAL_API_TOKEN="$INTERNAL_API_TOKEN" python3 "$dir/test-us56-room-cleanup.py"
pass us56-room-cleanup "Host timeout triggers room cleanup; repeat notification is safe"
