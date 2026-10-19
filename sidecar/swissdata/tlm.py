"""swissTLM3D: one-time extract of the layers we need, then fast per-cell reads. Owner: sidecar-tlm.

The national gdb.zip (2.9 GB) is fetched once. `ensure_extract` copies every feature that intersects
EXTRACT_BBOX from the needed layers (only the attributes we use, Z kept) into a small GeoPackage under
$SWISSDATA_RAW/extracts/. Features are selected, not cut, so lines and lake polygons stay whole and Z
values stay exact; cells never open the national file again.

OBJEKTART and the other attributes are integer codes; the names in the comments below come from the
FGDB's own subtype/domain tables (GDB_Items).
"""

from __future__ import annotations

import fcntl
import os
from functools import lru_cache
from pathlib import Path

import fiona
import numpy as np
import shapely
from rasterio import features
from rasterio.transform import Affine
from shapely.geometry import shape

from . import swd
from .config import Config
from .fetch import fetch

TLM_ITEM = "swisstlm3d_2026-02"
TLM_URL = (f"https://data.geo.admin.ch/ch.swisstopo.swisstlm3d/{TLM_ITEM}/"
           f"{TLM_ITEM}_2056_5728.gdb.zip")
TLM_GDB = "swissTLM3D_2026_LV95_LN02.gdb"

# Test bbox (WGS84 7.80-8.39 E, 46.56-46.80 N) in LV95 plus a 2 km margin.
EXTRACT_BBOX = (2_626_000, 1_152_000, 2_674_000, 1_183_000)

# layer -> attributes kept (besides OBJEKTART).
LAYERS: dict[str, tuple[str, ...]] = {
    "TLM_BODENBEDECKUNG": (),
    "TLM_STEHENDES_GEWAESSER": ("NAME",),
    "TLM_FLIESSGEWAESSER": ("VERLAUF", "NAME", "TLM_GEWAESSER_LAUF_UUID"),
    "TLM_STRASSE": ("KUNSTBAUTE", "BELAGSART", "STUFE"),
    "TLM_EISENBAHN": ("KUNSTBAUTE", "ZAHNRADBAHN", "STANDSEILBAHN", "AUSSER_BETRIEB", "NAME"),
    "TLM_STAUBAUTE": ("NAME",),
    "TLM_VERKEHRSBAUTE_PLY": (),
    "TLM_EINZELOBJEKT": ("NAME",),
    "TLM_NUTZUNGSAREAL": (),
    "TLM_VERKEHRSAREAL": (),
    "TLM_FREIZEITAREAL": (),
    "TLM_SPORTBAUTE_PLY": (),
    "TLM_SIEDLUNGSNAME": ("NAME",),
    "TLM_GEBAEUDE_FOOTPRINT": (),
}

# A few codes used by several builders (TLM 2026 domains).
KUNSTBAUTE_NONE = 100
BOOL_YES = 2  # TLM_BOOLEAN_CD: 1 = nein, 2 = ja


def check_pinned(pinned: dict | None) -> None:
    """A rebuild must use the TLM release the cell was built from; only TLM_ITEM is extracted."""
    if pinned and pinned.get("tlm") not in (None, TLM_ITEM):
        raise ValueError(f"cell pinned to {pinned['tlm']}, only {TLM_ITEM} is extracted")


def extract_path(cfg: Config) -> Path:
    return cfg.raw / "extracts" / f"{TLM_ITEM}_tlm-extract.gpkg"


def ensure_extract(cfg: Config) -> Path:
    """Builds the extract once (fetching the national file if needed). Safe across processes."""
    dest = extract_path(cfg)
    if dest.exists():
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    with open(dest.with_name(dest.name + ".lock"), "w") as lf:
        fcntl.flock(lf, fcntl.LOCK_EX)
        if dest.exists():
            return dest
        src = f"zip://{fetch(cfg, TLM_URL)}!{TLM_GDB}"
        part = dest.with_name(dest.name + ".part.gpkg")
        part.unlink(missing_ok=True)
        for layer, extra in LAYERS.items():
            _copy_layer(src, layer, ("OBJEKTART", *extra), part)
        os.replace(part, dest)
    return dest


def _copy_layer(src: str, layer: str, fields: tuple[str, ...], dest: Path) -> None:
    with fiona.open(src, layer=layer) as s:
        props = {k: v for k, v in s.schema["properties"].items() if k in fields}
        schema = {"geometry": s.schema["geometry"], "properties": props}
        with fiona.open(dest, "w", driver="GPKG", layer=layer, schema=schema, crs=s.crs) as d:
            d.writerecords(
                {"geometry": f.geometry, "properties": {k: f.properties[k] for k in props}}
                for f in s.filter(bbox=EXTRACT_BBOX) if f.geometry is not None)


def read(cfg: Config, layer: str, bbox: tuple[float, float, float, float]) -> list[tuple[object, dict]]:
    """Features of `layer` whose bbox intersects `bbox` (LV95), as (shapely geometry with Z, attributes)."""
    path = ensure_extract(cfg)
    with fiona.open(path, layer=layer) as s:
        return [(shape(f.geometry), dict(f.properties)) for f in s.filter(bbox=bbox)]


@lru_cache(maxsize=4)
def read_all(path: Path, layer: str) -> tuple[tuple[object, dict], ...]:
    """Whole extract layer, cached per process (for network-wide computations like river levels)."""
    with fiona.open(path, layer=layer) as s:
        return tuple((shape(f.geometry), dict(f.properties)) for f in s)


def cell_bbox(e_km: int, n_km: int, margin: float = 0.0) -> tuple[float, float, float, float]:
    e0, n0 = e_km * 1000, n_km * 1000
    return (e0 - margin, n0 - margin, e0 + 1000 + margin, n0 + 1000 + margin)


def cell_box(e_km: int, n_km: int, margin: float = 0.0):
    return shapely.box(*cell_bbox(e_km, n_km, margin))


# --- rasterising onto the cell grids (docs/swd-format.md §1, §3) ---

def burn(shapes: list[tuple[object, int]], e_km: int, n_km: int, pad: int = 0, fill: int = 0,
         dtype: str = "int32", all_touched: bool = False) -> np.ndarray:
    """Rasterises (geometry, value) pairs onto the 1 m column grid, rows = dz. Later shapes win.
    `pad` adds that many columns on every side (for neighbour tests that must agree across cells)."""
    size = swd.CELL + 2 * pad
    if not shapes:
        return np.full((size, size), fill, dtype=dtype)
    t = Affine(1, 0, e_km * 1000 - pad, 0, -1, n_km * 1000 + swd.CELL + pad)
    return features.rasterize(shapes, out_shape=(size, size), transform=t, fill=fill, dtype=dtype,
                              all_touched=all_touched)


def burn_nodes(shapes: list[tuple[object, int]], e_km: int, n_km: int, fill: int = 0,
               dtype: str = "int32", all_touched: bool = False) -> np.ndarray:
    """Like `burn`, on the 2 m TERR node grid: node (i, j) is the centre of a 2 m pixel."""
    if not shapes:
        return np.full((swd.TERR_N, swd.TERR_N), fill, dtype=dtype)
    t = Affine(2, 0, e_km * 1000 - 1, 0, -2, n_km * 1000 + swd.CELL + 1)
    return features.rasterize(shapes, out_shape=(swd.TERR_N, swd.TERR_N), transform=t, fill=fill,
                              dtype=dtype, all_touched=all_touched)


def column_points(e_km: int, n_km: int, dz: np.ndarray, dx: np.ndarray) -> np.ndarray:
    """Shapely points at the centres of columns (dz, dx)."""
    return shapely.points(e_km * 1000 + dx + 0.5, n_km * 1000 + swd.CELL - dz - 0.5)


def node_points(e_km: int, n_km: int, j: np.ndarray, i: np.ndarray) -> np.ndarray:
    return shapely.points(e_km * 1000 + 2.0 * i, n_km * 1000 + swd.CELL - 2.0 * j)


def terr_columns(terr: np.ndarray) -> np.ndarray:
    """Bilinear TERR (u16 nodes) at every column centre, in metres; NaN where a node has no value."""
    h = swd.decode_height(terr)
    f = (np.arange(swd.CELL) + 0.5) / 2.0  # column centre in node units
    i0 = np.floor(f).astype(int)
    t = f - i0
    rows = h[i0] * (1 - t)[:, None] + h[i0 + 1] * t[:, None]
    return rows[:, i0] * (1 - t)[None, :] + rows[:, i0 + 1] * t[None, :]


def value_along(lines: np.ndarray, points: np.ndarray) -> np.ndarray:
    """Z of the 3D line `lines[k]` at the point on it nearest to `points[k]` (vectorised)."""
    d = shapely.line_locate_point(lines, points)
    return shapely.get_coordinates(shapely.line_interpolate_point(lines, d), include_z=True)[:, 2]


def single_lines(geom) -> list:
    return [g for g in shapely.get_parts(geom) if not g.is_empty]


def main() -> None:
    print(ensure_extract(Config.from_env()))


if __name__ == "__main__":
    main()
