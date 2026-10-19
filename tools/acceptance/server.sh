#!/usr/bin/env bash
# tools/acceptance/server.sh setup|start|stop|wait|wipe|rcon <cmd>...|sidecar-start|sidecar-stop
# One dev server in mod/run (ports from env.sh), private store, pinned level-seed, heap ACC_HEAP.
set -euo pipefail
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
. "$(dirname "$SELF")/env.sh"
rcon() { python3 -I "$ACC_REPO/tools/rcon.py" "$ACC_RCON_PORT" "$ACC_RCON_PASS" "$@"; }
case "${1:-}" in
setup)
  mkdir -p "$ACC_RUN/config"
  echo "eula=true" > "$ACC_RUN/eula.txt"
  cat > "$ACC_RUN/server.properties" <<P
online-mode=false
server-port=$ACC_SERVER_PORT
enable-rcon=true
rcon.port=$ACC_RCON_PORT
rcon.password=$ACC_RCON_PASS
level-name=$ACC_LEVEL
level-seed=$ACC_SEED
network-compression-threshold=-1
difficulty=normal
spawn-monsters=true
spawn-animals=true
allow-flight=true
view-distance=10
simulation-distance=10
spawn-protection=0
max-tick-time=120000
sync-chunk-writes=false
P
  cat > "$ACC_RUN/config/swissworld-server.toml" <<P
sidecarUrl = "http://127.0.0.1:$ACC_SIDECAR_PORT"
store = "$ACC_STORE"
prefetch = true
P
  echo "run dir ready: $ACC_RUN";;
start)
  cd "$ACC_REPO/mod"
  export SWISSWORLD_STORE="$ACC_STORE" JAVA_TOOL_OPTIONS="-Xmx$ACC_HEAP"
  nohup ./gradlew --no-daemon runServer --console=plain > "$ACC_RUN/../run-acc-server.log" 2>&1 &
  echo $! > "$ACC_RUN/../run-acc-server.pid"
  "$SELF" wait;;
wait)
  for _ in $(seq 400); do
    if rcon "list" >/dev/null 2>&1; then echo "server up"; exit 0; fi; sleep 1
  done; echo "server did not come up" >&2; exit 1;;
stop)
  rcon "stop" >/dev/null 2>&1 || true
  for _ in $(seq 120); do lsof -iTCP:"$ACC_SERVER_PORT" -sTCP:LISTEN >/dev/null 2>&1 || { echo stopped; exit 0; }; sleep 1; done
  echo "server still listening" >&2; exit 1;;
wipe)
  rm -rf "${ACC_RUN:?}/$ACC_LEVEL"; echo "wiped $ACC_RUN/$ACC_LEVEL";;
rcon)
  shift; rcon "$@";;
sidecar-start)
  mkdir -p "$ACC_SCRATCH"
  cd "$ACC_REPO/sidecar"
  SWISSDATA_STORE="$ACC_STORE" SWISSDATA_SCRATCH="$ACC_SCRATCH" SWISSDATA_PORT="$ACC_SIDECAR_PORT" \
    nohup uv run python -m swissdata.server > "$ACC_STORE/../sidecar.log" 2>&1 &
  echo $! > "$ACC_STORE/../sidecar.pid"
  for _ in $(seq 90); do curl -fsS "http://127.0.0.1:$ACC_SIDECAR_PORT/health" >/dev/null 2>&1 && { echo "sidecar up"; exit 0; }; sleep 1; done
  echo "sidecar did not come up" >&2; exit 1;;
sidecar-stop)
  pid=$(lsof -tiTCP:"$ACC_SIDECAR_PORT" -sTCP:LISTEN || true)
  [ -n "$pid" ] && kill $pid; sleep 1; echo "sidecar stopped";;
*) sed -n 2,3p "$0"; exit 2;;
esac
