"""REGN, LITH, TOPO and HABT: per-column inputs for the mod's ground-zone classifier. Owner: sidecar-zones.

Bit layouts are in docs/swd-format.md §6b; the taxonomy they feed is in the mod's texture stage.

Sources (all national, fetched once into $SWISSDATA_RAW and kept, ~36 MB together). Each is pinned as
"<STAC item>@<item date>" under its own manifest key; the zip must contain the shapefile named below, so a new
upstream release (which replaces the file at the same URL) fails loudly instead of silently changing cells:
- bioRegions: BAFU "Biogeographische Regionen der Schweiz" (2020 revision, 12 polygons), opendata.swiss
  "Open use" (BAFU OGD), ch.bafu.biogeographische_regionen, 4.1 MB.
- lithology: swisstopo GK500 Geotechnik, Lithologie Hauptgruppen (2006), layer Lithologie_Aggregiert
  (12,247 polygons, field LITHOHAUPT), opendata.swiss "Open use. Must provide the source" (© swisstopo),
  ch.swisstopo.geologie-geotechnik-gk500-lithologie_hauptgruppen, 17.3 MB. Despite the _2056 asset name this
  layer is in LV03 (EPSG:21781); it is shifted by +2,000,000/+1,000,000 m. That is deliberate: FINELTRA
  differs from the plain shift by < 2 m, far below the map's 1:500,000 precision.
- raisedBogs / fens / floodplains: BAFU Bundesinventare der Hoch- und Übergangsmoore (2025 revision),
  der Flachmoore (2025) and der Auengebiete (2017, revised 2022; both Anhang 1 and Anhang 2), opendata.swiss
  "Open use" (BAFU OGD), 3.0 / 10.4 / 1.5 MB.

REGN, LITH, HABT: polygons are rasterised per column (column centre inside, tlm.burn). Columns outside every
region polygon (abroad, slivers between polygons) and GK500 glacier/firn polygons take the value of the nearest
source pixel on a national 100 m raster (scipy's exact Euclidean distance transform over the whole country), so
the fill is the same for every cell that asks.

TOPO is computed from the raw swissALTI3D 2 m pixels (not TERR) of the cell's tile and its 8 pinned neighbours
(sources "alti" / "altiNeighbours"), cropped to the cell ± MARGIN pixels. Every metric of a pixel depends only on
pixels within MARGIN, so it is a pure function of the DEM around it: two cells compute the same value for any
pixel both can see, whatever their crop. Missing tiles (abroad) are NaN and drop out of every sum. Each 2 m pixel
writes its 2×2 columns (pixel (r, c) holds columns dx = 2c, 2c+1, dz = 2r, 2r+1).
- TPI at radius 50 m and 300 m: height minus the mean height of the square window (side 2R+1 pixels), from
  int64 integral images of decimetre heights (exact). Ridge above +T, valley below −T, else flat when the slope
  is below FLAT_SLOPE, else mid-slope.
- Flow: D8 steepest descent on decimetre heights (strict descent, ties to the first direction in D8), no pit
  filling. Accumulation has a fixed reach: A₀ = 1, A_{k+1} = 1 + Σ A_k of the donors, FLOW_STEPS times, i.e.
  the number of pixels whose flow path reaches the pixel within FLOW_STEPS steps (300 m). Gully/channel are
  thresholds on A; a wet hollow is a valley-floor pixel (TPI50 valley) that collects at least HOLLOW_PX but
  hardly drains on (steepest descent below HOLLOW_DESCENT); it wins over gully/channel. Water columns (WKND ≠ 0) get flow 0.
- Wind: Winstral's Sx (max upwind slope within 300 m), averaged over the directions 270°, 292.5° and 315°
  (westerly to north-westerly, the snow-drift winds of the Swiss Alps), sampled at fixed integer offsets.
  Lee above LEE_SX, exposed below EXPOSED_SX.
Cost: ~1 s per cell on top of reading the 9 ALTI tiles (kept in raw in dev; in production the own tile was
just released by the terrain builder and is fetched again).
"""

from __future__ import annotations

import logging
import threading
import zipfile
from dataclasses import dataclass
from pathlib import Path

import fiona
import numpy as np
import shapely
from rasterio import features
from rasterio.transform import Affine
from scipy import ndimage
from shapely.geometry import shape

from . import stac, swd, tlm
from .alti import TILE_PX, read_tile
from .config import Config
from .fetch import fetch

log = logging.getLogger(__name__)


# ---------------------------------------------------------------- sources

@dataclass(frozen=True)
class Source:
    key: str  # manifest "sources" key
    collection: str
    item: str
    date: str  # STAC item datetime (day)
    members: tuple[str, ...]  # shapefiles inside the zip

    @property
    def pin(self) -> str:
        return f"{self.item}@{self.date}"

    @property
    def href(self) -> str:
        return f"{stac.ASSETS}/{self.collection}/{self.item}/{self.item}_2056.shp.zip"


BIO = Source("bioRegions", "ch.bafu.biogeographische_regionen", "biogeographische_regionen", "2020-09-01",
             ("N2020_Revision_BiogeoRegion.shp",))
LITHO = Source("lithology", "ch.swisstopo.geologie-geotechnik-gk500-lithologie_hauptgruppen",
               "geologie-geotechnik-gk500-lithologie_hauptgruppen", "2006-03-04", ("Lithologie_Aggregiert.shp",))
BOGS = Source("raisedBogs", "ch.bafu.bundesinventare-hochmoore", "bundesinventare-hochmoore", "2025-12-01",
              ("N2025_Revision_hochmoor_20251201.shp",))
FENS = Source("fens", "ch.bafu.bundesinventare-flachmoore", "bundesinventare-flachmoore", "2025-12-01",
              ("N2025_Revision_flachmoor_20251201.shp",))
AUEN = Source("floodplains", "ch.bafu.bundesinventare-auen", "bundesinventare-auen", "2022-11-22",
              ("N2017_Revision_Auengebiete_20171101_20221122.shp", "N2017_Revision_Auengebiete_Anhang2_20171101.shp"))
SOURCES = (BIO, LITHO, BOGS, FENS, AUEN)


def check_pinned(pinned: dict | None) -> None:
    """A rebuild must use the releases the cell was built from; only the ones above are supported."""
    for s in SOURCES:
        if pinned and pinned.get(s.key) not in (None, s.pin):
            raise ValueError(f"cell pinned to {s.key} {pinned[s.key]}, only {s.pin} is supported")


# ---------------------------------------------------------------- REGN

REGN_FILLED = 0x40  # outside every region polygon: nearest region


def regn_code(region: int, subregion: int) -> int:
    """region 1–6 in bits 0–2, the subregion's last digit (1–4) in bits 3–5."""
    return region | (subregion % 10) << 3


# ---------------------------------------------------------------- LITH

UNKNOWN, LIMESTONE, CRYSTALLINE, SCHIST, MOLASSE, QUATERNARY = range(6)
LITH_GLACIER = 0x08  # GK500 glacier/firn: class and detail of the nearest rock
LITH_LAKE = 0x10  # GK500 lake: class quaternary
GK500_LAKE, GK500_GLACIER = 80, 90
# GK500 LITHOHAUPT -> (class, detail); detail = bits 5–7, its meaning depends on the class
LITHOHAUPT: dict[int, tuple[int, int]] = {
    40: (LIMESTONE, 0),  # Kalksteine
    42: (LIMESTONE, 1),  # Radiolarite, Hornsteine
    44: (LIMESTONE, 2),  # Tone, Rauwacken (Trias: Gips, Rauwacke)
    47: (LIMESTONE, 3),  # Dolomite
    48: (LIMESTONE, 4),  # Dolomit- und Kalkmarmore
    50: (CRYSTALLINE, 0),  # Granite, Syenite
    52: (CRYSTALLINE, 1),  # Porphyrite
    54: (CRYSTALLINE, 1),  # Vulkanite
    49: (CRYSTALLINE, 2),  # Quarzite
    60: (CRYSTALLINE, 3),  # Gneise
    62: (CRYSTALLINE, 4),  # Glimmerschiefer, Gneise
    70: (CRYSTALLINE, 5),  # Amphibolite
    72: (CRYSTALLINE, 6),  # Basische Gesteine (Gabbro, Prasinit)
    74: (CRYSTALLINE, 7),  # Ultrabasische Gesteine (Serpentinit)
    30: (SCHIST, 0),  # Tongesteine, Tonschiefer
    32: (SCHIST, 1),  # Mergelschiefer, Kalkphyllite (Bündnerschiefer, Flysch)
    61: (SCHIST, 2),  # Quarzphyllite
    20: (MOLASSE, 0),  # Mergel, z.T. Sandstein
    22: (MOLASSE, 1),  # Sandsteine
    24: (MOLASSE, 2),  # Konglomerate, Brekzien (Nagelfluh)
    10: (QUATERNARY, 0),  # Tone, Silte, Sande
    12: (QUATERNARY, 1),  # Sande, Kiese, Gerölle
    14: (QUATERNARY, 2),  # Blöcke, Gerölle
}


def lith_lut() -> np.ndarray:
    """LITHOHAUPT (0–255) -> LITH byte; 0 for unknown codes, glacier and lake handled by the caller."""
    lut = np.zeros(256, dtype="u1")
    for code, (cls, detail) in LITHOHAUPT.items():
        lut[code] = cls | detail << 5
    lut[GK500_LAKE] = QUATERNARY | LITH_LAKE
    return lut


# ---------------------------------------------------------------- HABT

HAB_RAISED_BOG = 0x01  # Hochmoor Typ 1/2 (primary/secondary bog surface), 5 (bare peat)
HAB_BOG_MARGIN = 0x02  # Hochmoor Typ 3 (Hochmoorumfeld)
HAB_FEN = 0x04  # Flachmoor
HAB_AUE_SHIFT = 3  # bits 3–5: Auen type (1 glacier forefield … 5 lake shore), 0 = none
BOG_TYPES, BOG_MARGIN_TYPES = (1, 2, 5), (3,)  # Typ 4 (open water) is left to WKND


# ---------------------------------------------------------------- national 100 m fill grid

GRID_E, GRID_N, GRID_PX = 2_480_000, 1_300_000, 100  # NW corner, same extent as coverage.bin
GRID_W, GRID_H = 3600, 2300


def nearest_fill(shapes: list[tuple[object, int]], keep: np.ndarray) -> np.ndarray:
    """National GRID_H×GRID_W raster of `shapes` (values 0–255) where every pixel whose value is not in `keep`
    (a 256-bool LUT) takes the value of the nearest pixel that is."""
    t = Affine(GRID_PX, 0, GRID_E, 0, -GRID_PX, GRID_N)
    r = features.rasterize(shapes, out_shape=(GRID_H, GRID_W), transform=t, fill=0, dtype="uint8")
    src = keep[r]
    if not src.any():
        return r
    idx = ndimage.distance_transform_edt(~src, return_distances=False, return_indices=True)
    return r[idx[0], idx[1]]


def grid_columns(grid: np.ndarray, e_km: int, n_km: int) -> np.ndarray:
    """1000×1000 lookup of the 100 m grid pixel holding each column centre (0 outside the grid)."""
    dx = np.arange(swd.CELL)
    col = (e_km * 1000 + dx - GRID_E) // GRID_PX
    row = (GRID_N - n_km * 1000 - swd.CELL + dx) // GRID_PX  # dz runs south like the grid rows
    ok_c, ok_r = (col >= 0) & (col < GRID_W), (row >= 0) & (row < GRID_H)
    out = grid[np.clip(row, 0, GRID_H - 1)][:, np.clip(col, 0, GRID_W - 1)]
    out[~ok_r, :] = 0
    out[:, ~ok_c] = 0
    return out


class Vectors:
    """The national polygon sets as (geometry, value) lists, with STR-trees for per-cell queries and the
    nearest-fill grids. Built from the real sources by load_vectors(), or from synthetic shapes in tests."""

    def __init__(self, regions, lithology, bogs, fens, auen):
        self._sets = {"regions": regions, "lithology": lithology, "bogs": bogs, "fens": fens, "auen": auen}
        self._trees = {k: shapely.STRtree([g for g, _ in v]) for k, v in self._sets.items()}
        keep_regions = np.zeros(256, dtype=bool)
        keep_regions[1:] = True
        self.region_fill = nearest_fill(regions, keep_regions)
        keep_rock = np.zeros(256, dtype=bool)
        keep_rock[list(LITHOHAUPT)] = True
        self.rock_fill = nearest_fill(lithology, keep_rock)

    def shapes(self, kind: str, e_km: int, n_km: int) -> list[tuple[object, int]]:
        hits = self._trees[kind].query(tlm.cell_box(e_km, n_km))
        items = self._sets[kind]
        return [items[i] for i in sorted(hits)]  # file order, so overlaps resolve the same in every cell

    def regn(self, e_km: int, n_km: int) -> np.ndarray:
        r = tlm.burn(self.shapes("regions", e_km, n_km), e_km, n_km, dtype="uint8")
        miss = r == 0
        if miss.any():
            fill = grid_columns(self.region_fill, e_km, n_km)
            r[miss] = np.where(fill[miss] != 0, fill[miss] | REGN_FILLED, 0)
        return r

    def lith(self, e_km: int, n_km: int) -> np.ndarray:
        code = tlm.burn(self.shapes("lithology", e_km, n_km), e_km, n_km, dtype="uint8")
        lut = lith_lut()
        out = lut[code]
        fill = (code == GK500_GLACIER) | (out == 0)
        if fill.any():
            rock = lut[grid_columns(self.rock_fill, e_km, n_km)[fill]]
            out[fill] = np.where((code[fill] == GK500_GLACIER) & (rock != 0), rock | LITH_GLACIER, rock)
        return out

    def hab(self, e_km: int, n_km: int) -> np.ndarray:
        bogs = self.shapes("bogs", e_km, n_km)
        out = np.zeros((swd.CELL, swd.CELL), dtype="u1")
        raised = [(g, 1) for g, t in bogs if t in BOG_TYPES]
        margin = [(g, 1) for g, t in bogs if t in BOG_MARGIN_TYPES]
        fens = self.shapes("fens", e_km, n_km)
        auen = self.shapes("auen", e_km, n_km)
        if raised:
            out |= tlm.burn(raised, e_km, n_km, dtype="uint8") * HAB_RAISED_BOG
        if margin:
            out |= tlm.burn(margin, e_km, n_km, dtype="uint8") * HAB_BOG_MARGIN
        if fens:
            out |= tlm.burn(fens, e_km, n_km, dtype="uint8") * HAB_FEN
        if auen:
            out |= tlm.burn(auen, e_km, n_km, dtype="uint8") << HAB_AUE_SHIFT
        return out


def _read_shp(zip_path: Path, member: str) -> tuple[list[tuple[object, dict]], str]:
    with fiona.open(f"zip://{zip_path}!{member}") as src:
        crs = src.crs.to_string() if src.crs else ""
        return [(shape(f.geometry), dict(f.properties)) for f in src], crs


def _open_source(cfg: Config, s: Source) -> list[tuple[list[tuple[object, dict]], str]]:
    path = fetch(cfg, s.href)
    names = set(zipfile.ZipFile(path).namelist())
    missing = [m for m in s.members if m not in names]
    if missing:
        raise ValueError(f"{s.key}: {path.name} has no {missing}; upstream released a new version, update {s.pin}")
    return [_read_shp(path, m) for m in s.members]


def _lv95(geom, crs: str):
    if crs == "EPSG:2056":
        return geom
    if crs == "EPSG:21781":
        return shapely.transform(geom, lambda xy: xy + np.array([2_000_000.0, 1_000_000.0]))
    raise ValueError(f"unexpected CRS {crs}")


def load_vectors(cfg: Config) -> Vectors:
    (bio, crs_b), = _open_source(cfg, BIO)
    (lith, crs_l), = _open_source(cfg, LITHO)
    (bogs, crs_h), = _open_source(cfg, BOGS)
    (fens, crs_f), = _open_source(cfg, FENS)
    auen = _open_source(cfg, AUEN)

    def polys(feats, crs, value):
        out = []
        for g, p in feats:
            v = value(p)
            if v is not None and g is not None and not g.is_empty:
                out.append((_lv95(shapely.force_2d(g), crs), int(v)))
        return out

    return Vectors(
        regions=polys(bio, crs_b, lambda p: regn_code(int(p["RegionNumm"]), int(p["Unterregio"]))),
        lithology=polys(lith, crs_l, lambda p: int(p["LITHOHAUPT"]) if p["LITHOHAUPT"] is not None else None),
        bogs=polys(bogs, crs_h, lambda p: int(p["Typ"])),
        fens=polys(fens, crs_f, lambda p: 1),
        auen=[x for feats, crs in auen for x in polys(feats, crs, lambda p: int(p["Typ"]) if 1 <= int(p["Typ"]) <= 5 else None)],
    )


_lock = threading.Lock()
_vectors: dict[Path, Vectors] = {}


def shared_vectors(cfg: Config) -> Vectors:
    """Loaded once per process (~6 s: the GK500 layer and the two 100 m fill grids)."""
    with _lock:
        if cfg.raw not in _vectors:
            _vectors[cfg.raw] = load_vectors(cfg)
        return _vectors[cfg.raw]


# ---------------------------------------------------------------- TOPO

PX_M = 2.0
R50, R300 = 25, 150  # TPI half-window in pixels (50 m, 300 m)
FLOW_STEPS = 150  # 300 m of flow path
WIND_REACH = 150
MARGIN = 160  # >= max(R300, WIND_REACH, FLOW_STEPS + 1)

TPI_MID, TPI_VALLEY, TPI_FLAT, TPI_RIDGE = range(4)
FLOW_NONE, FLOW_GULLY, FLOW_CHANNEL, FLOW_HOLLOW = range(4)
WIND_NEUTRAL, WIND_LEE, WIND_EXPOSED = range(3)

# Calibrated on eight test-area cells from the Bödeli (31 m relief) to the Aar gorge (740 m): TPI and Sx
# percentiles per cell, chosen so ridge/valley mark real crests and floors, not every bend of a steep slope.
T50_DM = 20  # 2 m
T300_DM = 200  # 20 m
FLAT_SLOPE = 0.087  # tan 5°
GULLY_PX = 250  # 1,000 m² within 300 m of flow path
CHANNEL_PX = 2500  # 1 ha
HOLLOW_PX = 25  # 100 m²
HOLLOW_DESCENT = 0.02  # water hardly drains on: a pit, a flat or a gradient below 2 %
LEE_SX = 0.30  # ~17°: upwind terrain clearly higher (lee slopes, sheltered hollows)
EXPOSED_SX = -0.12  # ~-7°: upwind terrain falls away (crests, windward slopes)

# D8 in a fixed order (ties go to the first): N, E, S, W, NE, SE, SW, NW (row offset south-positive)
D8 = ((-1, 0), (0, 1), (1, 0), (0, -1), (-1, 1), (1, 1), (1, -1), (-1, -1))
D8_DIST = tuple(np.hypot(dr, dc) for dr, dc in D8)
WIND_STEPS = (1, 2, 3, 4, 5, 6, 8, 10, 13, 16, 20, 25, 30, 36, 43, 50, 60, 70, 85, 100, 120, 150)
WIND_DIRS_DEG = (270.0, 292.5, 315.0)  # wind *from*; the upwind side is where it comes from


def _wind_offsets() -> list[list[tuple[int, int, float]]]:
    out = []
    for deg in WIND_DIRS_DEG:
        a = np.radians(deg)
        ue, un = np.sin(a), np.cos(a)  # unit vector towards the wind's origin (E, N)
        k = 1.0 / max(abs(ue), abs(un))  # step so the dominant axis moves one pixel
        offs = []
        for d in WIND_STEPS:
            dc, dr = int(round(d * k * ue)), int(round(-d * k * un))
            offs.append((dr, dc, PX_M * float(np.hypot(dr, dc))))
        out.append(offs)
    return out


WIND_OFFSETS = _wind_offsets()


def _box_sums(a: np.ndarray, r: int, n: int, m: int) -> np.ndarray:
    """Sums of a over the (2r+1)² square around each of the n×n inner pixels (offset m), via an integral image."""
    s = np.zeros((a.shape[0] + 1, a.shape[1] + 1), dtype=np.int64)
    np.cumsum(np.cumsum(a, axis=0, dtype=np.int64), axis=1, out=s[1:, 1:])
    lo, hi = m - r, m + n + r  # inclusive lo .. exclusive hi of the windows' extent
    return (s[lo + 2 * r + 1:hi + 1, lo + 2 * r + 1:hi + 1] - s[lo:hi - 2 * r, lo + 2 * r + 1:hi + 1]
            - s[lo + 2 * r + 1:hi + 1, lo:hi - 2 * r] + s[lo:hi - 2 * r, lo:hi - 2 * r])


def tpi_dm(dm: np.ndarray, valid: np.ndarray, r: int, n: int, m: int) -> np.ndarray:
    """Height minus the mean of the valid pixels in the (2r+1)² window, decimetres, for the inner n×n."""
    s = _box_sums(np.where(valid, dm, 0), r, n, m)
    c = _box_sums(valid.astype(np.int64), r, n, m)
    inner = dm[m:m + n, m:m + n]
    return inner - s / np.maximum(c, 1)


def slope2(dm: np.ndarray, valid: np.ndarray, n: int, m: int) -> np.ndarray:
    """Squared gradient (m/m) by central differences, 0 where a neighbour is missing."""
    sl = slice(m, m + n)
    e, w = dm[sl, m + 1:m + n + 1], dm[sl, m - 1:m + n - 1]
    s, nn = dm[m + 1:m + n + 1, sl], dm[m - 1:m + n - 1, sl]
    ok = valid[sl, m + 1:m + n + 1] & valid[sl, m - 1:m + n - 1] & valid[m + 1:m + n + 1, sl] & valid[m - 1:m + n - 1, sl]
    gx = (e - w) / (2 * PX_M * 10)
    gy = (s - nn) / (2 * PX_M * 10)
    return np.where(ok, gx * gx + gy * gy, 0.0)


def tpi_class(tpi: np.ndarray, t_dm: int, s2: np.ndarray) -> np.ndarray:
    c = np.full(tpi.shape, TPI_MID, dtype="u1")
    c[(np.abs(tpi) <= t_dm) & (s2 < FLAT_SLOPE * FLAT_SLOPE)] = TPI_FLAT
    c[tpi < -t_dm] = TPI_VALLEY
    c[tpi > t_dm] = TPI_RIDGE
    return c


def flow_accumulation(dm: np.ndarray, valid: np.ndarray, steps: int = FLOW_STEPS) -> tuple[np.ndarray, np.ndarray]:
    """Fixed-reach D8 accumulation (pixels) over the whole array, exact for pixels at least steps + 1 from the
    array edge, and the steepest-descent gradient (m/m, 0 at pits and flats)."""
    h, w = dm.shape
    big = np.iinfo(np.int64).max // 4
    hp = np.full((h + 2, w + 2), big, dtype=np.int64)
    hp[1:-1, 1:-1] = np.where(valid, dm, big)
    best = np.zeros((h, w))
    recv = np.full((h, w), -1, dtype=np.int64)
    for k, (dr, dc) in enumerate(D8):
        nb = hp[1 + dr:h + 1 + dr, 1 + dc:w + 1 + dc]
        drop = np.where(nb < big, hp[1:-1, 1:-1] - nb, 0)
        s = drop / D8_DIST[k]
        better = s > best
        best = np.where(better, s, best)
        recv = np.where(better, k, recv)
    recv[~valid] = -1
    idx = np.arange(h * w, dtype=np.int64).reshape(h, w)
    off = np.array([dr * w + dc for dr, dc in D8], dtype=np.int64)
    has = recv >= 0
    src = idx[has]
    dst = src + off[recv[has]]
    base = valid.ravel().astype(np.float64)
    a = base.copy()
    for _ in range(steps):
        a = base + np.bincount(dst, weights=a[src], minlength=h * w)
    return a.reshape(h, w), best / (PX_M * 10)


def wind_sx(hm: np.ndarray, n: int, m: int) -> np.ndarray:
    """Mean over WIND_DIRS of the max upwind slope (dh/dist) within WIND_REACH; NaN pixels are skipped."""
    inner = hm[m:m + n, m:m + n]
    total = np.zeros((n, n))
    for offs in WIND_OFFSETS:
        best = np.full((n, n), -np.inf)
        for dr, dc, dist in offs:
            nb = hm[m + dr:m + dr + n, m + dc:m + dc + n]
            best = np.fmax(best, (nb - inner) / dist)
        total += np.where(np.isfinite(best), best, 0.0)
    return total / len(WIND_OFFSETS)


def topo_pixels(hm: np.ndarray, m: int = MARGIN) -> np.ndarray:
    """TOPO bytes for the inner pixels of a (n+2m)² height mosaic in metres (NaN = no data)."""
    n = hm.shape[0] - 2 * m
    valid = np.isfinite(hm)
    dm = np.where(valid, np.rint(np.where(valid, hm, 0.0) * 10), 0).astype(np.int64)
    inner_valid = valid[m:m + n, m:m + n]
    s2 = slope2(dm, valid, n, m)
    c50 = tpi_class(tpi_dm(dm, valid, R50, n, m), T50_DM, s2)
    c300 = tpi_class(tpi_dm(dm, valid, R300, n, m), T300_DM, s2)
    k = FLOW_STEPS + 1
    acc, descent = flow_accumulation(dm[m - k:m + n + k, m - k:m + n + k], valid[m - k:m + n + k, m - k:m + n + k])
    acc, descent = acc[k:k + n, k:k + n], descent[k:k + n, k:k + n]
    flow = np.where(acc >= CHANNEL_PX, FLOW_CHANNEL, np.where(acc >= GULLY_PX, FLOW_GULLY, FLOW_NONE)).astype("u1")
    hollow = (c50 == TPI_VALLEY) & (descent < HOLLOW_DESCENT) & (acc >= HOLLOW_PX)
    flow[hollow] = FLOW_HOLLOW
    sx = wind_sx(np.where(valid, dm / 10.0, np.nan), n, m)
    wind = np.where(sx > LEE_SX, WIND_LEE, np.where(sx < EXPOSED_SX, WIND_EXPOSED, WIND_NEUTRAL)).astype("u1")
    out = c50 | c300 << 2 | flow << 4 | wind << 6
    out[~inner_valid] = 0
    return out.astype("u1")


def mosaic(tiles: dict[tuple[int, int], np.ndarray | None], m: int = MARGIN) -> np.ndarray:
    """(500+2m)² heights: the own tile (0, 0) and m pixels of each neighbour (de, dn); None/missing = NaN."""
    size = TILE_PX + 2 * m
    out = np.full((size, size), np.nan)
    for (de, dn), px in tiles.items():
        if px is None:
            continue
        # tile (de, dn) occupies mosaic rows/cols starting at m - dn·500 / m + de·500
        r0, c0 = m - dn * TILE_PX, m + de * TILE_PX
        rs, cs = max(r0, 0), max(c0, 0)
        re, ce = min(r0 + TILE_PX, size), min(c0 + TILE_PX, size)
        if rs < re and cs < ce:
            out[rs:re, cs:ce] = px[rs - r0:re - r0, cs - c0:ce - c0]
    return out


def topo_columns(px: np.ndarray, wknd: np.ndarray | None) -> np.ndarray:
    cols = np.repeat(np.repeat(px, 2, axis=0), 2, axis=1)
    if wknd is not None:
        cols = np.where(wknd != 0, cols & 0xCF, cols)
    return cols.astype("u1")


class ZonesBuilder:
    """LayerBuilder for REGN, LITH, TOPO and HABT; runs after land cover (TOPO reads WKND).
    `vectors` (a Vectors) and `tile_for` (ALTI item -> 500×500 metres) are injectable for tests."""

    name = "zones"
    tags = ("REGN", "LITH", "TOPO", "HABT")

    def __init__(self, vectors: Vectors | None = None, tile_for=None):
        self._vectors, self._tile_for = vectors, tile_for

    def tile_items(self, e_km: int, n_km: int, sources: dict) -> dict[tuple[int, int], str | None] | None:
        own = sources.get("alti")
        if not own:
            return None
        items: dict[tuple[int, int], str | None] = {(0, 0): own}
        for k, v in (sources.get("altiNeighbours") or {}).items():
            e, n = map(int, k.split("-"))
            items[(e - e_km, n - n_km)] = v
        return items

    def build(self, e_km, n_km, cfg, partial, pinned):
        from .build import LayerResult  # build.py imports this module to register the builder

        check_pinned(pinned)
        vec = self._vectors or shared_vectors(cfg)
        layers = {"REGN": vec.regn(e_km, n_km), "LITH": vec.lith(e_km, n_km), "HABT": vec.hab(e_km, n_km)}
        items = self.tile_items(e_km, n_km, {**(pinned or {}), **partial.sources})
        if items is not None:
            read = self._tile_for or (lambda item: read_tile(fetch(cfg, stac.alti_href(item))))
            hm = mosaic({d: (read(it) if it else None) for d, it in items.items()})
            layers["TOPO"] = topo_columns(topo_pixels(hm), partial.layers.get("WKND"))
        return LayerResult(layers=layers, sources={s.key: s.pin for s in SOURCES})
