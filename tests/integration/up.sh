#!/usr/bin/env bash
# Build and start every service whose folder has a Dockerfile (T-08).
# Placeholder folders are skipped, so this works at any stage of Sprint 1.
#
#   bash tests/integration/up.sh
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

profiles=()
for line in "${SERVICES[@]}"; do
  read -r name folder _ _ <<<"$line"
  if service_present "$folder"; then
    profiles+=("$name")
    echo "start  $name"
  else
    echo "skip   $name ($folder/ has no Dockerfile yet)"
  fi
done

if [ "${#profiles[@]}" -eq 0 ]; then
  echo "No service has a Dockerfile yet. Nothing to start."
  exit 0
fi

cd "$REPO_ROOT"
COMPOSE_PROFILES="$(IFS=,; echo "${profiles[*]}")" docker compose up -d --build
