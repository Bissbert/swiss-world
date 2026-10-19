"""M3 building checks on generated chunks (region files only).

    uv run --project sidecar python tools/acceptance/buildings.py AREA [--light]

For every building of the cell table (BTBL/BSPN) whose span columns all lie in generated (full) chunks:
- present: >= 80 % of its body columns have a non-air block inside the span's height range
- roof: per body column with the roof flag, world top (no leaves) - floor(topH - 2000), share within +-1 (ridges)
  and per eave column (part 1) the same (eaves)
- door: any *_door block within the footprint bbox +1
- stair core: any stairs/ladder block inside the bbox between base+1 and top-2 (multi-floor buildings only)
- --light: interior air components (6-connected, inside the bbox, below the roof) with >= 8 blocks and no block
  light >= 1 anywhere -> dark rooms
Containers: every chest/barrel/lootr block entity in the area, with or without LootTable.
"""
from __future__ import annotations

import collections
import math
import sys
from collections import deque

import numpy as np

import acc
from acc import swd
from areas import chunks_of


def blocklight(ch: acc.Chunk, lx: int, y: int, lz: int) -> int:
    if not hasattr(ch, "_bl"):
        ch._bl = {s["Y"]: s["BlockLight"] for s in ch.root.get("sections", []) if "BlockLight" in s}
    arr = ch._bl.get(y >> 4)
    if arr is None:
        return 0
    i = ((y & 15) << 8) | (lz << 4) | lx
    v = arr[i >> 1] & 0xFF
    return (v >> 4) if i & 1 else (v & 15)


def m2y(h_enc: int) -> float:
    return float(swd.decode_height(h_enc)) - 2000


def main(area: str, light: bool):
    chunks = set(chunks_of(area))
    full = {c for c in chunks if acc.chunk_at(c[0] * 16, c[1] * 16) is not None}
    # cells touching the area
    xs = [c[0] for c in full]; zs = [c[1] for c in full]
    e1, n2 = acc.lv95_of(min(xs) * 16, min(zs) * 16)
    e2, n1 = acc.lv95_of(max(xs) * 16 + 15, max(zs) * 16 + 15)
    stats = collections.Counter()
    roof_d, eave_d = collections.Counter(), collections.Counter()
    problems = collections.defaultdict(list)
    seen_gid = set()
    for ek in range(math.floor(e1 / 1000), math.floor(e2 / 1000) + 1):
        for nk in range(math.floor(n1 / 1000), math.floor(n2 / 1000) + 1):
            c = acc.cell(ek, nk)
            if c is None:
                continue
            cols = collections.defaultdict(list)
            for (dx, dz), spans in c.spans.items():
                for s in spans:
                    cols[s.building_idx].append((dx, dz, s))
            n_cell = n_in = 0
            for bi, b in enumerate(c.buildings):
                n_cell += 1
                bc = cols.get(bi, [])
                if not bc or b.gid in seen_gid:
                    continue
                blocks = [(acc.block_of(ek * 1000 + dx + 0.5, nk * 1000 + 999 - dz + 0.5), s) for dx, dz, s in bc]
                if not all(((x >> 4), (z >> 4)) in full for (x, z), _ in blocks):
                    continue
                # cross-cell buildings: only judge them from the cell that owns their first column
                seen_gid.add(b.gid)
                n_in += 1
                stats["buildings"] += 1
                body = [(p, s) for p, s in blocks if s.part != 1]
                if not body:
                    stats["eave-only in this cell (skipped)"] += 1
                    continue
                hit = 0
                for (x, z), s in body:
                    lo, hi = math.floor(m2y(s.bottom_h)), math.ceil(m2y(s.top_h)) - 1
                    if any(acc.block_at(x, y, z) not in (None, "minecraft:air") for y in range(lo, hi + 1)):
                        hit += 1
                present = body and hit >= 0.8 * len(body)
                stats["present"] += bool(present)
                if not present:
                    problems["missing"].append((b.gid, blocks[0][0], hit, len(body)))
                    continue
                for (x, z), s in blocks:
                    if not (s.roof_flags & 1):
                        continue
                    top = acc.top_at(x, z, "MOTION_BLOCKING_NO_LEAVES")
                    tb = acc.block_at(x, top, z) or ""
                    surf = top + (0.5 if ("_slab" in tb and "type=bottom" in tb) else 1.0)
                    d = round(surf - m2y(s.top_h))
                    (eave_d if s.part == 1 else roof_d)[max(-5, min(5, d))] += 1
                # bbox in blocks
                bx = [p[0] for p, _ in blocks]; bz = [p[1] for p, _ in blocks]
                x0, x1, z0, z1 = min(bx) - 1, max(bx) + 1, min(bz) - 1, max(bz) + 1
                base = math.floor(min(m2y(s.bottom_h) for _, s in body))
                topy = math.ceil(max(m2y(s.top_h) for _, s in body))
                names = {}
                for x in range(x0, x1 + 1):
                    for z in range(z0, z1 + 1):
                        for y in range(base - 1, topy + 2):
                            names[(x, y, z)] = acc.block_at(x, y, z) or "minecraft:air"
                has_door = any(n.endswith("_door") or "_door[" in n for n in names.values())
                stats["with door"] += has_door
                if not has_door:
                    problems["no door"].append((b.gid, (x0, base, z0), b.objektart))
                multi = topy - base >= 8
                if multi:
                    stats["multi-storey (>= 8 blocks)"] += 1
                    has_stair = any(("_stairs" in n or "ladder" in n) and base + 1 <= p[1] <= topy - 3
                                    and x0 + 1 < p[0] < x1 - 1 and z0 + 1 < p[2] < z1 - 1
                                    for p, n in names.items())
                    stats["multi-storey with stair/ladder core"] += has_stair
                    if not has_stair:
                        problems["no stair core"].append((b.gid, (x0, base, z0)))
                if light:
                    bodyset = {p for p, _ in body}
                    air = {p for p, n in names.items() if (p[0], p[2]) in bodyset and
                           n in ("minecraft:air", "minecraft:cave_air") and base <= p[1] < topy - 1}
                    # remove air connected to the outside (above the roof or outside the body)
                    comp_dark = 0
                    seen = set()
                    for p0 in air:
                        if p0 in seen:
                            continue
                        comp, q, outside, lit = [], deque([p0]), False, 0
                        seen.add(p0)
                        while q:
                            p = q.popleft(); comp.append(p)
                            ch = acc.chunk_at(p[0], p[2])
                            lit = max(lit, blocklight(ch, p[0] & 15, p[1], p[2] & 15))
                            for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                                n = (p[0] + d[0], p[1] + d[1], p[2] + d[2])
                                if n in air:
                                    if n not in seen:
                                        seen.add(n); q.append(n)
                                elif (n[0], n[2]) not in bodyset and names.get(n, "minecraft:air").endswith("air"):
                                    outside = True
                        if len(comp) >= 8 and not outside:
                            stats["interior rooms"] += 1
                            if lit == 0:
                                comp_dark += 1
                                xs_ = [p[0] for p in comp]; ys_ = [p[1] for p in comp]; zs_ = [p[2] for p in comp]
                                problems["dark room"].append((b.gid, len(comp), (min(xs_), min(ys_), min(zs_)),
                                                              (max(xs_), max(ys_), max(zs_))))
                    stats["dark rooms"] += comp_dark
            print(f"cell {ek}_{nk}: {n_cell} buildings in BTBL, {n_in} fully inside the generated area")
    # containers
    cont = collections.Counter(); tables = collections.Counter(); nolt = []
    for cx, cz in sorted(full):
        ch = acc.chunk_at(cx * 16, cz * 16)
        for be in ch.block_entities():
            bid = be.get("id", "")
            if any(k in bid for k in ("chest", "barrel", "shulker", "lootr")):
                cont[bid] += 1
                lt = be.get("LootTable")
                if lt:
                    tables[lt] += 1
                else:
                    items = be.get("Items")
                    nolt.append((bid, be.get("x"), be.get("y"), be.get("z"), len(items) if items else 0))
    print("\n== buildings", area)
    for k, v in stats.items():
        print(f"  {k}: {v}")
    fmt = lambda c: ", ".join(f"{k:+d}:{v}" for k, v in sorted(c.items()))
    rt, et = sum(roof_d.values()), sum(eave_d.values())
    print(f"  roof columns: round(world roof surface - source topH): within +-1 {sum(v for k, v in roof_d.items() if abs(k) <= 1)}/{rt} [{fmt(roof_d)}]")
    print(f"  eave columns: within +-1 {sum(v for k, v in eave_d.items() if abs(k) <= 1)}/{et} [{fmt(eave_d)}]")
    for k, v in problems.items():
        print(f"  {k}: {len(v)} e.g. {v[:5]}")
    print(f"== containers: {sum(cont.values())} {dict(cont)}; with LootTable {sum(tables.values())}; without {len(nolt)} e.g. {nolt[:5]}")
    print("  loot tables:", dict(tables.most_common(30)))


if __name__ == "__main__":
    main(sys.argv[1], "--light" in sys.argv)
