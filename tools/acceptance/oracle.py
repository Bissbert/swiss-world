"""Find M2 features in the cell data (the sidecar's output is the oracle) and write chunk lists for gen.py.

    uv run --project sidecar python tools/acceptance/oracle.py OUTDIR

Writes OUTDIR/oracle.json (features with block coordinates) and OUTDIR/chunks-<feature>.json ([cx, cz] lists).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

import acc
from acc import swd

# Cells per feature (km grid, E_km, N_km inclusive)
BOXES = {
    # Brünig line Interlaken Ost (2632.9/1171.2) -> Brienz -> Meiringen (2657.0/1175.4): north shore of the lake
    "rail": (2632, 1170, 2657, 1178),
    "brienzersee": (2633, 1170, 2647, 1179),
    "grimsel": (2665, 1154, 2670, 1163),
    "falls": (2644, 1175, 2644, 1176),
    "handegg": (2666, 1162, 2666, 1162),
}


def cells_in(box):
    e1, n1, e2, n2 = box
    for e in range(e1, e2 + 1):
        for n in range(n1, n2 + 1):
            c = acc.cell(e, n)
            if c is not None:
                yield c


def to_block(c, dz, dx):
    e = c.e_km * 1000 + dx + 0.5
    n = c.n_km * 1000 + 999 - dz + 0.5
    return acc.block_of(e, n)


def main(out: Path):
    out.mkdir(parents=True, exist_ok=True)
    res = {}

    # rails (OFLG bit2) in the Brünig box
    rail = []
    for c in cells_in(BOXES["rail"]):
        f = c.layers.get("OFLG")
        if f is None:
            continue
        zz, xx = np.nonzero(f & 4)
        tun = f[zz, xx] & 2
        oelv = c.layers["OELV"][zz, xx]
        for dz, dx, t, h in zip(zz, xx, tun, oelv):
            x, z = to_block(c, dz, dx)
            rail.append((x, z, int(bool(t)), float(swd.decode_height(h))))
    res["rail_pixels"] = len(rail)
    res["rail_tunnel_pixels"] = sum(r[2] for r in rail)
    (out / "rail.json").write_text(json.dumps(rail))

    # dams / tunnels / road overlays in Grimsel
    dams, tunnels = [], []
    for c in cells_in(BOXES["grimsel"]):
        f = c.layers.get("OFLG")
        if f is None:
            continue
        for bit, acc_list in ((16, dams), (2, tunnels)):
            zz, xx = np.nonzero(f & bit)
            for dz, dx in zip(zz, xx):
                x, z = to_block(c, dz, dx)
                acc_list.append((x, z, float(swd.decode_height(c.layers["OELV"][dz, dx]))))
    res["grimsel_dam_pixels"] = len(dams)
    res["grimsel_tunnel_pixels"] = len(tunnels)
    (out / "dams.json").write_text(json.dumps(dams))
    (out / "tunnels.json").write_text(json.dumps(tunnels))

    # waterfall columns (WKND 6) at Giessbach and Handegg
    for name in ("falls", "handegg"):
        wf = []
        for c in cells_in(BOXES[name]):
            k = c.layers.get("WKND")
            if k is None:
                continue
            zz, xx = np.nonzero(k == 6)
            for dz, dx in zip(zz, xx):
                x, z = to_block(c, dz, dx)
                wf.append((x, z, float(swd.decode_height(c.layers["WLVL"][dz, dx]))))
        res[f"{name}_waterfall_columns"] = len(wf)
        (out / f"waterfall-{name}.json").write_text(json.dumps(wf))

    # Brienzersee: deepest lake column (WKND 1), surface and bed from the cells
    best = None
    for c in cells_in(BOXES["brienzersee"]):
        k, w = c.layers.get("WKND"), c.layers.get("WLVL")
        if k is None or w is None:
            continue
        lake = (k == 1) & (w != swd.NO_HEIGHT)
        if not lake.any():
            continue
        t = c.layers["TERR"]
        # column-centre bed approximated by the NW node (2 m grid) for speed
        bed = swd.decode_height(t[:500, :500]).repeat(2, 0).repeat(2, 1)
        depth = np.where(lake, swd.decode_height(w) - bed, -1)
        dz, dx = np.unravel_index(np.argmax(depth), depth.shape)
        d = float(depth[dz, dx])
        if best is None or d > best[0]:
            x, z = to_block(c, dz, dx)
            best = (d, x, z, float(swd.decode_height(w[dz, dx])), float(bed[dz, dx]), c.e_km, c.n_km)
    res["brienzersee_deepest"] = dict(zip(("depth_m", "x", "z", "surface_m", "bed_m", "e_km", "n_km"), best))
    (out / "oracle.json").write_text(json.dumps(res, indent=1))
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main(Path(sys.argv[1]))
