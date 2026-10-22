"""Starts a private sidecar on 127.0.0.1:8091 (never 8080), prefetches cells, then asks for one "now" cell and
reports its time-to-ready and the completion order.

    python bench/queue_experiment.py SRC_SIDECAR_DIR SCENARIO_DIR NOW_CELL PREFETCH_CELL...
SCENARIO_DIR holds store/ raw/ scratch/ (see prep_scenario.py). Kills only the server process it started."""
import json, os, subprocess, sys, time, urllib.request

src, scen, now, *pre = sys.argv[1:]
assert "sidecar-speed" in scen
port = 8091
env = {**os.environ, "SWISSDATA_HOST": "127.0.0.1", "SWISSDATA_PORT": str(port), "SWISSDATA_RAW": f"{scen}/raw",
       "SWISSDATA_STORE": f"{scen}/store", "SWISSDATA_SCRATCH": f"{scen}/scratch"}
log = open(f"{scen}/server.log", "w")
proc = subprocess.Popen(["nice", "-n", "19", sys.executable, "-m", "swissdata.server"], cwd=src, env=env,
                        stdout=log, stderr=subprocess.STDOUT)
base = f"http://127.0.0.1:{port}"


def call(path, body=None):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=300))


def cell(k):
    e, n = k.split("_")
    return [int(e), int(n)]


try:
    for _ in range(120):
        try:
            call("/health"); break
        except Exception:
            time.sleep(0.5)
    t0 = time.time()
    call("/prefetch", {"cells": [cell(k) for k in pre]}) if False else call("/request", {"cells": [cell(k) for k in pre], "priority": "prefetch"})
    time.sleep(1.0)  # prefetch builds are running
    t1 = time.time()
    done = {}
    r = call(f"/wait?cell={now.replace('_', ',')}&timeoutMs=100000")
    done[now] = round(time.time() - t0, 1)
    print("now cell state", r["state"], "time-to-ready after request", round(time.time() - t1, 1), "s")
    while len(done) < len(pre) + 1 and time.time() - t0 < 280:
        for k in pre:
            if k not in done and call("/request", {"cells": [cell(k)], "priority": "prefetch"})["cells"][0]["state"] == "ready":
                done[k] = round(time.time() - t0, 1)
        time.sleep(1)
    print("completion (s since start):", json.dumps(dict(sorted(done.items(), key=lambda x: x[1]))))
finally:
    proc.terminate()
    try:
        proc.wait(20)
    except subprocess.TimeoutExpired:
        proc.kill()
