#!/bin/bash
# Block-identity check of the fast feature step (FastDecoration) against vanilla's applyBiomeDecoration, on four areas
# (Interlaken, Grindelwald, Faulhorn, Grimsel). Headless server on 25700/25701 (rcon pw dev); needs a store holding the
# cells: SWISSWORLD_STORE=<dir> (default: the dev store). Exit 1 on any difference. See DecoCheck.java.
set -u
WT=$(cd "$(dirname "$0")/.." && pwd)
RUN=$WT/mod/run
rm -rf "$RUN/decocheck"; mkdir -p "$RUN/decocheck/serverconfig"
echo 'sidecarUrl = "http://127.0.0.1:8099"' > "$RUN/decocheck/serverconfig/swissworld-server.toml"   # dead port: store only
echo "eula=true" > "$RUN/eula.txt"
printf 'online-mode=false\nserver-port=25700\nenable-rcon=true\nrcon.port=25701\nrcon.password=dev\nlevel-name=decocheck\nlevel-seed=4242\nmax-tick-time=-1\n' > "$RUN/server.properties"
cd "$WT/mod"
export SWISSWORLD_SERVER_JVM="-Xmx4g -Dmax.bg.threads=4"
nice -n 10 ./gradlew runServer --offline > "$RUN/decocheck.log" 2>&1 &
GPID=$!
for i in $(seq 300); do python3 -I "$WT/tools/rcon.py" 25701 dev "list" >/dev/null 2>&1 && break; sleep 2; done
rc=0
for a in "46.6863 7.8632" "46.6243 8.0413" "46.6760 7.9996" "46.5714 8.3326"; do
  out=$(python3 -I "$WT/tools/rcon.py" 25701 dev "swiss admin decocheck $a 3" 2>&1); echo "$out"
  echo "$out" | grep -q "DECOCHECK OK" || rc=1
done
python3 -I "$WT/tools/rcon.py" 25701 dev "stop" >/dev/null 2>&1
wait $GPID
exit $rc
