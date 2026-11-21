#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../sync-service"
# Deterministic acceptance tests use a fake clock, so no real 30-second delay.
mvn -B -Dtest=HostDepartureManagerTest test
