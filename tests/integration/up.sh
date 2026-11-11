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
export COMPOSE_PROFILES="$(IFS=,; echo "${profiles[*]}")"

# Build and start, retrying once on a transient failure. Image pulls and the
# Maven dependency downloads inside the service builds occasionally flake in CI
# (network blips, registry rate limits); a warm retry then succeeds without a
# manual "Re-run jobs". A genuine build or startup error still fails BOTH
# attempts and the job, so real breakage is never hidden.
attempts="${UP_ATTEMPTS:-2}"
for attempt in $(seq 1 "$attempts"); do
  if docker compose up -d --build; then
    exit 0
  fi
  if [ "$attempt" -lt "$attempts" ]; then
    echo "docker compose up failed (attempt $attempt/$attempts); cleaning up and retrying..."
    docker compose down -v --remove-orphans || true
    sleep 5
  fi
done

echo "docker compose up failed after $attempts attempt(s)."
exit 1
