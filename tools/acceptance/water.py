"""Water checks: lake depth, falls, and stability (rivers/dams) via before/after snapshots of the region files.

    uv run --project sidecar python tools/acceptance/water.py lake OUTDIR
    uv run --project sidecar python tools/acceptance/water.py falls OUTDIR
    uv run --project sidecar python tools/acceptance/water.py snap AREA|@chunks.json OUT.json
    uv run --project sidecar python tools/acceptance/water.py diff A.json B.json
    uv run --project sidecar python tools/acceptance/water.py tick AREA|@chunks.json SECONDS    # forceload, keep
                                                       ticking, sample /tick query every 5 s, unload, save-all flush
    uv run --project sidecar python tools/acceptance/water.py banks AREA N OUT.json   # break N river banks (setblock air)
"""
from __future__ import annotations

import json
import random
import sys
import time
from pathlib import Path

import numpy as np

import acc
from acc import swd
from areas import chunks_of


def is_water(n: str) -> bool:
    return n.startswith("minecraft:water") or "waterlogged=true" in n or n in ("minecraft:kelp", "minecraft:seagrass")


def lake(out: Path):
    o = json.loads((out / "oracle.json").read_text())["brienzersee_deepest"]
    x, z = o["x"], o["z"]
    best = None
    for dx in range(-8, 9):
        for dz in range(-8, 9):
            col = column_water(x + dx, z + dz)
            if col and (best is None or col[0] - col[1] > best[0] - best[1]):
                best = (*col, x + dx, z + dz)
    surf, bed, bx, bz = best
    print(f"Brienzersee deepest oracle column x {x} z {z}: surface {o['surface_m']} m, bed {o['bed_m']:.1f} m, depth {o['depth_m']:.1f} m")
    print(f"world (17x17 around it): deepest water column x {bx} z {bz}: top water y {surf} ({surf + 2001} m surface), "
          f"bed block y {bed} ({bed + 2001} m), water depth {surf - bed} blocks")


def column_water(x, z):
    """(top water y, solid bed y) or None."""
    top = acc.top_at(x, z)
    if top is None:
        return None
    y = top
    while y > acc.MIN_Y and not is_water(acc.block_at(x, y, z)):
        y -= 1
        if top - y > 40:
            return None
    wt = y
    while y > acc.MIN_Y and is_water(acc.block_at(x, y, z)):
        y -= 1
    return wt, y


def falls(out: Path):
    for name in ("falls", "handegg"):
        cols = json.loads((out / f"waterfall-{name}.json").read_text())
        ok = drop = 0
        missing = []
        for x, z, wl in cols:
            top = acc.top_at(x, z)
            if top is None:
                continue
            n = sum(is_water(acc.block_at(x, y, z)) for y in range(top - 3, top + 1))
            if n:
                ok += 1
            else:
                missing.append((x, top, z, acc.block_at(x, top, z)))
        ys = [acc.top_at(x, z) for x, z, _ in cols if acc.top_at(x, z) is not None]
        print(f"{name}: {len(cols)} waterfall columns (WKND 6); {ok} with water at the top, "
              f"water-top range y {min(ys)}..{max(ys)} (drop {max(ys) - min(ys)} blocks); dry e.g. {missing[:3]}")


def snap(area: str, out: str):
    res = {}
    for cx, cz in chunks_of(area):
        ch = acc.load_chunk(cx, cz)
        if ch is None or ch.status != "minecraft:full":
            continue
        cnt, h = 0, []
        for sy in sorted(ch.sections):
            pal, idx = ch.section(sy)
            wmask = np.array([is_water(p) for p in pal])
            if idx is None:
                c = 4096 * int(wmask[0])
            else:
                c = int(wmask[idx].sum())
            if c:
                h.append(f"{sy}:{ch.section_hashes().get(sy)}")
            cnt += c
        res[f"{cx},{cz}"] = [cnt, h]
    Path(out).write_text(json.dumps(res))
    print(f"{len(res)} chunks, {sum(v[0] for v in res.values())} water blocks -> {out}")


def diff(a: str, b: str):
    A, B = json.loads(Path(a).read_text()), json.loads(Path(b).read_text())
    ch = [(k, A[k][0], B[k][0]) for k in A if k in B and A[k][1] != B[k][1]]
    tot = sum(v[2] - v[1] for v in ch)
    print(f"{len(A)} chunks compared; {len(ch)} chunks with changed water sections; net water blocks {tot:+d}")
    for k, x, y in sorted(ch, key=lambda t: -abs(t[2] - t[1]))[:8]:
        cx, cz = map(int, k.split(","))
        print(f"  chunk {k} (x {cx * 16}..{cx * 16 + 15}, z {cz * 16}..{cz * 16 + 15}): {x} -> {y} ({y - x:+d})")


def tick(area: str, seconds: float):
    r = acc.Rcon()
    chunks = chunks_of(area)
    for cx, cz in chunks:
        r.inside(f"forceload add {cx * 16} {cz * 16}")
    t0 = time.time()
    samples = []
    while time.time() - t0 < seconds:
        time.sleep(5)
        q = r("tick query")
        try:
            avg = float(q.split("Average time per tick: ")[1].split("ms")[0])
            p95 = float(q.split("P95: ")[1].split("ms")[0])
            samples.append((avg, p95))
        except (IndexError, ValueError):
            pass
    for cx, cz in chunks:
        r.inside(f"forceload remove {cx * 16} {cz * 16}")
    r("save-all flush")
    if samples:
        a = np.array(samples)
        print(f"ticked {len(chunks)} chunks for {seconds:.0f}s: MSPT avg {a[:, 0].mean():.1f} max {a[:, 0].max():.1f}, "
              f"P95 max {a[:, 1].max():.1f} ms (TPS {min(20, 1000 / max(50, a[:, 0].mean())):.1f})")


def banks(area: str, n: int, out: str):
    """Pick n river columns (WKND 2) that border a dry, plain land column; set that land column's blocks from water
    level down 3 to air (a breach). Writes the positions."""
    rnd = random.Random(7)
    cand = []
    for cx, cz in chunks_of(area):
        for i in range(0, 16, 3):
            for k in range(0, 16, 3):
                x, z = cx * 16 + i, cz * 16 + k
                e, nn = acc.lv95_of(x, z)
                c, dx, dz = acc.cell_xy(e, nn)
                if c is None or "WKND" not in c.layers or c.layers["WKND"][dz, dx] != 2:
                    continue
                for ddx, ddz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ex, ez = dx + ddx, dz + ddz
                    if 0 <= ex < 1000 and 0 <= ez < 1000 and c.layers["WLVL"][ez, ex] == swd.NO_HEIGHT:
                        wy = acc.top_at(x, z)
                        cand.append((x + ddx, wy, z + ddz))
                        break
    pick = rnd.sample(cand, min(n, len(cand)))
    r = acc.Rcon()
    for x, y, z in pick:
        print(r.inside(f"fill {x} {y - 3} {z} {x} {y + 1} {z} air").strip())
    Path(out).write_text(json.dumps(pick))
    print(f"breached {len(pick)} banks of {len(cand)} candidates")


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd in ("lake", "falls"):
        {"lake": lake, "falls": falls}[cmd](Path(args[0]))
    elif cmd == "snap":
        snap(args[0], args[1])
    elif cmd == "diff":
        diff(args[0], args[1])
    elif cmd == "tick":
        tick(args[0], float(args[1]))
    elif cmd == "banks":
        banks(args[0], int(args[1]), args[2])
