"""TERR from swissALTI3D 2 m COGs. Owner: sidecar-core.

Geometry: the 2 m COG of tile (e, n) is 500×500 pixels with AREA_OR_POINT=Area and its origin at the tile's
NW corner, so pixel (row r, col c) is the sample at its *centre*, E = e·1000 + 2c + 1, N = n·1000 + 999 − 2r
(the matching xyz product lists 2631001 1170999 … for tile 2631-1170). TERR node (i, j) sits on the pixel
*corners*, E = e·1000 + 2i, N = n·1000 + 1000 − 2j, so each node is the mean (= bilinear value) of the 4
pixels around it. Edge nodes therefore need pixels from all 8 neighbouring tiles: only their outermost
row/column/corner, which are cached in scratch/alti-edges so a neighbour tile is read once.

A missing neighbour (no swisstopo tile, e.g. abroad, or outside the dev bbox) contributes nothing: the node
is the mean of the pixels that do exist. Every cell sharing a node sees the same set of pixels, so shared
edges stay byte-identical. Sums are exact in float64 (4 float32 values), so pixel order cannot matter.

Nodata pixels are filled per tile, before edges are taken, by iterated 4-neighbour means.

Item choice: per tile through manifest.Pins ("alti"), so a neighbour tile read for an edge is later built
from the same item. A rebuild uses the cell's manifest sources `alti` + `altiNeighbours` verbatim.
"""

from __future__ import annotations

import logging
import os
import threading
from collections.abc import Callable
from pathlib import Path

import numpy as np
import rasterio

from . import stac, swd
from .config import Config
from .fetch import fetch
from .manifest import Pins

log = logging.getLogger(__name__)

TILE_PX = 500
# neighbour offsets (de, dn) in the order stored in sources["altiNeighbours"]
NEIGHBOURS = [(-1, 1), (0, 1), (1, 1), (-1, 0), (1, 0), (-1, -1), (0, -1), (1, -1)]


class OutsideDevArea(RuntimeError):
    pass


def tile_key(e_km: int, n_km: int) -> str:
    return f"{e_km}-{n_km}"


def fill_nodata(a: np.ndarray, nodata_mask: np.ndarray) -> np.ndarray:
    """Fills masked pixels with the mean of their valid 4-neighbours, growing inwards until none are left."""
    a = a.astype(np.float64, copy=True)
    hole = nodata_mask.copy()
    if hole.all():
        raise ValueError("tile has no valid pixels")
    a[hole] = 0.0
    while hole.any():
        valid = ~hole
        s = np.zeros_like(a)
        k = np.zeros(a.shape, dtype=np.int32)
        for sl_dst, sl_src in (((slice(1, None), slice(None)), (slice(None, -1), slice(None))),
                               ((slice(None, -1), slice(None)), (slice(1, None), slice(None))),
                               ((slice(None), slice(1, None)), (slice(None), slice(None, -1))),
                               ((slice(None), slice(None, -1)), (slice(None), slice(1, None)))):
            s[sl_dst] += np.where(valid[sl_src], a[sl_src], 0.0)
            k[sl_dst] += valid[sl_src]
        grow = hole & (k > 0)
        a[grow] = s[grow] / k[grow]
        hole &= ~grow
    return a


def read_tile(path: Path) -> np.ndarray:
    """500×500 float64 heights (row 0 = north), nodata filled."""
    with rasterio.open(path) as ds:
        a = ds.read(1).astype(np.float64)
        mask = ~np.isfinite(a)
        if ds.nodata is not None:
            mask |= a == ds.nodata
    if a.shape != (TILE_PX, TILE_PX):
        raise ValueError(f"{path.name}: unexpected shape {a.shape}")
    if mask.any():
        log.info("%s: filling %d nodata pixels", path.name, int(mask.sum()))
        a = fill_nodata(a, mask)
    return a


def nodes_from_padded(p: np.ndarray) -> np.ndarray:
    """502×502 pixel block (own tile in [1:501, 1:501], NaN = missing) -> 501×501 node heights."""
    q = np.stack([p[:-1, :-1], p[:-1, 1:], p[1:, :-1], p[1:, 1:]])
    valid = np.isfinite(q)
    return np.where(valid, q, 0.0).sum(axis=0) / valid.sum(axis=0)


class TileSource:
    """Pixels of pinned ALTI items: full tiles for the own cell, cached outer rows/cols for neighbours."""

    def __init__(self, cfg: Config, path_for: Callable[[str], Path] | None = None):
        self.cfg = cfg
        self._path_for = path_for or (lambda item: fetch(cfg, stac.alti_href(item)))
        self._edges_dir = Path(cfg.scratch) / "alti-edges"

    def tile(self, item: str) -> np.ndarray:
        a = read_tile(self._path_for(item))
        self._save_edges(item, a)
        return a

    def edges(self, item: str) -> dict[str, np.ndarray]:
        """{"n": row 0, "s": row 499, "w": col 0, "e": col 499} of the filled tile."""
        f = self._edges_dir / f"{item}.npz"
        if f.exists():
            with np.load(f) as z:
                return {k: z[k] for k in "nswe"}
        return self._save_edges(item, read_tile(self._path_for(item)))

    def release(self, item: str) -> None:
        """Deletes the raw COG of a built tile unless keepRaw (its edges stay cached for neighbours)."""
        if not self.cfg.keep_raw:
            self._path_for(item).unlink(missing_ok=True)

    def _save_edges(self, item: str, a: np.ndarray) -> dict[str, np.ndarray]:
        e = {"n": a[0].copy(), "s": a[-1].copy(), "w": a[:, 0].copy(), "e": a[:, -1].copy()}
        self._edges_dir.mkdir(parents=True, exist_ok=True)
        tmp = self._edges_dir / f"{item}.{os.getpid()}-{threading.get_ident()}.tmp.npz"  # workers may race
        np.savez(tmp, **e)
        tmp.replace(self._edges_dir / f"{item}.npz")
        return e


def padded_pixels(own: np.ndarray, neighbour_edges: dict[tuple[int, int], dict[str, np.ndarray] | None]) -> np.ndarray:
    p = np.full((TILE_PX + 2, TILE_PX + 2), np.nan)
    p[1:-1, 1:-1] = own
    last = TILE_PX + 1
    for (de, dn), ed in neighbour_edges.items():
        if ed is None:
            continue
        rows = {1: 0, 0: slice(1, -1), -1: last}[dn]  # north neighbour fills our row 0
        cols = {-1: 0, 0: slice(1, -1), 1: last}[de]
        if dn == 0:  # west/east: their east/west column
            p[rows, cols] = ed["e"] if de == -1 else ed["w"]
        elif de == 0:  # north/south: their south/north row
            p[rows, cols] = ed["s"] if dn == 1 else ed["n"]
        else:  # diagonal: their nearest corner pixel
            row = ed["s"] if dn == 1 else ed["n"]
            p[rows, cols] = row[-1] if de == -1 else row[0]
    return p


_lock = threading.Lock()
_shared: dict[Path, tuple[stac.AltiIndex, Pins]] = {}


def _services(cfg: Config) -> tuple[stac.AltiIndex, Pins]:
    with _lock:
        if cfg.store not in _shared:
            _shared[cfg.store] = (stac.AltiIndex(), Pins(cfg.store))
        return _shared[cfg.store]


class AltiTerrain:
    """LayerBuilder for TERR. `index`, `pins` and `tiles` are injectable for tests."""

    name = "alti"
    tags = ("TERR",)

    def __init__(self, index=None, pins: Pins | None = None, tiles: TileSource | None = None):
        self._index, self._pins, self._tiles = index, pins, tiles

    def build(self, e_km, n_km, cfg, partial, pinned):
        from .build import LayerResult  # build.py imports this module to register the builder

        index, pins = _services(cfg)
        index, pins = self._index or index, self._pins or pins
        tiles = self._tiles or TileSource(cfg)

        def item_for(e: int, n: int) -> str | None:
            if cfg.dev_bbox and not stac.in_dev_area(e, n):
                return None  # not pinned: says nothing about upstream
            return pins.pin("alti", tile_key(e, n), lambda: index.newest(e, n))

        if pinned and "alti" in pinned:
            own = pinned["alti"]
            neighbour_items = {tuple(map(int, k.split("-"))): v for k, v in pinned.get("altiNeighbours", {}).items()}
            neighbour_items = {(e - e_km, n - n_km): v for (e, n), v in neighbour_items.items()}
        else:
            if cfg.dev_bbox and not stac.in_dev_area(e_km, n_km):
                raise OutsideDevArea(f"tile {tile_key(e_km, n_km)} is outside the dev bbox")
            own = item_for(e_km, n_km)
            neighbour_items = {(de, dn): item_for(e_km + de, n_km + dn) for de, dn in NEIGHBOURS}
        if own is None:
            raise LookupError(f"no swissALTI3D tile {tile_key(e_km, n_km)}")

        own_px = tiles.tile(own)
        edges = {d: (tiles.edges(item) if item else None) for d, item in neighbour_items.items()}
        terr = swd.encode_height(nodes_from_padded(padded_pixels(own_px, edges)))
        tiles.release(own)
        return LayerResult(layers={"TERR": terr}, sources={
            "alti": own,
            "altiNeighbours": {tile_key(e_km + de, n_km + dn): item for (de, dn), item in sorted(neighbour_items.items())},
        })
