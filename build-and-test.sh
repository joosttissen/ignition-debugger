#!/usr/bin/env bash
# build-and-test.sh
#
# Single script that:
#   1. Builds the Ignition module (Gradle)
#   2. Clears the Ignition module JAR cache inside the running container
#      (forces Ignition to re-extract the new .modl on next start)
#   3. Restarts the Docker gateway container
#   4. Waits for the gateway to become healthy
#   5. Waits for the debugger module to write its registry file
#   6. Runs the mock-mode E2E test (no Docker required, always runs)
#   7. Runs the Docker-mode E2E test against the real Ignition gateway
#
# Usage:
#   bash build-and-test.sh [--mock-only] [--keep-running]
#
#   --mock-only     Skip the Docker restart and Docker-mode test.
#                   Useful for fast iteration on pure logic changes.
#   --keep-running  Leave the Docker container running after the test.
#                   Default: leave it running (Docker is NOT stopped).
#
# Requirements:
#   - Docker + Docker Compose
#   - Java (for Gradle)
#   - Node.js + npm (for the E2E test)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MOCK_ONLY=false
KEEP_RUNNING=true

for arg in "$@"; do
    case "$arg" in
        --mock-only)    MOCK_ONLY=true ;;
        --keep-running) KEEP_RUNNING=true ;;
        *) echo "[build-and-test] Unknown argument: $arg"; exit 1 ;;
    esac
done

log()  { echo "[build-and-test] $*"; }
die()  { echo "[build-and-test] ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Step 1 – Build the Ignition module
# ---------------------------------------------------------------------------

log "━━━ Step 1 – Build Ignition module ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
cd "${SCRIPT_DIR}/ignition-module"
./gradlew zipModule
MODL_FILE="${SCRIPT_DIR}/ignition-module/build/ignition-debugger.unsigned.modl"
[[ -f "${MODL_FILE}" ]] || die ".modl not found after build: ${MODL_FILE}"
log "Module built: ${MODL_FILE}"

# ---------------------------------------------------------------------------
# Step 2 – Run mock-mode E2E test (fast, no Docker)
# ---------------------------------------------------------------------------

log "━━━ Step 2 – Mock-mode E2E test ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
cd "${SCRIPT_DIR}/e2e-test"
npm install --silent
npm test
log "Mock-mode test passed."

if [[ "${MOCK_ONLY}" == "true" ]]; then
    log "━━━ --mock-only set; skipping Docker steps ━━━━━━━━━━━━━━━━━━━━━━"
    log "All done. ✓"
    exit 0
fi

# ---------------------------------------------------------------------------
# Step 3 – Clear stale JAR cache inside the container (if running)
# ---------------------------------------------------------------------------

log "━━━ Step 3 – Clear module JAR cache ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
JAR_CACHE_PATH="/usr/local/bin/ignition/data/jar-cache/dev.ignition.debugger"
if docker inspect ignition-gateway > /dev/null 2>&1; then
    docker exec ignition-gateway rm -rf "${JAR_CACHE_PATH}" 2>/dev/null && \
        log "JAR cache cleared." || log "Cache already absent – OK."
else
    log "Container not running yet – cache clear skipped."
fi

# ---------------------------------------------------------------------------
# Step 4 – Restart Docker gateway
# ---------------------------------------------------------------------------

log "━━━ Step 4 – Restart Docker gateway ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
cd "${SCRIPT_DIR}"
# Always do a hard restart so the new .modl + cleared JAR cache take effect.
# 'docker compose restart' reuses the existing container process; we need a
# full stop+start so Ignition re-scans user-lib/modules on startup.
docker compose down --timeout 15 2>/dev/null || true
# Remove any leftover registry file (the module's shutdown hook may not have
# run if the container was killed, and a stale file would confuse the wait loop).
rm -f "${SCRIPT_DIR}"/debugger-gateway-registry/gateway-*.json 2>/dev/null || true
docker compose up -d
log "Container restarted."

# ---------------------------------------------------------------------------
# Step 5 – Wait for gateway health
# ---------------------------------------------------------------------------

log "━━━ Step 5 – Wait for gateway health ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
HEALTH_URL="http://localhost:8088/StatusPing"
MAX_HEALTH_SECONDS=180
INTERVAL=5
elapsed=0
while true; do
    if curl -sf "${HEALTH_URL}" > /dev/null 2>&1; then
        log "Gateway healthy (${elapsed}s)."
        break
    fi
    if (( elapsed >= MAX_HEALTH_SECONDS )); then
        die "Gateway did not become healthy within ${MAX_HEALTH_SECONDS}s."
    fi
    sleep "${INTERVAL}"
    (( elapsed += INTERVAL ))
    log "  … waiting for health (${elapsed}/${MAX_HEALTH_SECONDS}s)"
done

# ---------------------------------------------------------------------------
# Step 6 – Wait for registry file
# ---------------------------------------------------------------------------

log "━━━ Step 6 – Wait for registry file ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
REGISTRY_DIR="${SCRIPT_DIR}/debugger-gateway-registry"
mkdir -p "${REGISTRY_DIR}"
MAX_REG_SECONDS=90
elapsed=0
REGISTRY_FILE=""
while true; do
    REGISTRY_FILE="$(find "${REGISTRY_DIR}" -name 'gateway-*.json' 2>/dev/null | head -1)"
    if [[ -n "${REGISTRY_FILE}" ]]; then
        log "Registry file found: ${REGISTRY_FILE}"
        break
    fi
    if (( elapsed >= MAX_REG_SECONDS )); then
        die "No registry file appeared in ${MAX_REG_SECONDS}s.
  Is the Ignition Debugger module installed?
  Open http://localhost:8088/web/config/modules and upload:
    ${MODL_FILE}"
    fi
    sleep 3
    (( elapsed += 3 ))
    log "  … waiting for registry (${elapsed}/${MAX_REG_SECONDS}s)"
done

# ---------------------------------------------------------------------------
# Step 7 – Run Docker-mode E2E test
# ---------------------------------------------------------------------------

log "━━━ Step 7 – Docker-mode E2E test ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
cd "${SCRIPT_DIR}/e2e-test"
IGNITION_GATEWAY_REGISTRY_FILE="${REGISTRY_FILE}" npm test

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log "All tests passed. ✓"
if [[ "${KEEP_RUNNING}" == "false" ]]; then
    log "Stopping Docker…"
    cd "${SCRIPT_DIR}"
    docker compose down
fi
