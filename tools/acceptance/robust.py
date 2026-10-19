"""Sidecar-down robustness (M1): stop the sidecar, then
  (a) a bot logs in, is teleported into an already generated area, is killed and respawns;
  (b) an ungenerated chunk whose cell is in the store is force-loaded (must generate without the sidecar);
  (c) an ungenerated chunk whose cell is NOT in the store is force-loaded (must wait: no crash, no placeholder,
      server keeps ticking), then the sidecar is started again and the chunk must generate (recovery).

    uv run --project sidecar python tools/acceptance/robust.py AX AY AZ  BX BZ  CX CZ [RECOVER_MINUTES=15]
"""
from __future__ import annotations

import re
import subprocess
import sys
import time
from pathlib import Path

import acc
from flight import BOT, log_lines_since, log_pos, parse_tick, start_bot, wait_online

HERE = Path(__file__).resolve().parent
DIM = acc.DIM


def sh(*args):
    return subprocess.run([str(HERE / "server.sh"), *args], capture_output=True, text=True).stdout.strip()


def loaded(r, x, z):
    return "passed" in r(f"execute in {DIM} if loaded {x} 0 {z}")


def status(r):
    return r("swiss admin status").strip().splitlines()[-1]


def ticks(r, label):
    q = parse_tick(r("tick query"))
    print(f"  [{label}] tick query avg/P50/P95/P99 ms: {q}; status: {status(r)}", flush=True)
    return q


def main(a, b, c, recover_min=15.0):
    r = acc.Rcon()
    lp = log_pos()
    print("sidecar stop:", sh("sidecar-stop"), flush=True)
    time.sleep(2)
    ticks(r, "sidecar down")

    # (a) login, teleport into a saved area, death and respawn
    bot = start_bot(["wait:14", "respawn", "wait:8"])
    ok = wait_online(r)
    r(f"op {BOT}")
    r(f"gamemode survival {BOT}")
    r(f"execute in {DIM} run tp {BOT} {a[0]} {a[1]} {a[2]}")
    time.sleep(5)
    pos = r(f"data get entity {BOT} Pos")
    r(f"kill {BOT}")
    out, _ = bot.communicate(timeout=60)
    print(f"(a) bot joined: {ok}; position after tp: {pos.strip()[-60:]}; bot log: "
          + " | ".join(l for l in out.splitlines() if l.startswith(("spawn", "died", "disconnected", "position"))),
          flush=True)
    ticks(r, "after (a)")

    # (b) ungenerated chunk, cell in store
    t0 = time.time()
    r(f"execute in {DIM} run forceload add {b[0]} {b[1]}")
    while not loaded(r, *b) and time.time() - t0 < 120:
        time.sleep(1)
    print(f"(b) chunk at x {b[0]} z {b[1]} (cell in store) loaded: {loaded(r, *b)} after {time.time() - t0:.0f}s", flush=True)
    r(f"execute in {DIM} run forceload remove {b[0]} {b[1]}")

    # (c) ungenerated chunk, cell not in store
    t0 = time.time()
    r(f"execute in {DIM} run forceload add {c[0]} {c[1]}")
    qs = []
    while time.time() - t0 < 60:
        time.sleep(5)
        qs.append(ticks(r, f"(c) sidecar down t={time.time() - t0:.0f}s"))
    print(f"(c) chunk at x {c[0]} z {c[1]} (cell missing) loaded while sidecar down: {loaded(r, *c)}", flush=True)
    print("sidecar start:", sh("sidecar-start"), flush=True)
    t1 = time.time()
    while not loaded(r, *c) and time.time() - t1 < recover_min * 60:
        time.sleep(10)
        if int(time.time() - t1) % 60 < 10:
            ticks(r, f"(c) recovering t={time.time() - t1:.0f}s")
    print(f"(c) after sidecar restart: loaded {loaded(r, *c)} after {time.time() - t1:.0f}s", flush=True)
    r(f"execute in {DIM} run forceload remove {c[0]} {c[1]}")
    lines = log_lines_since(lp)
    keys = ("Sidecar unreachable", "Sidecar reachable again", "waited", "placeholder", "Exception", "ERROR")
    for k in keys:
        hits = [l for l in lines if k in l]
        print(f"log '{k}': {len(hits)}" + (f"  e.g. {hits[0][:220]}" if hits else ""))
    print("final:", status(r))


if __name__ == "__main__":
    v = [float(x) for x in sys.argv[1:]]
    main((v[0], v[1], v[2]), (int(v[3]), int(v[4])), (int(v[5]), int(v[6])), *(v[7:8]))
