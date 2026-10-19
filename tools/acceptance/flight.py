"""MSPT while a headless bot flies at a fixed speed (spectator), plus mob census helpers.

    uv run --project sidecar python tools/acceptance/flight.py fly NAME X Y Z DX DZ [SPEED=30]
        bot "AccBot" logs in, rcon puts it into spectator at (X, Y, Z) in the Swiss dimension, it flies DX/DZ blocks
        at SPEED m/s; /tick query and /swiss admin status are sampled every 2 s; StallGuard/placeholder log lines in
        mod/run/logs/latest.log during the flight are counted.
    uv run --project sidecar python tools/acceptance/flight.py census X Y Z MINUTES
        bot stands (survival) at X Y Z at night; every 30 s counts hostile mobs (type=#minecraft:... via
        `execute if entity`) within 128 blocks, and those inside building columns (BSPN) per the cell data.

Needs the server (online-mode=false, network-compression-threshold=-1) from env.sh.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import time
from pathlib import Path

import acc

ROOT = Path(__file__).resolve().parents[2]
LOG = ROOT / "mod/run/logs/latest.log"
BOT = "AccBot"
DIM = acc.DIM


def start_bot(steps: list[str]) -> subprocess.Popen:
    port = os.environ.get("ACC_SERVER_PORT", "25710")
    return subprocess.Popen([sys.executable, "-I", str(ROOT / "tools/bot.py"), port, BOT, *steps],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)


def wait_online(r: acc.Rcon, t=60):
    t0 = time.time()
    while time.time() - t0 < t:
        if BOT in r("list"):
            return True
        time.sleep(0.5)
    return False


def parse_tick(q: str):
    m = re.search(r"Average time per tick: ([\d.]+)ms.*P50: ([\d.]+)ms P95: ([\d.]+)ms P99: ([\d.]+)ms", q, re.S)
    return tuple(float(v) for v in m.groups()) if m else None


def log_pos() -> int:
    return LOG.stat().st_size if LOG.exists() else 0


def log_lines_since(pos: int) -> list[str]:
    with LOG.open("rb") as f:
        f.seek(pos)
        return f.read().decode("utf-8", "replace").splitlines()


def fly(name, x, y, z, dx, dz, speed=30.0):
    r = acc.Rcon()
    dur = (dx * dx + dz * dz) ** 0.5 / speed
    bot = start_bot(["wait:6", f"walk:{dx},{dz},{speed}", "wait:3"])
    if not wait_online(r):
        print("bot did not join:", bot.stdout.read() if bot.poll() is not None else "")
        return
    r(f"op {BOT}")
    r(f"gamemode spectator {BOT}")
    r(f"execute in {DIM} run tp {BOT} {x} {y} {z}")
    lp = log_pos()
    s0 = r("swiss admin status")
    time.sleep(5.5)
    t0, samples, st = time.time(), [], []
    while bot.poll() is None and time.time() - t0 < dur + 30:
        q = parse_tick(r("tick query"))
        s = r("swiss admin status")
        if q:
            samples.append(q)
        m = re.search(r"(\d+) waiting, (\d+) aborted", s)
        if m:
            st.append((int(m.group(1)), int(m.group(2))))
        time.sleep(2)
    out = bot.stdout.read()
    s1 = r("swiss admin status")
    lines = log_lines_since(lp)
    stall = [l for l in lines if "StallGuard" in l or "waited" in l and "cells are missing" in l]
    ph = [l for l in lines if "placeholder" in l.lower()]
    g0 = re.search(r"chunks (\d+) generated", s0); g1 = re.search(r"chunks (\d+) generated", s1)
    print(f"== flight {name}: from ({x},{y},{z}) by ({dx},{dz}) at {speed} m/s ({dur:.0f} s planned); "
          f"load {os.getloadavg()[0]:.1f}")
    print("bot:", " | ".join(l for l in out.splitlines() if l.startswith(("walked", "disconnected", "spawn"))))
    if samples:
        import numpy as np
        a = np.array(samples)
        print(f"tick query samples {len(a)}: avg MSPT mean {a[:, 0].mean():.1f} max {a[:, 0].max():.1f}; "
              f"P95 median {np.median(a[:, 2]):.1f} max {a[:, 2].max():.1f}; P99 max {a[:, 3].max():.1f} ms")
        print(f"share of samples with avg MSPT > 50 ms: {100 * (a[:, 0] > 50).mean():.0f} %")
    if g0 and g1:
        print(f"chunks generated during flight: {int(g1.group(1)) - int(g0.group(1))}")
    if st:
        print(f"max chunks waiting {max(w for w, _ in st)}, aborted (placeholders) {max(a for _, a in st)}")
    print(f"log: {len(stall)} StallGuard lines, {len(ph)} placeholder lines"
          + (f"; e.g. {stall[0][:200]}" if stall else ""))
    print("status after:", s1.strip().splitlines()[-1] if s1.strip() else "")


HOSTILE = ["zombie", "skeleton", "creeper", "spider", "enderman", "witch", "drowned", "husk", "stray",
           "zombie_villager", "slime", "phantom", "cave_spider", "bogged"]


def census(x, y, z, minutes):
    from acc import swd
    r = acc.Rcon()
    bot = start_bot([f"wait:{minutes * 60 + 20}"])
    if not wait_online(r):
        print("bot did not join"); return
    r(f"op {BOT}")
    r(f"gamemode survival {BOT}")
    r(f"execute in {DIM} run tp {BOT} {x} {y} {z}")
    r(f"effect give {BOT} minecraft:resistance infinite 255 true")
    r(f"effect give {BOT} minecraft:invisibility infinite 0 true")
    r("time set 13000")
    r("gamerule doDaylightCycle false")
    time.sleep(10)
    total, inside_max, seen_inside = [], 0, []
    t0 = time.time()
    while time.time() - t0 < minutes * 60:
        n_all, inside = 0, 0
        # `execute as @e[...] run data get` returns no feedback over rcon, so enumerate one entity at a time
        # by tagging it (nearest untagged first).
        # `execute as @e[...] run data get` returns no feedback over rcon and `data get entity ... id` has no id,
        # so enumerate per hostile type, one entity at a time, tagging each one seen (nearest untagged first).
        r(f"execute in {DIM} run tag @e[tag=acc_seen] remove acc_seen")
        found = []
        for t in HOSTILE:
            sel = (f"@e[x={x},y={y},z={z},distance=..128,type=minecraft:{t},tag=!acc_seen,limit=1,sort=nearest]")
            for _ in range(200):
                res = r(f"execute in {DIM} run data get entity {sel} Pos")
                m = re.search(r"has the following entity data: \[([-\d.]+)d, ([-\d.]+)d, ([-\d.]+)d\]", res)
                if not m:
                    break
                r(f"execute in {DIM} run tag {sel} add acc_seen")
                found.append((None, *m.groups(), t))
        r(f"execute in {DIM} run tag @e[tag=acc_seen] remove acc_seen")
        n_ent = r(f"execute in {DIM} positioned {x} {y} {z} if entity @e[distance=..128,type=!minecraft:player]")
        kinds = {}
        for f in found:
            kinds[f[4]] = kinds.get(f[4], 0) + 1
        print(f"  all entities within 128: {n_ent.strip().splitlines()[-1] if n_ent.strip() else '?'}; hostiles {kinds}",
              flush=True)
        for nm, px, py, pz, eid in found:
            if eid.split(":")[-1] not in HOSTILE:
                continue
            n_all += 1
            bx, by, bz = int(float(px) // 1), int(float(py) // 1), int(float(pz) // 1)
            e, n = acc.lv95_of(bx, bz)
            c, ddx, ddz = acc.cell_xy(e, n)
            if c is None:
                continue
            for s in c.spans.get((ddx, ddz), []):
                lo, hi = float(swd.decode_height(s.bottom_h)) - 2000, float(swd.decode_height(s.top_h)) - 2000
                if s.part != 1 and lo <= by < hi:
                    inside += 1
                    seen_inside.append((eid, bx, by, bz))
                    break
        total.append(n_all)
        inside_max = max(inside_max, inside)
        print(f"t={time.time() - t0:5.0f}s hostiles within 128: {n_all}, inside building volumes: {inside}", flush=True)
        time.sleep(30)
    r("gamerule doDaylightCycle true")
    r(f"kick {BOT}")
    try:
        bot.wait(timeout=30)
    except subprocess.TimeoutExpired:
        bot.kill()
    print(f"== census at ({x},{y},{z}) for {minutes} min: max hostiles {max(total) if total else 0}, "
          f"max inside buildings {inside_max}; inside e.g. {seen_inside[:8]}")


FAUNA = ["bat", "slime", "cod", "salmon", "tropical_fish", "pufferfish", "squid", "glow_squid", "axolotl",
         "cow", "sheep", "pig", "chicken", "horse", "fox", "rabbit", "wolf", "goat"]


def fauna(x, y, z, seconds):
    """A survival bot stands at X Y Z at night; after SECONDS, count each FAUNA type within 128 blocks."""
    r = acc.Rcon()
    bot = start_bot([f"wait:{seconds + 40}"])
    if not wait_online(r):
        print("bot did not join"); return
    r(f"gamemode survival {BOT}")
    r(f"execute in {DIM} run tp {BOT} {x} {y} {z}")
    r(f"effect give {BOT} minecraft:resistance infinite 255 true")
    r("time set 13000")
    time.sleep(seconds)
    counts = {}
    for t in FAUNA:
        res = r(f"execute in {DIM} positioned {x} {y} {z} if entity @e[distance=..128,type=minecraft:{t}]")
        m = re.search(r"count: (\d+)", res)
        counts[t] = int(m.group(1)) if m else 0
    r(f"kick {BOT}")
    try:
        bot.wait(timeout=30)
    except subprocess.TimeoutExpired:
        bot.kill()
    print(f"== fauna at ({x},{y},{z}) after {seconds:.0f}s at night: " + ", ".join(f"{k} {v}" for k, v in counts.items() if v)
          + f"; none of: {[k for k, v in counts.items() if not v]}")


if __name__ == "__main__":
    cmd, a = sys.argv[1], sys.argv[2:]
    if cmd == "fly":
        fly(a[0], *(float(v) for v in a[1:6]), *(float(v) for v in a[6:7]))
    elif cmd == "fauna":
        fauna(*(float(v) for v in a[:3]), float(a[3]))
    elif cmd == "census":
        census(*(float(v) for v in a[:3]), float(a[3]))
