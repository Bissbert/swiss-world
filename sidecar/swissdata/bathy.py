"""swissBATHY3D lake beds merged into TERR. Owner: sidecar-tlm.

Called by the water builder (it knows the lake polygons and levels). Each lake is one STAC item with a zip
of 1 km ESRI ASCII grids (1 m, absolute bed heights LN02, named ..._{eKm}_{nKm}.asc by their SW corner).
Lakes without an item get a synthetic depth in water.py instead.
"""

from __future__ import annotations

import re
import warnings
import zipfile
from functools import lru_cache
from pathlib import Path

import numpy as np
import rasterio

from . import swd
from .config import Config
from .fetch import fetch

# item -> covered km tiles (min e, min n, max e, max n), inclusive, from the zips' tile lists.
ITEMS: dict[str, tuple[int, int, int, int]] = {
    "swissbathy3d_brienzersee": (2632, 1170, 2646, 1178),
    "swissbathy3d_thunersee": (2614, 1167, 2630, 1177),
    "swissbathy3d_lungernsee": (2654, 1181, 2656, 1184),
}

_TILE = re.compile(r"_(\d{4})_(\d{4})\.asc$")


def item_url(item: str) -> str:
    return f"https://data.geo.admin.ch/ch.swisstopo.swissbathy3d/{item}/{item}_2056_5728.esriasciigrid.zip"


def items_for(e_km: int, n_km: int) -> list[str]:
    """Items whose tiles touch the cell or the neighbour tiles its edge nodes need."""
    return [it for it, (e0, n0, e1, n1) in ITEMS.items()
            if e0 - 1 <= e_km <= e1 and n0 - 1 <= n_km <= n1 + 1]


@lru_cache(maxsize=8)
def _tiles(zip_path: Path) -> dict[tuple[int, int], str]:
    with zipfile.ZipFile(zip_path) as z:
        return {(int(m[1]), int(m[2])): n for n in z.namelist() if (m := _TILE.search(n))}


def mosaic(cfg: Config, item: str, e_km: int, n_km: int) -> np.ndarray | None:
    """Bed heights (m, NaN = none) on a 1002² 1 m grid whose pixel (r, c) has its centre at
    E = e0 - 0.5 + c, N = n0 + 1000.5 - r, i.e. the cell plus one metre on every side."""
    path = fetch(cfg, item_url(item))
    tiles = _tiles(path)
    west, north = e_km * 1000 - 1, n_km * 1000 + swd.CELL + 1  # NW corner of the mosaic
    out = np.full((swd.CELL + 2, swd.CELL + 2), np.nan)
    hit = False
    for te in (e_km - 1, e_km, e_km + 1):
        for tn in (n_km - 1, n_km, n_km + 1):
            if (te, tn) not in tiles:
                continue
            with rasterio.open(f"zip://{path}!{tiles[(te, tn)]}") as src:
                a = src.read(1, masked=True).filled(np.nan).astype(np.float64)
                left, top = src.bounds.left, src.bounds.top
            c0, r0 = int(round(left - west)), int(round(north - top))
            rs, cs = max(r0, 0), max(c0, 0)
            re_, ce = min(r0 + a.shape[0], out.shape[0]), min(c0 + a.shape[1], out.shape[1])
            if rs >= re_ or cs >= ce:
                continue
            out[rs:re_, cs:ce] = a[rs - r0:re_ - r0, cs - c0:ce - c0]
            hit = True
    return out if hit else None


def at_nodes(m: np.ndarray) -> np.ndarray:
    """Node (i, j) sits on the corner shared by mosaic pixels (2j..2j+1, 2i..2i+1); mean of the valid ones."""
    quads = np.stack([m[0:-1:2, 0:-1:2], m[0:-1:2, 1::2], m[1::2, 0:-1:2], m[1::2, 1::2]])
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)  # all-NaN quads -> NaN
        return np.nanmean(quads, axis=0)


def merge(cfg: Config, e_km: int, n_km: int, terr_m: np.ndarray, level_nodes: np.ndarray,
          pinned: list[str] | None) -> tuple[np.ndarray, list[str]]:
    """Lowers TERR (metres, nodes) to the surveyed bed wherever a node is under a lake (finite
    `level_nodes`) and the bed lies below that lake's level. Returns the mask of merged nodes and the
    items used (for the manifest)."""
    merged = np.zeros(terr_m.shape, dtype=bool)
    used: list[str] = []
    if not np.isfinite(level_nodes).any():
        return merged, used
    for item in pinned if pinned is not None else items_for(e_km, n_km):
        m = mosaic(cfg, item, e_km, n_km)
        if m is None:
            continue
        bed = at_nodes(m)
        ok = np.isfinite(bed) & np.isfinite(level_nodes) & (bed < level_nodes - 0.05)
        if ok.any():
            terr_m[ok] = np.where(np.isfinite(terr_m[ok]), np.minimum(terr_m[ok], bed[ok]), bed[ok])
            merged |= ok
            used.append(item)
    return merged, used
