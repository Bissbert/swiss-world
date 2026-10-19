"""CNPY (canopy height) and TTOP (tree tops) from the swissSURFACE3D Raster DSM. Owner: sidecar-core.

Sources (opendata.swiss "Open use. Must provide the source"):
- swissSURFACE3D Raster, STAC collection ch.swisstopo.swisssurface3d-raster, 0.5 m DSM COG per 1 km tile
  (12-16 MB), © swisstopo. Pinned per tile ("surface"), fetched lazily like ALTI and deleted from raw after
  extraction unless SWISSDATA_KEEP_RAW=1 (Config.keep_surface_raw).
- Waldmischungsgrad LFI (forest mix rate NFI), STAC collection ch.bafu.landesforstinventar-waldmischungsgrad,
  one 10 m COG for the whole country (171 MB, int8 = percent broadleaf, -128 = no forest), © BAFU/WSL.
  Fetched once and kept; pinned as "forestMix" = "<item>@<date>".

Geometry: DSM pixel (r, c) of tile (e, n) covers column dx = c // 2, dz = r // 2 (origin NW corner, Area), its
centre lies at E = e·1000 + 0.5c + 0.25, N = n·1000 + 1000 − 0.5r − 0.25. CHM = DSM − TERR, TERR interpolated
bilinearly between its 2 m nodes at the pixel centre. CNPY = rint(max CHM of the column's 4 pixels), 0 below
2 m. Masked columns (CNPY 0, no tops): building columns (BSPN) plus a 1-column ring (roof edges in the DSM),
water (WKND or WLVL), bridge/dam decks (OFLG), which would otherwise read as tall canopy, and columns whose CHM
exceeds MAX_TREE_M (+1 ring): cliff edges where the 2 m TERR misses the rim, masts, cables; ~0.1 % of the tops.
On rock, scree and glacier (SURF 4/5/6) the CHM is mostly boulders and steep faces the 2 m TERR smooths away, so
canopy is kept there only inside the forest mix raster's forest (the rock class is coarser than the forest edge).

Tree tops, a common individual-tree-detection scheme (local maxima with a height-dependent window, Popescu &
Wynne 2004; the window size is lidR's documented lmf example, Roussel et al. 2020):
1. smooth the masked CHM with a Gaussian (sigma 0.5 m) against crown-surface noise;
2. candidates: 3×3 maxima of the smoothed CHM, at least MIN_TOP_M high;
3. a candidate survives if no higher candidate lies within r(h) = ws(h) / 2, ws(h) = 3 m + 0.1·h (window
   diameter); equal heights are ordered by pixel index, so the result is deterministic;
4. tops on a masked column or on an overlay (OCLS ≠ 0, tunnels excepted) are dropped; heightM = CNPY there;
5. crown radius: canopy columns at least half as high as a top join the nearest top within crown_max_m(h);
   crownDm is the radius of a disc with the joined area (a nearest-top stand-in for a watershed);
6. kind from the forest mix pixel (10 m) under the top: broadleaf with probability = its broadleaf share, drawn
   from a hash of the global column, so rebuilds are byte-identical. No data -> 0; larch is never emitted.

Seams: only the own DSM tile is used, so the result depends on the pins alone, never on what happens to be in
raw. A crown cut by a tile edge rises towards the edge, so its maximum sits on the outermost pixel ring; such
tops are dropped (after the window test, so they still suppress the lower bumps of their own crown): the cell
holding the real top finds it as an interior maximum. A tree on a seam is thus placed once, by the cell that
contains its top, on every side alike; only tops within ~1 m of a seam are lost (the smoothing can push them
onto the edge pixel; ~0.4 % of the trees). Neighbour DSMs are not used: if only one side of a seam had its
neighbour in raw, the result would depend on cache state and build order, and a "suppress near the east/south
edge" band still keeps a duplicate at the west/north edge whenever a crown is wider than the band.
"""

from __future__ import annotations

import logging
import threading
from pathlib import Path

import numpy as np
import rasterio
from rasterio.windows import Window
from scipy import ndimage
from scipy.spatial import cKDTree

from . import stac, swd
from .alti import tile_key
from .config import Config
from .fetch import fetch
from .manifest import Pins

log = logging.getLogger(__name__)

DSM_PX = 2000  # 0.5 m pixels per tile side
MIN_CANOPY_M = 2.0
MIN_TOP_M = 3.0
MAX_TREE_M = 50.0  # the tallest Swiss trees reach ~50-55 m
BARE_SURF = (4, 5, 6)  # rock, scree, glacier
SMOOTH_SIGMA_PX = 1.0
MASKED_OFLG = swd.OFLG_BRIDGE | swd.OFLG_DAM

FOREST_MIX = "ch.bafu.landesforstinventar-waldmischungsgrad"
FOREST_MIX_ITEM = "landesforstinventar-waldmischungsgrad"
MIX_PX_M = 10
MIX_ORIGIN = (2_480_000, 1_300_000)  # NW corner of the national raster
MIX_NODATA = -128


def window_radius_m(h: np.ndarray | float) -> np.ndarray:
    return (3.0 + 0.1 * np.asarray(h, dtype=np.float64)) / 2


def crown_max_m(h: np.ndarray | float) -> np.ndarray:
    return 1.0 + 0.15 * np.asarray(h, dtype=np.float64)


def forest_mix_href(item: str = FOREST_MIX_ITEM) -> str:
    return f"{stac.ASSETS}/{FOREST_MIX}/{item}/{item}_2056.tif"


def read_dsm(path: Path) -> np.ndarray:
    """2000×2000 float64 heights (row 0 = north), NaN = no data."""
    with rasterio.open(path) as ds:
        a = ds.read(1).astype(np.float64)
        if ds.nodata is not None:
            a[a == ds.nodata] = np.nan
    if a.shape != (DSM_PX, DSM_PX):
        raise ValueError(f"{path.name}: unexpected shape {a.shape}")
    return a


def ground_at_pixels(terr_m: np.ndarray) -> np.ndarray:
    """TERR (501×501 node heights in metres) interpolated bilinearly at the 2000×2000 DSM pixel centres."""
    t = (np.arange(DSM_PX) + 0.5) / 4.0  # pixel centre in node units from the W (N) edge
    i0 = np.minimum(t.astype(np.int64), swd.TERR_N - 2)
    f = t - i0
    fy, fx = f[:, None], f[None, :]
    a, b = terr_m[np.ix_(i0, i0)], terr_m[np.ix_(i0, i0 + 1)]
    c, d = terr_m[np.ix_(i0 + 1, i0)], terr_m[np.ix_(i0 + 1, i0 + 1)]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def column_mask(layers: dict[str, np.ndarray], spans: dict[tuple[int, int], list]) -> np.ndarray:
    """1000×1000, True where no canopy may be: buildings (+1 column ring), water, bridge and dam decks."""
    m = np.zeros((swd.CELL, swd.CELL), dtype=bool)
    if spans:
        k = np.array([key for key in spans if 0 <= key[0] < swd.CELL and 0 <= key[1] < swd.CELL], dtype=np.int64)
        if len(k):
            m[k[:, 1], k[:, 0]] = True
            m = ndimage.binary_dilation(m, np.ones((3, 3), dtype=bool))
    if "WKND" in layers:
        m |= layers["WKND"] != 0
    if "WLVL" in layers:
        m |= layers["WLVL"] != swd.NO_HEIGHT
    if "OFLG" in layers:
        m |= (layers["OFLG"] & MASKED_OFLG) != 0
    return m


def canopy_height(chm: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """CNPY: whole metres, max of each column's 4 pixels, 0 below MIN_CANOPY_M and on masked columns."""
    col = chm.reshape(swd.CELL, 2, swd.CELL, 2).max(axis=(1, 3))
    h = np.where(col < MIN_CANOPY_M, 0.0, np.minimum(np.rint(col), 255.0))
    h[mask] = 0
    return h.astype("u1")


def detect_tops(chm: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Rows, cols and smoothed heights of the tree tops in a (masked) pixel CHM, after the variable-window test;
    tops on the outermost pixel ring (crowns cut by the tile edge) are dropped."""
    s = ndimage.gaussian_filter(chm, SMOOTH_SIGMA_PX, mode="nearest")
    peak = (s == ndimage.maximum_filter(s, size=3, mode="nearest")) & (s >= MIN_TOP_M)
    r, c = np.nonzero(peak)
    h = s[r, c]
    order = np.lexsort((r * DSM_PX + c, -h))  # highest first, ties by pixel index
    r, c, h = r[order], c[order], h[order]
    radius = window_radius_m(h)
    pts = np.column_stack([c, r]) * 0.5
    keep = np.ones(len(h), dtype=bool)
    if len(h):
        for k, near in enumerate(cKDTree(pts).query_ball_point(pts, radius)):
            keep[k] = min(near) == k  # no higher (or equal and earlier) candidate within r(h)
    keep &= (r > 0) & (r < DSM_PX - 1) & (c > 0) & (c < DSM_PX - 1)
    return r[keep], c[keep], h[keep]


def crown_radii_dm(dx: np.ndarray, dz: np.ndarray, height: np.ndarray, cnpy: np.ndarray) -> np.ndarray:
    """Crown radius per top in decimetres: canopy columns >= half the top's height join the nearest top within
    crown_max_m; the radius is that of a disc with the joined area (≥ one column)."""
    n = len(dx)
    if n == 0:
        return np.zeros(0, dtype="u1")
    zz, xx = np.nonzero(cnpy)
    d, i = cKDTree(np.column_stack([dx, dz])).query(np.column_stack([xx, zz]),
                                                   distance_upper_bound=float(crown_max_m(height.max())))
    hit = i < n
    i, d, ch = i[hit], d[hit], cnpy[zz[hit], xx[hit]]
    ok = (d <= crown_max_m(height[i])) & (ch >= 0.5 * height[i])
    area = np.maximum(np.bincount(i[ok], minlength=n), 1)
    return np.clip(np.rint(np.sqrt(area / np.pi) * 10), 1, 255).astype("u1")


def _unit_hash(x: np.ndarray) -> np.ndarray:
    """splitmix64 of uint64 keys -> floats in [0, 1)."""
    z = x.astype(np.uint64) + np.uint64(0x9E3779B97F4A7C15)
    z = (z ^ (z >> np.uint64(30))) * np.uint64(0xBF58476D1CE4E5B9)
    z = (z ^ (z >> np.uint64(27))) * np.uint64(0x94D049BB133111EB)
    z ^= z >> np.uint64(31)
    return (z >> np.uint64(11)).astype(np.float64) / float(1 << 53)


def tree_kinds(e_km: int, n_km: int, dx: np.ndarray, dz: np.ndarray, mix: np.ndarray | None) -> np.ndarray:
    """kind per top from the 100×100 broadleaf percentages of the cell (row 0 = north), see module doc."""
    if mix is None or len(dx) == 0:
        return np.zeros(len(dx), dtype="u1")
    p = mix[dz // MIX_PX_M, dx // MIX_PX_M].astype(np.int64)
    key = (np.int64(e_km * 1000) + dx).astype(np.uint64) << np.uint64(32) | (np.int64(n_km * 1000) + dz).astype(np.uint64)
    broad = _unit_hash(key) * 100 < p
    kind = np.where(broad, swd.KIND_BROADLEAF, swd.KIND_CONIFER)
    return np.where((p < 0) | (p > 100), swd.KIND_UNKNOWN, kind).astype("u1")


def canopy_and_trees(dsm: np.ndarray, layers: dict[str, np.ndarray], spans: dict, e_km: int, n_km: int,
                     mix: np.ndarray | None = None) -> tuple[np.ndarray, list[swd.Tree]]:
    """CNPY and the cell's trees from its 0.5 m DSM and the layers built so far (TERR required)."""
    chm = dsm - ground_at_pixels(swd.decode_height(layers["TERR"]))
    chm = np.where(np.isfinite(chm), np.maximum(chm, 0.0), 0.0)
    mask = column_mask(layers, spans)
    too_high = chm.reshape(swd.CELL, 2, swd.CELL, 2).max(axis=(1, 3)) > MAX_TREE_M
    mask |= ndimage.binary_dilation(too_high, np.ones((3, 3), dtype=bool))
    if "SURF" in layers:
        forest = np.zeros((swd.CELL, swd.CELL), dtype=bool) if mix is None else \
            np.repeat(np.repeat(mix != MIX_NODATA, MIX_PX_M, axis=0), MIX_PX_M, axis=1)
        mask |= np.isin(layers["SURF"], BARE_SURF) & ~forest
    cnpy = canopy_height(chm, mask)
    chm[np.repeat(np.repeat(mask, 2, axis=0), 2, axis=1)] = 0.0
    r, c, _ = detect_tops(chm)
    dx, dz = c // 2, r // 2
    keep = cnpy[dz, dx] > 0
    if "OCLS" in layers:
        tunnel = (layers["OFLG"][dz, dx] & swd.OFLG_TUNNEL) != 0 if "OFLG" in layers else False
        keep &= (layers["OCLS"][dz, dx] == 0) | tunnel
    dx, dz = dx[keep], dz[keep]
    height = cnpy[dz, dx].astype(np.float64)
    crown = crown_radii_dm(dx, dz, height, cnpy)
    kind = tree_kinds(e_km, n_km, dx, dz, mix)
    trees = [swd.Tree(int(x), int(z), int(h), int(cr), int(k)) for x, z, h, cr, k in zip(dx, dz, height, crown, kind)]
    return cnpy, sorted(trees, key=lambda t: (t.dz, t.dx))


def read_forest_mix(path: Path, e_km: int, n_km: int) -> np.ndarray:
    """The cell's 100×100 window of the national forest mix raster (int8 broadleaf %, MIX_NODATA = no forest)."""
    col = (e_km * 1000 - MIX_ORIGIN[0]) // MIX_PX_M
    row = (MIX_ORIGIN[1] - (n_km * 1000 + 1000)) // MIX_PX_M
    n = swd.CELL // MIX_PX_M
    with rasterio.open(path) as ds:
        # Switzerland lies well inside the national raster, so the window never needs padding
        return ds.read(1, window=Window(col, row, n, n))


_lock = threading.Lock()
_shared: dict[Path, tuple[stac.SurfaceIndex, Pins]] = {}
_mix_pin: list[str] = []


def _services(cfg: Config) -> tuple[stac.SurfaceIndex, Pins]:
    with _lock:
        if cfg.store not in _shared:
            _shared[cfg.store] = (stac.SurfaceIndex(), Pins(cfg.store))
        return _shared[cfg.store]


def forest_mix_pin() -> str:
    """"<item>@<date>" of the current forest mix release (one STAC query per process)."""
    with _lock:
        if not _mix_pin:
            f = next(stac.items(FOREST_MIX))
            _mix_pin.append(f"{f['id']}@{f['properties']['datetime'][:10]}")
        return _mix_pin[0]


class CanopyBuilder:
    """LayerBuilder for CNPY and TTOP; runs after water, overlays and buildings, whose columns it masks.
    `index`, `pins`, `dsm_for` (item -> DSM array) and `mix_for` ((e, n) -> mix window) are injectable for tests."""

    name = "canopy"
    tags = ("CNPY", "TTOP")

    def __init__(self, index=None, pins: Pins | None = None, dsm_for=None, mix_for=None):
        self._index, self._pins, self._dsm_for, self._mix_for = index, pins, dsm_for, mix_for

    def build(self, e_km, n_km, cfg, partial, pinned):
        from .build import LayerResult  # build.py imports this module to register the builder

        if pinned and "surface" in pinned:
            item = pinned["surface"]
        else:
            index, pins = _services(cfg)
            index, pins = self._index or index, self._pins or pins
            item = pins.pin("surface", tile_key(e_km, n_km), lambda: index.newest(e_km, n_km))
        if item is None:
            return LayerResult()  # no DSM: no CNPY/TTOP, the mod keeps its statistical trees
        mix_pin = (pinned or {}).get("forestMix") or (None if self._mix_for else forest_mix_pin())
        if self._dsm_for:
            dsm = self._dsm_for(item)
        else:
            path = fetch(cfg, stac.surface_href(item))
            try:
                dsm = read_dsm(path)
            finally:  # also after a failed read: a broken tile is fetched again on retry
                if not cfg.keep_surface_raw:
                    path.unlink(missing_ok=True)
        if self._mix_for:
            mix = self._mix_for(e_km, n_km)
        else:
            mix = read_forest_mix(fetch(cfg, forest_mix_href(mix_pin.split("@")[0])), e_km, n_km)
        cnpy, trees = canopy_and_trees(dsm, partial.layers, partial.spans, e_km, n_km, mix)
        sources = {"surface": item} | ({"forestMix": mix_pin} if mix_pin else {})
        return LayerResult(layers={"CNPY": cnpy}, trees=trees, sources=sources)
