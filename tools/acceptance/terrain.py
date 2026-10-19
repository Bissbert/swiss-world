"""Terrain accuracy, seams and snow line on already generated chunks (reads region files only).

    uv run --project sidecar python tools/acceptance/terrain.py survey AREA...     # named points + every column
    uv run --project sidecar python tools/acceptance/terrain.py seams              # cell-edge vs interior steps
    uv run --project sidecar python tools/acceptance/terrain.py snow AREA...        # snow cover by elevation

Ground = highest natural block of the column (trees, plants, snow layers skipped); columns with an overlay (road,
rail, dam ...), a building span or water are left out. Reference 1: floor(TERR - 2000) at the column centre (what the
generator was given). Reference 2: swissALTI3D 2 m COG (nearest pixel) from the shared raw cache (independent).
"""
from __future__ import annotations

import collections
import math
import sys

import numpy as np

import acc
from acc import swd
from areas import chunks_of

POINTS = {
    "Harder Kulm Haupthuegel (1322)": (2631590, 1171870, 1322),
    "Harder Kulm Aussichtspunkt (1322)": (2631587, 1171854, 1322),
    "Harder Kulm station (1296)": (2631854, 1171972, 1296),
    "Höhematte (568)": (2632253, 1170637, 568),
    "Interlaken Ost (567)": (2632923, 1171160, 567),
    "Interlaken Ost 2 (567)": (2632931, 1171135, 567),
    "Brienz station (566)": (2645866, 1178350, 566),
    "Meiringen station (595)": (2656972, 1175392, 595),
    "Aareschlucht Graben (632)": (2659290, 1174180, 632),
    "Handegg-Fall (1371)": (2666590, 1162823, 1371),
    "Grimselpass Pass (2163)": (2668880, 1157032, 2163),
    "Grimselpass Strassenpass (2164)": (2668937, 1157087, 2164),
    "Totensee Staumauer (2155)": (2669416, 1156964, 2155),
}


def plain(x: int, z: int) -> bool:
    e, n = acc.lv95_of(x, z)
    c, dx, dz = acc.cell_xy(e, n)
    if c is None:
        return False
    L = c.layers
    if "OCLS" in L and L["OCLS"][dz, dx]:
        return False
    if "WLVL" in L and L["WLVL"][dz, dx] != swd.NO_HEIGHT:
        return False
    if (dx, dz) in c.spans:
        return False
    return True


def survey(areas):
    print("Named survey points (y = metres - 2000):")
    print(f"{'point':36s} {'x':>6s} {'z':>6s} {'world':>6s} {'TERR':>8s} {'ALTI3D':>8s} {'names':>6s} d(ALTI) block")
    for name, (e, n, h) in POINTS.items():
        x, z = acc.block_of(e, n)
        g = acc.ground_at(x, z)
        if g is None:
            print(f"{name:36s} {x:6d} {z:6d}   (not generated)"); continue
        t = acc.terr_at_block(x, z); a = acc.alti_m(*acc.lv95_of(x, z))
        d = math.floor(a - 2000) - g[0]
        print(f"{name:36s} {x:6d} {z:6d} {g[0]:6d} {t:8.2f} {a:8.2f} {h:6d} {d:+5d}  {g[1].split('[')[0]}"
              f"{'' if plain(x, z) else '  (overlay/building/water column)'}")
    rng = np.random.default_rng(42)
    for area in areas:
        dT, dA, n_all = collections.Counter(), collections.Counter(), 0
        chunks = chunks_of(area)
        cols = [(cx * 16 + int(i), cz * 16 + int(k)) for cx, cz in chunks for i, k in rng.integers(0, 16, (8, 2))]
        for x, z in cols:
            if not plain(x, z):
                continue
            g = acc.ground_at(x, z)
            if g is None:
                continue
            n_all += 1
            dT[math.floor(acc.terr_at_block(x, z) - 2000) - g[0]] += 1
            a = acc.alti_m(*acc.lv95_of(x, z))
            if not math.isnan(a):
                dA[math.floor(a - 2000) - g[0]] += 1
        fmt = lambda c: ", ".join(f"{k:+d}:{v}" for k, v in sorted(c.items()))
        wT = sum(v for k, v in dT.items() if abs(k) <= 1); wA = sum(v for k, v in dA.items() if abs(k) <= 1)
        print(f"{area}: {n_all} plain columns sampled. vs TERR within ±1: {wT}/{n_all} ({100 * wT / max(1, n_all):.1f} %) [{fmt(dT)}]"
              f"  vs ALTI3D within ±1: {wA}/{sum(dA.values())} ({100 * wA / max(1, sum(dA.values())):.1f} %) [{fmt(dA)}]")


def seams():
    """Cell edges E 2633000 (between x 99|100) and N 1171000 (between z -401|-400) inside the 'seam' area."""
    xe = 2633000 - acc.E0          # first x of the eastern cell
    ze = acc.N0 - 1171000          # first z of the southern cell (z = N0 - N - 1 -> N = 1171000 - 1e-9 ... )
    def step_stats(pairs):
        out = []
        for (x1, z1), (x2, z2) in pairs:
            if not (plain(x1, z1) and plain(x2, z2)):
                continue
            g1, g2 = acc.ground_at(x1, z1), acc.ground_at(x2, z2)
            if g1 is None or g2 is None:
                continue
            exp = math.floor(acc.terr_at_block(x2, z2) - 2000) - math.floor(acc.terr_at_block(x1, z1) - 2000)
            out.append(((g2[0] - g1[0]), exp))
        if not out:
            return "no data"
        a = np.array(out)
        act, err = np.abs(a[:, 0]), np.abs(a[:, 0] - a[:, 1])
        return (f"n={len(a)} mean|dy|={act.mean():.2f} max|dy|={act.max()} "
                f"|dy-expected|>0: {int((err > 0).sum())}, >1: {int((err > 1).sum())}")
    zs = range(-528, -272)
    xs = range(-28, 228)
    print(f"E-edge pairs (x {xe - 1}|{xe}):      ", step_stats([((xe - 1, z), (xe, z)) for z in zs]))
    print(f"interior pairs (x {xe - 9}|{xe - 8}):   ", step_stats([((xe - 9, z), (xe - 8, z)) for z in zs]))
    print(f"interior pairs (x {xe + 7}|{xe + 8}):   ", step_stats([((xe + 7, z), (xe + 8, z)) for z in zs]))
    print(f"N-edge pairs (z {ze - 1}|{ze}):   ", step_stats([((x, ze - 1), (x, ze)) for x in xs]))
    print(f"interior pairs (z {ze - 9}|{ze - 8}):", step_stats([((x, ze - 9), (x, ze - 8)) for x in xs]))
    # chunk-edge check: generated block equality is not needed; ground continuity across 16-block borders
    pairs = [((x, z), (x + 1, z)) for x in range(-17, 220, 16) for z in zs]
    print("chunk-edge pairs (x 15|0):        ", step_stats(pairs))


def snow(areas):
    by = collections.defaultdict(lambda: [0, 0])
    lowest = None
    for area in areas:
        for cx, cz in chunks_of(area):
            ch = acc.load_chunk(cx, cz)
            if ch is None or ch.status != "minecraft:full":
                continue
            hm = ch.heightmap()
            for k in range(0, 16, 2):
                for i in range(0, 16, 2):
                    top = int(hm[k, i]); b = ch.block(i, top, k)
                    m = top + 2000
                    nm = b.split("[")[0]
                    snowy = nm in ("minecraft:snow", "minecraft:snow_block", "minecraft:powder_snow") or "snowy=true" in b
                    by[m // 100 * 100][0] += 1; by[m // 100 * 100][1] += snowy
                    if snowy and (lowest is None or m < lowest[0]):
                        lowest = (m, cx * 16 + i, cz * 16 + k, b)
    print("elevation band: columns, snow %")
    half = None
    for band in sorted(by):
        n, s = by[band]
        print(f"  {band:5d}-{band + 99:5d} m: {n:6d} {100 * s / n:5.1f} %")
        if half is None and s / n >= 0.5:
            half = band
    print("lowest snow:", lowest, " first band with >= 50 % snow:", half)


if __name__ == "__main__":
    cmd = sys.argv[1]
    {"survey": lambda: survey(sys.argv[2:]), "seams": seams, "snow": lambda: snow(sys.argv[2:])}[cmd]()
