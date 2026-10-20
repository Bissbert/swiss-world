"""Brünig rail continuity, Interlaken Ost -> Meiringen.

    uv run --project sidecar python tools/acceptance/rails.py plan OUTDIR     # oracle path (needs oracle.py output)
    uv run --project sidecar python tools/acceptance/rails.py check OUTDIR    # walk the generated world

plan: shortest 8-connected path over the cells' rail centre pixels (gaps <= 6 m bridged) from the pixel nearest
Interlaken Ost to the one nearest Meiringen; writes chunks-rail.json (path chunks +-1) and rail-path.json.
check: follows minecraft rail blocks like a minecart (each rail's shape must point at the next rail and back) from
the Interlaken Ost end; prints how far it gets and the first break with coordinates.
"""
from __future__ import annotations

import heapq
import json
import math
import sys
from pathlib import Path

import acc

START = acc.block_of(2632940, 1171136)   # Interlaken Ost, on the Brünig line (meter-gauge main line, TLM ACHSE_DKM)
GOAL = acc.block_of(2656972, 1175392)    # Meiringen station


def component(pix, s, R):
    from collections import deque
    seen, q = {s}, deque([s])
    while q:
        p = q.popleft()
        for dx in range(-R, R + 1):
            for dz in range(-R, R + 1):
                n = (p[0] + dx, p[1] + dz)
                if n in pix and n not in seen:
                    seen.add(n); q.append(n)
    return seen


def astar(pix, s, g, R):
    """Shortest path over rail pixels, jumps up to R px allowed at 5x cost; None if unreachable."""
    offs = [(dx, dz) for dx in range(-R, R + 1) for dz in range(-R, R + 1) if (dx or dz) and dx * dx + dz * dz <= R * R]
    dist, prev, pq = {s: 0.0}, {}, [(0.0, s)]
    hfun = lambda p: math.hypot(p[0] - g[0], p[1] - g[1])
    while pq:
        f, p = heapq.heappop(pq)
        if p == g:
            break
        d0 = dist[p]
        if f - hfun(p) > d0 + 1e-9:
            continue
        for dx, dz in offs:
            q = (p[0] + dx, p[1] + dz)
            if q not in pix:
                continue
            step = math.hypot(dx, dz)
            nd = d0 + (step if max(abs(dx), abs(dz)) == 1 else step * 5)
            if nd < dist.get(q, 1e18):
                dist[q], prev[q] = nd, p
                heapq.heappush(pq, (nd + hfun(q), q))
    if g != s and g not in prev:
        return None
    path, p = [g], g
    while p != s:
        p = prev[p]; path.append(p)
    return path[::-1]


def plan(out: Path):
    pix = {(x, z): (t, h) for x, z, t, h in json.loads((out / "rail.json").read_text())}
    near = lambda p: min(pix, key=lambda q: (q[0] - p[0]) ** 2 + (q[1] - p[1]) ** 2)
    s, g = near(START), near(GOAL)
    print("start pixel", s, "goal pixel", g)
    path = astar(pix, s, g, 15)
    if path is None:
        # bridge the start's and the goal's networks at their closest pixels (oracle gap, reported)
        import numpy as np
        A = np.array(sorted(component(pix, s, 15)))
        best = None
        for b in component(pix, g, 15):
            d = ((A - np.array(b)) ** 2).sum(1); i = int(d.argmin())
            if best is None or d[i] < best[0]:
                best = (int(d[i]), tuple(int(v) for v in A[i]), b)
        a, b = best[1], best[2]
        print(f"ORACLE GAP {best[0] ** 0.5:.0f} m between {a} and {b} "
              f"(LV95 E {acc.lv95_of(*a)[0]:.0f} N {acc.lv95_of(*a)[1]:.0f} -> E {acc.lv95_of(*b)[0]:.0f} N {acc.lv95_of(*b)[1]:.0f})")
        n = max(abs(a[0] - b[0]), abs(a[1] - b[1]))
        line = [(round(a[0] + (b[0] - a[0]) * t / n), round(a[1] + (b[1] - a[1]) * t / n)) for t in range(1, n)]
        path = astar(pix, s, a, 15) + line + astar(pix, b, g, 15)
    gaps = [(a, b) for a, b in zip(path, path[1:]) if max(abs(a[0] - b[0]), abs(a[1] - b[1])) > 1]
    tun = sum(pix.get(p, (0, 0))[0] for p in path)
    print(f"oracle path {len(path)} px, {tun} in tunnels, {len(gaps)} gap jumps: {gaps[:10]}")
    chunks = sorted({((x >> 4) + i, (z >> 4) + k) for x, z in path for i in (-1, 0, 1) for k in (-1, 0, 1)},
                    key=lambda c: (abs(c[0] - (START[0] >> 4)) + abs(c[1] - (START[1] >> 4))))
    (out / "rail-path.json").write_text(json.dumps(path))
    (out / "chunks-rail.json").write_text(json.dumps(chunks))
    print(f"{len(chunks)} chunks -> {out / 'chunks-rail.json'}")


DIRS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
SHAPES = {
    "north_south": ("north", "south"), "east_west": ("east", "west"),
    "ascending_north": ("north", "south"), "ascending_south": ("north", "south"),
    "ascending_east": ("east", "west"), "ascending_west": ("east", "west"),
    "north_east": ("north", "east"), "north_west": ("north", "west"),
    "south_east": ("south", "east"), "south_west": ("south", "west"),
}
OPP = {"north": "south", "south": "north", "east": "west", "west": "east"}


def rail_at(x, y, z):
    b = acc.block_at(x, y, z)
    if b and b.split("[")[0].endswith("rail"):
        shape = b.split("shape=")[1].split(",")[0].split("]")[0] if "shape=" in b else None
        return shape
    return None


def find_rail_near(x0, z0, r=24):
    best = None
    for dx in range(-r, r + 1):
        for dz in range(-r, r + 1):
            x, z = x0 + dx, z0 + dz
            top = acc.top_at(x, z)
            if top is None:
                continue
            for y in range(top + 1, top - 40, -1):
                if rail_at(x, y, z):
                    d = dx * dx + dz * dz
                    if best is None or d < best[0]:
                        best = (d, (x, y, z))
                    break
    return best[1] if best else None


def neighbours(x, y, z, shape):
    out = []
    for d in SHAPES[shape]:
        dx, dz = DIRS[d]
        ys = [y + 1] if shape == f"ascending_{d}" else [y, y - 1]
        out.append((d, [(x + dx, yy, z + dz) for yy in ys]))
    return out


def check(out: Path, start=None):
    path = [tuple(p) for p in json.loads((out / "rail-path.json").read_text())]
    s = start or find_rail_near(*path[0])
    if not s:
        print("no rail block near the start", path[0]); return
    seen, stack, breaks = {s}, [s], []
    while stack:
        x, y, z = stack.pop()
        sh = rail_at(x, y, z)
        for d, cands in neighbours(x, y, z, sh):
            nxt = None
            for c in cands:
                nsh = rail_at(*c)
                if nsh and OPP[d] in SHAPES[nsh]:
                    nxt = c; break
            if nxt is None:
                breaks.append(((x, y, z), sh, d))
            elif nxt not in seen:
                seen.add(nxt); stack.append(nxt)
    # progress along the oracle path: furthest path index within 3 blocks of a reached rail
    reached = {(x, z) for x, _, z in seen}
    far = 0
    for i, (px, pz) in enumerate(path):
        if any((px + a, pz + b) in reached for a in range(-3, 4) for b in range(-3, 4)):
            far = i
    gx, gz = path[-1]
    print(f"start rail {s}; {len(seen)} rails reachable; furthest along oracle path: index {far}/{len(path) - 1} "
          f"at x {path[far][0]} z {path[far][1]} (LV95 E {acc.lv95_of(*path[far])[0]:.0f} N {acc.lv95_of(*path[far])[1]:.0f})")
    print(f"goal reached: {far == len(path) - 1}")
    ends = [b for b in breaks]
    print(f"{len(ends)} dead ends in the reachable network; nearest to the furthest point:")
    fx, fz = path[far]
    for (p, sh, d) in sorted(ends, key=lambda e: (e[0][0] - fx) ** 2 + (e[0][2] - fz) ** 2)[:6]:
        e, n = acc.lv95_of(p[0], p[2])
        print(f"  dead end {p} shape {sh} -> {d}   LV95 E {e:.0f} N {n:.0f}")


def network(s):
    seen, stack, ends = {s}, [s], []
    while stack:
        x, y, z = stack.pop()
        sh = rail_at(x, y, z)
        for d, cands in neighbours(x, y, z, sh):
            nxt = None
            for c in cands:
                nsh = rail_at(*c)
                if nsh and OPP[d] in SHAPES[nsh]:
                    nxt = c; break
            if nxt is None:
                ends.append((x, y, z))
            elif nxt not in seen:
                seen.add(nxt); stack.append(nxt)
    return seen, ends


TUNNEL_DEPTH = 160  # how far under the surface segments looks for a rail (tunnels)


def segments(out: Path, step=3):
    """Whole-line continuity: rail coverage along the oracle path and the connected rail pieces it falls into."""
    path = [tuple(p) for p in json.loads((out / "rail-path.json").read_text())]
    comp_of, comps, cover, gaps, gap0, last_y = {}, [], [], [], None, None

    def probe(px, pz, ys_of):
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                top = acc.top_at(px + dx, pz + dz)
                if top is None:
                    continue
                for y in ys_of(top):
                    if rail_at(px + dx, y, pz + dz):
                        return (px + dx, y, pz + dz)
        return None

    for i in range(0, len(path), step):
        px, pz = path[i]
        # near the surface; then (tunnels) beside the last rail's level; then down to TUNNEL_DEPTH under the top
        hit = probe(px, pz, lambda top: range(top, top - 8, -1))
        if hit is None and last_y is not None:
            hit = probe(px, pz, lambda top: range(min(top, last_y + 6), last_y - 7, -1))
        if hit is None:
            hit = probe(px, pz, lambda top: range(top - 8, top - TUNNEL_DEPTH, -1))
        if hit is not None:
            last_y = hit[1]
        cover.append(hit is not None)
        if hit is None:
            gap0 = i if gap0 is None else gap0
            continue
        if gap0 is not None:
            gaps.append((gap0, i)); gap0 = None
        if hit not in comp_of:
            seen, ends = network(hit)
            k = len(comps); comps.append([i, i, len(seen), ends])
            for r in seen:
                comp_of[r] = k
        c = comps[comp_of[hit]]
        c[0], c[1] = min(c[0], i), max(c[1], i)
    if gap0 is not None:
        gaps.append((gap0, len(path) - 1))
    print(f"path {len(path)} px, sampled every {step}: rail within 2 blocks (tunnels included) at {sum(cover)}/{len(cover)} "
          f"({100 * sum(cover) / len(cover):.1f} %); {len(comps)} separate rail pieces along the line")
    big = sorted(comps, key=lambda c: c[1] - c[0], reverse=True)
    print("longest pieces (path index span, rails):", [(c[0], c[1], c[2]) for c in big[:8]])
    print(f"{len(gaps)} gaps without rail; longest:")
    for a, b in sorted(gaps, key=lambda g: g[0] - g[1])[:10]:
        e1, n1 = acc.lv95_of(*path[a]); e2, n2 = acc.lv95_of(*path[b])
        print(f"  index {a}..{b} ({b - a} px) x {path[a][0]} z {path[a][1]} -> x {path[b][0]} z {path[b][1]} "
              f"(LV95 E {e1:.0f} N {n1:.0f} -> E {e2:.0f} N {n2:.0f})")


if __name__ == "__main__":
    {"plan": plan, "check": check, "segments": segments}[sys.argv[1]](Path(sys.argv[2]))
