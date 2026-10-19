"""coverage.bin and the INSD layer from swissBOUNDARIES3D. Owner: sidecar-core.

Source: layer TLM_LANDESGEBIET of the gdb.zip (19 MB, the smallest format; gpkg/shp are ~36 MB), feature
ICC = "CH" only (the layer also holds Liechtenstein and the Büsingen/Campione enclaves, which are holes in
CH). Z is dropped. The item is pinned in pins.json ("boundaries") and recorded in each border cell's sources.

Grid: E 2480–2840 km, N 1070–1300 km as half-open km ranges, so 360 × 230 cells whose SW corners are
E 2480…2839 and N 1070…1299. Everything outside the grid is state 0 (the mod's Coverage.state does the same).
A cell is 2 if CH contains it, 1 if they share area, else 0 (touching only along an edge counts as outside).

INSD (border cells only): 1 where the column centre lies inside CH (rasterize with all_touched=False).
"""

from __future__ import annotations

import logging
import threading
from pathlib import Path

import fiona
import numpy as np
import shapely
from rasterio.features import rasterize
from rasterio.transform import Affine
from shapely.geometry import shape

from . import stac, swd
from .config import Config
from .fetch import fetch
from .manifest import Pins

log = logging.getLogger(__name__)

E_MIN, N_MIN, WIDTH, HEIGHT = 2480, 1070, 360, 230
OUTSIDE, BORDER, INSIDE = 0, 1, 2
LAYER = "TLM_LANDESGEBIET"

_lock = threading.Lock()
_cache: dict[str, shapely.Geometry] = {}


def boundaries_item(cfg: Config) -> str:
    return Pins(cfg.store).pin("boundaries", "ch", lambda: stac.latest_item_id(stac.BOUNDARIES))


def load_switzerland(cfg: Config, item: str) -> shapely.Geometry:
    """CH national territory as a 2D (Multi)Polygon in LV95. Cached per process."""
    with _lock:
        if item not in _cache:
            path = fetch(cfg, stac.boundaries_href(item))
            # the zip's member names use backslashes; GDAL still resolves the .gdb directory by name
            gdb = f"zip://{path}!{_gdb_name(path)}"
            with fiona.open(gdb, layer=LAYER) as src:
                geoms = [shape(f.geometry) for f in src if f.properties["ICC"] == "CH"]
            if len(geoms) != 1:
                raise ValueError(f"expected one CH feature in {item}, got {len(geoms)}")
            g = shapely.force_2d(geoms[0])
            shapely.prepare(g)
            _cache[item] = g
        return _cache[item]


def _gdb_name(path: Path) -> str:
    import zipfile

    with zipfile.ZipFile(path) as z:
        first = z.namelist()[0].replace("\\", "/")
    return first.split("/")[0]


def classify(ch: shapely.Geometry) -> np.ndarray:
    """grid[n − N_MIN, e − E_MIN] in {0, 1, 2}."""
    e = np.arange(E_MIN, E_MIN + WIDTH)[None, :] * 1000.0
    n = np.arange(N_MIN, N_MIN + HEIGHT)[:, None] * 1000.0
    ee, nn = np.broadcast_arrays(e, n)
    boxes = shapely.box(ee, nn, ee + 1000.0, nn + 1000.0)
    grid = np.zeros(boxes.shape, dtype="u1")
    inside = shapely.contains(ch, boxes)
    grid[inside] = INSIDE
    cand = shapely.intersects(ch, boxes) & ~inside
    areas = shapely.area(shapely.intersection(ch, boxes[cand]))
    grid[cand] = np.where(areas > 0.0, BORDER, OUTSIDE)
    return grid


def state(grid: np.ndarray, e_km: int, n_km: int) -> int:
    x, y = e_km - E_MIN, n_km - N_MIN
    if not (0 <= x < WIDTH and 0 <= y < HEIGHT):
        return OUTSIDE
    return int(grid[y, x])


def ensure_coverage(cfg: Config) -> np.ndarray:
    """Loads coverage.bin, building it from swissBOUNDARIES3D on first use."""
    path = Path(cfg.store) / "coverage.bin"
    if path.exists():
        e0, n0, grid = swd.decode_coverage(path.read_bytes())
        if (e0, n0, grid.shape) == (E_MIN, N_MIN, (HEIGHT, WIDTH)):
            return grid
        log.warning("coverage.bin has an unexpected grid; rebuilding")
    item = boundaries_item(cfg)
    grid = classify(load_switzerland(cfg, item))
    swd.write_atomic(path, swd.encode_coverage(E_MIN, N_MIN, grid))
    log.info("coverage.bin from %s: %d inside, %d border", item, int((grid == INSIDE).sum()), int((grid == BORDER).sum()))
    return grid


def insd_mask(ch: shapely.Geometry, e_km: int, n_km: int) -> np.ndarray:
    """u8[1000, 1000], row = dz (south of the north edge), col = dx."""
    box = shapely.box(e_km * 1000, n_km * 1000, e_km * 1000 + 1000, n_km * 1000 + 1000)
    part = shapely.intersection(ch, box)
    if part.is_empty:
        return np.zeros((swd.CELL, swd.CELL), dtype="u1")
    transform = Affine(1.0, 0.0, e_km * 1000.0, 0.0, -1.0, n_km * 1000.0 + 1000.0)
    return rasterize([(part, 1)], out_shape=(swd.CELL, swd.CELL), transform=transform, fill=0,
                     all_touched=False, dtype="uint8")


class BorderMask:
    """LayerBuilder for INSD: only border cells (coverage state 1) get the layer."""

    name = "insd"
    tags = ("INSD",)

    def __init__(self, grid: np.ndarray | None = None):
        self._grid = grid

    def build(self, e_km, n_km, cfg, partial, pinned):
        from .build import LayerResult  # build.py imports this module to register the builder

        if self._grid is None:
            self._grid = ensure_coverage(cfg)
        grid = self._grid
        if state(grid, e_km, n_km) != BORDER:
            return LayerResult()
        item = (pinned or {}).get("boundaries") or boundaries_item(cfg)
        return LayerResult(layers={"INSD": insd_mask(load_switzerland(cfg, item), e_km, n_km)},
                           sources={"boundaries": item})
