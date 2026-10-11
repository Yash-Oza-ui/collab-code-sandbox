#!/usr/bin/env bash
# Shared helpers for integration tests (T-08). Source this file; don't run it.

INTEGRATION_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$INTEGRATION_DIR/../.." && pwd)"

# Settings priority: variables already exported in the shell (e.g. by CI),
# then your local .env (git-ignored), then the defaults in .env.example.
_load_env_defaults() {
  local file="$1" key value
  [ -f "$file" ] || return 0
  while IFS='=' read -r key value || [ -n "$key" ]; do
    key="${key%%[[:space:]]*}"
    value="${value%$'\r'}"                       # tolerate Windows line endings
    case "$key" in ''|\#*) continue ;; esac
    if [ -z "${!key+x}" ]; then export "$key=$value"; fi
  done < "$file"
}
_load_env_defaults "$REPO_ROOT/.env"
_load_env_defaults "$REPO_ROOT/.env.example"

# Every service the stack knows about: "<name> <folder> <port-var> <health-path-var>".
# Add a line here when a new service joins the stack.
SERVICES=(
  "client            client            CLIENT_PORT            CLIENT_HEALTH_PATH"
  "sync-service      sync-service      SYNC_SERVICE_PORT      SYNC_SERVICE_HEALTH_PATH"
  "execution-service execution-service EXECUTION_SERVICE_PORT EXECUTION_SERVICE_HEALTH_PATH"
  "ai-ml-service     ai-ml-service     AI_ML_SERVICE_PORT     AI_ML_SERVICE_HEALTH_PATH"
)

# A service is "present" once its folder has a Dockerfile. Placeholder folders
# (only .gitkeep) are skipped, so the harness never breaks on a missing service.
service_present() {
  [ -f "$REPO_ROOT/$1/Dockerfile" ]
}

# Base URL of a service by name, e.g. service_url sync-service -> http://localhost:8081
service_url() {
  local line name folder port_var path_var
  for line in "${SERVICES[@]}"; do
    read -r name folder port_var path_var <<<"$line"
    if [ "$name" = "$1" ]; then
      echo "http://localhost:${!port_var}"
      return 0
    fi
  done
  echo "unknown service: $1" >&2
  return 1
}

pass() { printf 'PASS  %-18s %s\n' "$1" "$2"; }
skip() { printf 'SKIP  %-18s %s\n' "$1" "$2"; }
fail() { printf 'FAIL  %-18s %s\n' "$1" "$2"; }
