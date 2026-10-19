"""Builds the golden synthetic fixture fixtures/synthetic_2632_1170.swd (python -m swissdata.fixture)."""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

from . import swd

E_KM, N_KM = 2632, 1170


def build() -> swd.Cell:
    i = np.arange(swd.TERR_N)[None, :]
    j = np.arange(swd.TERR_N)[:, None]
    h = 600.0 + i * 0.1 + j * 0.05
    lake_nodes = (j >= 400) & (i <= 100)
    h = np.where(lake_nodes, 550.0, h)
    terr = swd.encode_height(h)

    dx = np.arange(swd.CELL)[None, :]
    dz = np.arange(swd.CELL)[:, None]
    lake = (dz >= 800) & (dx < 200)
    surf = np.zeros((swd.CELL, swd.CELL), dtype="u1")
    surf[:, 500:600] = 2
    surf[lake.repeat(1, 0) & np.ones_like(surf, dtype=bool)] = 7

    wlvl = np.full((swd.CELL, swd.CELL), swd.NO_HEIGHT, dtype="<u2")
    wlvl[lake & np.ones_like(wlvl, dtype=bool)] = swd.encode_height(564.0)
    wknd = np.where(lake, 1, 0).astype("u1") * np.ones((swd.CELL, swd.CELL), dtype="u1")

    ocls = np.zeros((swd.CELL, swd.CELL), dtype="u1")
    oflg = np.zeros((swd.CELL, swd.CELL), dtype="u1")
    oelv = np.full((swd.CELL, swd.CELL), swd.NO_HEIGHT, dtype="<u2")
    ocls[298:303, :] = 3
    ocls[:, 698:703] = 8
    oflg[:, 700] |= swd.OFLG_RAIL
    # bridge deck where the road crosses the forest strip
    oflg[298:303, 500:600] |= swd.OFLG_BRIDGE
    oelv[298:303, 500:600] = swd.encode_height(640.0)

    insd = np.ones((swd.CELL, swd.CELL), dtype="u1")
    insd[:, 990:] = 0

    cell = swd.Cell(E_KM, N_KM, layers={
        "TERR": terr, "SURF": surf, "WLVL": wlvl, "WKND": wknd,
        "OCLS": ocls, "OELV": oelv, "OFLG": oflg, "INSD": insd,
    })
    b = swd.Building(
        gid=swd.gid_from_uuid("fixture-building-1"), egid=123456, objektart=0,
        gwr_category=1021, gwr_class=1110, gwr_period=8014, floors=2, entrance_side=3,
        base_h=int(swd.encode_height(610.0)), top_h=int(swd.encode_height(619.5)),
        min_x=100, min_z=100, max_x=109, max_z=107,
    )
    cell.buildings.append(b)
    for x in range(100, 110):
        for z in range(100, 108):
            ridge = 619.5 - abs(z - 103.5) * 0.5
            cell.spans[(x, z)] = [swd.Span(int(swd.encode_height(610.0)), int(swd.encode_height(ridge)), 0, 0, 1)]
    return cell


def expected(cell: swd.Cell) -> dict:
    def terr_node(i, j):
        return float(swd.decode_height(cell.layers["TERR"][j, i]))
    return {
        "eKm": E_KM, "nKm": N_KM, "border": True,
        "terrNodes": [{"i": i, "j": j, "h": terr_node(i, j)} for i, j in [(0, 0), (500, 0), (0, 500), (250, 100), (50, 450)]],
        "columns": [
            {"dx": 10, "dz": 900, "surf": 7, "wlvl": 564.0, "wknd": 1, "ocls": 0, "oflg": 0, "insd": 1},
            {"dx": 550, "dz": 10, "surf": 2, "wlvl": None, "wknd": 0, "ocls": 0, "oflg": 0, "insd": 1},
            {"dx": 550, "dz": 300, "surf": 2, "wlvl": None, "wknd": 0, "ocls": 3, "oflg": swd.OFLG_BRIDGE, "oelv": 640.0, "insd": 1},
            {"dx": 700, "dz": 10, "surf": 0, "wlvl": None, "wknd": 0, "ocls": 8, "oflg": swd.OFLG_RAIL, "insd": 1},
            {"dx": 995, "dz": 10, "surf": 0, "wlvl": None, "wknd": 0, "ocls": 0, "oflg": 0, "insd": 0},
        ],
        "buildings": [{"gid": str(cell.buildings[0].gid), "egid": 123456, "gwrClass": 1110, "floors": 2,
                       "bbox": [100, 100, 109, 107], "baseH": 610.0, "topH": 619.5}],
        "spanColumns": len(cell.spans),
        "spanProbe": {"dx": 105, "dz": 103, "bottom": 610.0,
                      "top": float(swd.decode_height(cell.spans[(105, 103)][0].top_h)), "buildingIdx": 0, "roofFlags": 1},
    }


def main(out_dir: str) -> None:
    out = Path(out_dir)
    cell = build()
    data = swd.encode(cell)
    swd.write_atomic(out / f"synthetic_{E_KM}_{N_KM}.swd", data)
    (out / f"synthetic_{E_KM}_{N_KM}.expected.json").write_text(json.dumps(expected(cell), indent=2) + "\n")
    print(f"wrote {len(data)} bytes")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "../fixtures")
