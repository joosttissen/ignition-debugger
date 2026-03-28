#!/usr/bin/env bash
# run-e2e-test.sh
#
# Full end-to-end test runner for the Ignition Debugger.
#
# Prerequisites (must be completed manually before running this script):
#   1. Build the Ignition module:
#        cd ../ignition-module && ./gradlew build
#      The unsigned .modl file will be at:
#        ignition-module/build/ignition-debugger-unsigned.modl
#
#   2. Install the module in the running Ignition gateway (once per container):
#        Open http://localhost:8088/web/config/modules
#        Click "Install or Upgrade a Module" → upload the .modl file.
#        Accept the unsigned module warning.
#
# What this script does:
#   1. Starts the Ignition gateway in Docker (docker compose up -d).
#   2. Waits until the gateway is healthy (up to 3 minutes).
#   3. Waits until the debugger module has written its registry file.
#   4. Runs the Node.js E2E test against the real gateway.
#   5. Stops Docker (unless --keep-running is passed).
#
# Usage:
#   cd e2e-test
#   bash run-e2e-test.sh [--keep-running]
#
# Environment variables:
#   GATEWAY_REGISTRY_DIR  Directory where the gateway registry files live.
#                         Default: ../debugger-gateway-registry

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

KEEP_RUNNING=false
for arg in "$@"; do
    case "$arg" in
        --keep-running) KEEP_RUNNING=true ;;
        *) echo "Unknown argument: $arg"; exit 1 ;;
    esac
done

GATEWAY_REGISTRY_DIR="${GATEWAY_REGISTRY_DIR:-${REPO_ROOT}/debugger-gateway-registry}"
GATEWAY_HEALTH_URL="http://localhost:8088/StatusPing"
COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"

# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

log()  { echo "[e2e] $*" >&2; }
warn() { echo "[e2e] WARN: $*" >&2; }
die()  { echo "[e2e] ERROR: $*" >&2; exit 1; }

wait_for_gateway() {
    local max_seconds=180
    local interval=5
    local elapsed=0

    log "Waiting for Ignition gateway to become healthy (up to ${max_seconds}s)…"
    while true; do
        if curl -sf "${GATEWAY_HEALTH_URL}" > /dev/null 2>&1; then
            log "Gateway is healthy."
            return 0
        fi
        if (( elapsed >= max_seconds )); then
            die "Gateway did not become healthy within ${max_seconds}s."
        fi
        sleep "${interval}"
        (( elapsed += interval ))
        log "  … still waiting (${elapsed}/${max_seconds}s)"
    done
}

wait_for_registry_file() {
    local max_seconds=60
    local interval=3
    local elapsed=0

    log "Waiting for gateway registry file in ${GATEWAY_REGISTRY_DIR} …"
    mkdir -p "${GATEWAY_REGISTRY_DIR}"

    while true; do
        local registry_file
        registry_file="$(find "${GATEWAY_REGISTRY_DIR}" -name 'gateway-*.json' | head -1)"
        if [[ -n "${registry_file}" ]]; then
            log "Found registry file: ${registry_file}"
            echo "${registry_file}"
            return 0
        fi
        if (( elapsed >= max_seconds )); then
            die "No gateway registry file appeared in ${max_seconds}s.
Make sure the Ignition Debugger module is installed in the gateway.
Install it at: http://localhost:8088/web/config/modules"
        fi
        sleep "${interval}"
        (( elapsed += interval ))
        log "  … still waiting (${elapsed}/${max_seconds}s)"
    done
}

# ---------------------------------------------------------------------------
# 1. Start Docker Compose
# ---------------------------------------------------------------------------

log "Starting Ignition gateway via Docker Compose…"
docker compose -f "${COMPOSE_FILE}" up -d

# ---------------------------------------------------------------------------
# 2. Wait for gateway health
# ---------------------------------------------------------------------------

wait_for_gateway

# ---------------------------------------------------------------------------
# 3. Check module install
# ---------------------------------------------------------------------------

log ""
log "─────────────────────────────────────────────────────────────"
log " ACTION REQUIRED (first run only)"
log "─────────────────────────────────────────────────────────────"
log " If this is the first time running after a fresh container,"
log " the Ignition Debugger module must be installed:"
log ""
log "   1. Open http://localhost:8088/web/config/modules"
log "   2. Click 'Install or Upgrade a Module'"
log "   3. Upload: ${REPO_ROOT}/ignition-module/build/ignition-debugger-unsigned.modl"
log "   4. Accept the unsigned-module warning."
log ""
log " The module writes the registry file the test depends on."
log " Waiting for registry file now…"
log "─────────────────────────────────────────────────────────────"
log ""

# ---------------------------------------------------------------------------
# 4. Wait for registry file
# ---------------------------------------------------------------------------

REGISTRY_FILE="$(wait_for_registry_file)"

# ---------------------------------------------------------------------------
# 5. Install Node.js dependencies and run the test
# ---------------------------------------------------------------------------

log "Installing test dependencies…"
(cd "${SCRIPT_DIR}" && npm install --silent)

log "Running E2E test against real Ignition gateway…"
IGNITION_GATEWAY_REGISTRY_FILE="${REGISTRY_FILE}" \
    npm --prefix "${SCRIPT_DIR}" test

TEST_EXIT=$?

# ---------------------------------------------------------------------------
# 6. Stop Docker (unless --keep-running)
# ---------------------------------------------------------------------------

if [[ "${KEEP_RUNNING}" == "false" ]]; then
    log "Stopping Docker Compose…"
    docker compose -f "${COMPOSE_FILE}" down
else
    log "Leaving Docker Compose running (--keep-running was set)."
fi

exit "${TEST_EXIT}"
