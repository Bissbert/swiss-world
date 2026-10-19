#!/usr/bin/env bash
# Local playtest: starts the swissdata sidecar on :8080 and a NeoForge dev client from this checkout,
# both on the shared store. Stops the sidecar when the client exits.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
export SWISSDATA_RAW="$REPO/.data-cache/raw"
export SWISSDATA_STORE="$REPO/store"
export SWISSWORLD_STORE="$REPO/store"

if curl -fsS http://127.0.0.1:8080/status >/dev/null 2>&1; then
  echo "sidecar already running on :8080"
  SIDECAR_PID=""
else
  (cd "$REPO/sidecar" && exec uv run python -m swissdata.server) >"$REPO/mod/run-client-sidecar.log" 2>&1 &
  SIDECAR_PID=$!
  trap '[ -n "$SIDECAR_PID" ] && kill "$SIDECAR_PID" 2>/dev/null || true' EXIT
  for _ in $(seq 60); do curl -fsS http://127.0.0.1:8080/status >/dev/null 2>&1 && break; sleep 1; done
  echo "sidecar up (log: mod/run-client-sidecar.log)"
fi

cd "$REPO/mod" && ./gradlew runClient
