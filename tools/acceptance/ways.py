"""Way walkability: oracle path over overlay pixels of given classes, then a walkability check in the world.

    uv run --project sidecar python tools/acceptance/ways.py plan OUTDIR [ROUTE]
    uv run --project sidecar python tools/acceptance/ways.py check OUTDIR [ROUTE]

ROUTE: road (default; Grimsel road Handegg -> Grimsel Pass over main/secondary road pixels) or aareschlucht (footpath
pixels, OCLS 6|7, through the gorge from the Meiringen end to the Innertkirchen end). Writes/reads OUTDIR/ROUTE-path.json
and OUTDIR/chunks-ROUTE.json.

check: flood fill over standable positions (solid block below, two non-solid blocks for feet and head, step up or
down at most 1 block) inside a 14-block corridor around the oracle path, starting on the road at Handegg. Reports
how far along the path a walker gets and the blocking spot. Tunnels (oracle tunnel bit) are reported separately.
"""
from __future__ import annotations

import json
import sys
from collections import deque
from pathlib import Path

import numpy as np

import acc
from acc import swd
from rails import astar, component

ROUTES = {
    # classes, start LV95, goal LV95, km box (E1, N1, E2, N2), A* gap
    "road": ((2, 3), (2666610, 1162818), (2668937, 1157087), (2665, 1155, 2669, 1163), 3),  # Handegg-Fall -> Strassenpass
    "aareschlucht": ((6, 7), (2659292, 1174299), (2660190, 1173059), (2659, 1173, 2660, 1174), 3),  # gorge walkway N->S
    # the walkway data stops at E 2659472 N 1173745 and resumes ~80 m on at the gallery bridge; south part separately
    "aareschlucht-s": ((6, 7), (2659550, 1173699), (2660190, 1173059), (2659, 1173, 2660, 1174), 3),
}


def plan(out: Path, route: str = "road"):
    classes, s_lv, g_lv, BOX, gap = ROUTES[route]
    START, GOAL = acc.block_of(*s_lv), acc.block_of(*g_lv)
    pix = {}
    for e in range(BOX[0], BOX[2] + 1):
        for n in range(BOX[1], BOX[3] + 1):
            c = acc.cell(e, n)
            if c is None or "OCLS" not in c.layers:
                continue
            o, f = c.layers["OCLS"], c.layers["OFLG"]
            zz, xx = np.nonzero(np.isin(o, classes))
            for dz, dx in zip(zz, xx):
                x, z = acc.block_of(c.e_km * 1000 + dx + 0.5, c.n_km * 1000 + 999 - dz + 0.5)
                pix[(x, z)] = (int(bool(f[dz, dx] & 2)), int(bool(f[dz, dx] & 1)))
    near = lambda p: min(pix, key=lambda q: (q[0] - p[0]) ** 2 + (q[1] - p[1]) ** 2)
    s, g = near(START), near(GOAL)
    print(f"{len(pix)} way pixels of classes {classes}; start {s} goal {g}")
    # thin the pixel set to a skeleton-ish path: A* over road pixels, jumps of up to 3 px
    path = astar(pix, s, g, gap)
    if path is None:
        comp = component(pix, s, gap)
        far = min(comp, key=lambda q: (q[0] - g[0]) ** 2 + (q[1] - g[1]) ** 2)
        print(f"ORACLE: way network from the start ends at {far} LV95 {acc.lv95_of(*far)}")
        path = astar(pix, s, far, gap)
    tun = sum(pix[p][0] for p in path)
    br = sum(pix[p][1] for p in path)
    print(f"oracle way path {len(path)} px ({tun} tunnel px, {br} bridge px)")
    chunks = sorted({((x >> 4) + i, (z >> 4) + k) for x, z in path for i in (-1, 0, 1) for k in (-1, 0, 1)},
                    key=lambda c: abs(c[0] - (s[0] >> 4)) + abs(c[1] - (s[1] >> 4)))
    (out / f"{route}-path.json").write_text(json.dumps([[x, z, *pix[(x, z)]] for x, z in path]))
    (out / f"chunks-{route}.json").write_text(json.dumps(chunks))
    print(f"{len(chunks)} chunks -> chunks-{route}.json")


def solid(name: str | None) -> bool:
    if name is None:
        return False
    n = name.split("[")[0].removeprefix("minecraft:")
    if n in ("air", "cave_air", "water", "lava", "short_grass", "tall_grass", "fern", "snow", "rail", "powered_rail",
             "light", "torch", "wall_torch", "lantern", "ladder", "vine") or n.endswith(("_sign", "_button", "_carpet",
                                                                                          "_pressure_plate", "flower", "sapling")):
        return False
    return not n.endswith(("_door",)) or "open=true" not in name


def passable(name: str | None) -> bool:
    return name is not None and not solid(name) and not name.startswith(("minecraft:water", "minecraft:lava"))


def standable(x, y, z) -> bool:
    return solid(acc.block_at(x, y - 1, z)) and passable(acc.block_at(x, y, z)) and passable(acc.block_at(x, y + 1, z))


def check(out: Path, route: str = "road"):
    raw = json.loads((out / f"{route}-path.json").read_text())
    path = [(p[0], p[1]) for p in raw]
    tunnel = {(p[0], p[1]) for p in raw if p[2]}
    corridor = {}
    for i, (x, z) in enumerate(path):
        for a in range(-7, 8):
            for b in range(-7, 8):
                k = (x + a, z + b)
                if k not in corridor:
                    corridor[k] = i
    # start: standable spot on the first path column
    sx, sz = path[0]
    top = acc.top_at(sx, sz)
    # scan down far enough to get under tree canopies (Aareschlucht: leaves 50 blocks above the gorge walkway)
    start = next(((sx, y, sz) for y in range(top + 2, top - 80, -1)
                  if standable(sx, y, sz) and "leaves" not in (acc.block_at(sx, y - 1, sz) or "")), None)
    if start is None:
        print("no standable start at", path[0]); return
    seen, q = {start}, deque([start])
    while q:
        x, y, z = q.popleft()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = x + dx, z + dz
            if (nx, nz) not in corridor:
                continue
            for ny in (y, y + 1, y - 1):
                if (nx, ny, nz) in seen:
                    break
                if standable(nx, ny, nz) and (ny <= y or passable(acc.block_at(x, y + 2, z))):
                    seen.add((nx, ny, nz)); q.append((nx, ny, nz)); break
    reached = {(x, z) for x, _, z in seen}
    far = max((corridor[k] for k in reached), default=0)
    gx, gz = path[far]
    e, n = acc.lv95_of(gx, gz)
    print(f"walker from {start}: {len(seen)} positions; furthest path index {far}/{len(path) - 1} "
          f"at x {gx} z {gz} (LV95 E {e:.0f} N {n:.0f}); goal reached: {far >= len(path) - 3}")
    if far < len(path) - 3:
        nx, nz = path[min(far + 3, len(path) - 1)]
        col = [(y, acc.block_at(nx, y, nz)) for y in range((acc.top_at(nx, nz) or 0) + 3, (acc.top_at(nx, nz) or 0) - 12, -1)]
        print(f"  blocked near x {nx} z {nz} (tunnel px: {(nx, nz) in tunnel}); column: "
              + ", ".join(f"{y}:{b.split('[')[0].removeprefix('minecraft:')}" for y, b in col if b))
    # tunnels on the path: clear-height check at each tunnel column (2 passable blocks above a solid floor)
    bad = []
    for x, z in path:
        if (x, z) not in tunnel:
            continue
        top = acc.top_at(x, z)
        if top is None:
            continue
        ok = any((x, y, z) in seen for y in range(top - 400, top + 3))
        if not ok:
            bad.append((x, z))
    print(f"tunnel columns on path: {len(tunnel & set(path))}, not reached by walker: {len(bad)}"
          + (f", first {bad[:3]}" if bad else ""))


if __name__ == "__main__":
    {"plan": plan, "check": check}[sys.argv[1]](Path(sys.argv[2]), *sys.argv[3:4])
