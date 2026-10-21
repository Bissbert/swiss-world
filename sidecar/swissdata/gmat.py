"""GMAT: ground material per column from the SWISSIMAGE orthophoto (docs/swd-format.md §6c). Owner: sidecar-ortho.

Source: SWISSIMAGE 10 cm, STAC collection ch.swisstopo.swissimage-dop10, its 2 m RGB COG per 1 km tile
(~170 KB, JPEG-in-TIFF), © swisstopo, "Open use. Must provide the source". The 0.1 m asset of the same item is
~62 MB per tile (2.5 TB for CH); at 2 m all of CH is ~7 GB, and the 2 m grid is the swissALTI3D grid (500×500,
origin at the NW corner, Area), so photo and DEM pixels coincide. Pinned per tile like ALTI ("ortho" in pins.json,
manifest sources `ortho` + `orthoNeighbours`); the newest flight year wins. One 2 m pixel sets its 2×2 columns
(class and tone).

Pipeline (each step is a pure function of the pinned ortho and ALTI tiles at the world position, so two cells
agree on every pixel both compute; per-tile quantities are computed from the whole tile, never from a cell crop):
1. Per tile: white balance from its near-neutral pixels (gains clamped to ±WB_MAX), and the sun position of its
   flight, fitted as the (azimuth, elevation) whose DEM illumination best correlates with the photo's luminance
   (SWISSIMAGE metadata has no flight time). Tiles of different flight years thereby meet without a colour seam.
2. Per pixel: illumination I = AMBIENT + (1 − AMBIENT)·max(cos i, 0)/sin(elev), 0 direct light in cast shadow
   (horizon ray-march towards the tile's sun over the ALTI mosaic). The luminance divided by I is the
   shadow-corrected brightness; shadowed pixels also get a skylight colour correction.
3. Photo class from shadow-invariant chromaticity (excess green, red/blue balance, saturation) plus the corrected
   brightness: green grass, dry grass, bare soil, grey (rock or scree), snow. Snow counts only above the regional
   snow line (ClimateLines.snowLine in the mod, ported verbatim); below it, and in shadows too dark to read, the
   pixel is unknown; below-line snow also drops its brightness (rock under it splits light/dark by LITH, tone 7).
4. Coherence: a pixel with at most one 3×3 neighbour of its class takes the 3×3 majority (1-pixel paths survive),
   then unknown pixels take the most common known class within FILL_R pixels. Integer counts, fixed tie order.
5. Per column (priors, after the filter so the filter never reads a neighbour cell's layers): water (WKND) 9,
   buildings (BSPN), sealed SURF and paved overlays 10, canopy CNPY > 3 m 1, rail ballast 7; grey splits into
   scree (TLM scree/gravel, glacier debris, slopes below ROCK_SLOPE_DEG) and light/dark rock (corrected brightness
   against a threshold per GK500 lithology); TLM glacier is snow/ice; what is still unknown follows SURF.
Tone: the 3×3-pixel mean of the corrected brightness (TONE_R; JPEG noise would otherwise dominate the layer's
size) within the class's nationally fixed range (TONE_RANGE), mapped to 0–15, constant over a pixel's 2×2 columns.
"""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import rasterio

from . import stac, swd, zones
from .alti import NEIGHBOURS, TILE_PX, tile_key
from .config import Config
from .fetch import cache_path, fetch
from .manifest import Pins

log = logging.getLogger(__name__)

ORTHO = "ch.swisstopo.swissimage-dop10"

# GMAT low nibble
UNKNOWN, TREE, GRASS, DRY, SOIL, ROCK_LIGHT, ROCK_DARK, SCREE, SNOW, WATER, SEALED = range(11)
GREY = 15  # internal: rock or scree, split per column

PX_M = 2.0
M_PX = zones.MARGIN  # mosaic margin (pixels) around the own tile, as for TOPO
WORK = 24  # pixels around the own tile that are classified (filter + fill reach, + 1 for the tone)
SHADOW_REACH = (1, 2, 3, 4, 5, 6, 8, 10, 13, 16, 20, 25, 30, 36, 43, 50, 60, 70, 85, 100, 115, 130)
assert SHADOW_REACH[-1] + WORK <= M_PX

WB_MAX = 0.06
WB_MIN_NEUTRAL = 0.02  # share of near-neutral pixels needed to trust the balance
SUN_AZ = tuple(range(100, 261, 10))
SUN_EL = (30, 35, 40, 45, 50, 55, 60, 65)
SUN_DEFAULT = (160.0, 50.0)
SUN_MIN_CORR = 0.15
AMBIENT = 0.40
SKY_GAIN = np.array([1.10, 1.03, 0.88])  # skylight is blue: undo its cast in shadow
DARK_L = 16.0  # raw luminance below which a shadow carries no colour

# photo thresholds (white-balanced 0–255 RGB; chroma = channel / sum)
EXG_GREEN = 0.035  # 2g − r − b
EXG_DRY = -0.005
SAT_GREY = 0.14
RB_DRY = 0.045  # r − b chroma for yellow-brown
SNOW_L, SNOW_SAT = 185.0, 0.12
SOIL_MAX_L = 125.0  # dry grass is lighter than bare soil
FILL_R = 6
TONE_UNKNOWN = 7  # tone where the photo shows no ground (masked snow, no photo)
TONE_R = 1  # tone from the 3×3 pixel (6 m) mean: JPEG noise otherwise dominates the layer's size
ISOLATED = 2  # own-class count in 3×3 (incl. itself) at or below which a pixel is replaced

ROCK_SLOPE_DEG = 38.0
LIGHT_ROCK_L = {zones.LIMESTONE: 115.0, zones.CRYSTALLINE: 150.0, zones.SCHIST: 140.0, zones.MOLASSE: 130.0,
                zones.QUATERNARY: 135.0, zones.UNKNOWN: 135.0}
ICE_L = 150.0  # grey on a TLM glacier brighter than this is ice, darker is debris
TONE_RANGE = {GRASS: (40, 150), DRY: (70, 200), SOIL: (50, 170), ROCK_LIGHT: (100, 230), ROCK_DARK: (30, 170),
              SCREE: (50, 210), SNOW: (150, 255), TREE: (20, 110), WATER: (20, 160), SEALED: (60, 220)}
CANOPY_M = 3
SEALED_OCLS = (1, 2, 3, 4, 10, 12, 14)
BALLAST_OCLS = (8, 9)
# SURF -> GMAT for columns the photo leaves unknown
SURF_FALLBACK = np.array([GRASS, TREE, TREE, TREE, GREY, SCREE, SNOW, WATER, WATER, GRASS, SEALED, GRASS, GRASS,
                          SOIL, GRASS, SCREE, GRASS, GRASS] + [UNKNOWN] * 238, dtype="u1")
SURF_ROCK, SURF_SCREE, SURF_GLACIER, SURF_SEALED, SURF_GRAVEL = 4, 5, 6, 10, 15


def ortho_href(item: str) -> str:
    return f"{stac.ASSETS}/{ORTHO}/{item}/{item}_2_2056.tif"


class OrthoIndex(stac.TileIndex):
    """Newest SWISSIMAGE item (with a 2 m COG) per tile."""

    def __init__(self):
        super().__init__(ORTHO, "swissimage-dop10", "_2_2056.tif")


def read_ortho(path: Path) -> np.ndarray:
    """500×500×3 uint8 RGB (row 0 = north)."""
    with rasterio.open(path) as ds:
        a = ds.read()
    if a.shape != (3, TILE_PX, TILE_PX):
        raise ValueError(f"{path.name}: unexpected shape {a.shape}")
    return np.ascontiguousarray(a.transpose(1, 2, 0))


def snow_line(e: np.ndarray, n: np.ndarray) -> np.ndarray:
    """Regional snow line (m), ClimateLines.snowLine of the mod."""
    def smooth(t):
        t = np.clip(t, 0.0, 1.0)
        return t * t * (3 - 2 * t)
    return (2700 + 300 * smooth((1_195_000 - n) / 80_000.0) + 80 * smooth((e - 2_700_000) / 100_000.0)
            - 60 * smooth((n - 1_200_000) / 60_000.0))


# ---------------------------------------------------------------- per tile

@dataclass(frozen=True)
class TileParams:
    gains: tuple[float, float, float]
    sun_az: float
    sun_el: float


def gradients(h: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """dh/dE and dh/dN by central differences (NaN at the array edge and next to missing pixels)."""
    ge = np.full(h.shape, np.nan)
    gn = np.full(h.shape, np.nan)
    ge[:, 1:-1] = (h[:, 2:] - h[:, :-2]) / (2 * PX_M)
    gn[1:-1, :] = (h[:-2, :] - h[2:, :]) / (2 * PX_M)  # row 0 = north
    return ge, gn


def cos_incidence(ge: np.ndarray, gn: np.ndarray, az_deg, el_deg) -> np.ndarray:
    a, el = np.radians(az_deg), np.radians(el_deg)
    se, sn, sz = np.sin(a) * np.cos(el), np.cos(a) * np.cos(el), np.sin(el)
    return (-ge * se - gn * sn + sz) / np.sqrt(ge * ge + gn * gn + 1)


def white_balance(rgb: np.ndarray) -> tuple[float, float, float]:
    """Gains that make the tile's near-neutral (rock, road, gravel) pixels grey on average, clamped to ±WB_MAX."""
    f = rgb.reshape(-1, 3).astype(np.float64)
    mx, mn = f.max(axis=1), f.min(axis=1)
    lum = f.mean(axis=1)
    neutral = (mx - mn < 0.18 * np.maximum(mx, 1)) & (lum > 50) & (lum < 230)
    if neutral.mean() < WB_MIN_NEUTRAL:
        return (1.0, 1.0, 1.0)
    m = f[neutral].mean(axis=0)
    g = np.clip(m.mean() / m, 1 - WB_MAX, 1 + WB_MAX)
    return tuple(float(round(x, 4)) for x in g)


def fit_sun(rgb: np.ndarray, dem: np.ndarray) -> tuple[float, float]:
    """(azimuth, elevation) of the grid point whose illumination best correlates with the tile's luminance."""
    ge, gn = gradients(dem)
    lum = rgb.astype(np.float64).mean(axis=2)
    sub = (slice(1, -1, 2), slice(1, -1, 2))
    ge, gn, lum = ge[sub].ravel(), gn[sub].ravel(), lum[sub].ravel()
    ok = np.isfinite(ge) & np.isfinite(gn) & (lum > 8) & (lum < 240)
    slope = np.hypot(ge, gn)
    ok &= slope > 0.1  # flat ground carries no information about the sun
    if ok.sum() < 2000:
        return SUN_DEFAULT
    ge, gn, lum = ge[ok], gn[ok], lum[ok]
    lz = (lum - lum.mean()) / (lum.std() + 1e-9)
    best, best_c = SUN_DEFAULT, SUN_MIN_CORR
    for az in SUN_AZ:
        for el in SUN_EL:
            ci = np.maximum(cos_incidence(ge, gn, az, el), 0.0)
            c = float(np.mean(lz * (ci - ci.mean())) / (ci.std() + 1e-9))
            if c > best_c + 1e-9:
                best, best_c = (float(az), float(el)), c
    return best


def tile_params(rgb: np.ndarray, dem: np.ndarray | None) -> TileParams:
    az, el = fit_sun(rgb, dem) if dem is not None else SUN_DEFAULT
    return TileParams(white_balance(rgb), az, el)


# ---------------------------------------------------------------- per pixel

def cast_shadow(h: np.ndarray, rows: slice, cols: slice, az_deg: float, el_deg: float) -> np.ndarray:
    """True where terrain towards the sun rises above the sun's elevation (ray-march over SHADOW_REACH)."""
    a = np.radians(az_deg)
    ue, un = np.sin(a), np.cos(a)
    k = 1.0 / max(abs(ue), abs(un))
    tan_el = np.tan(np.radians(el_deg))
    inner = h[rows, cols]
    out = np.zeros(inner.shape, dtype=bool)
    for d in SHADOW_REACH:
        dc, dr = int(round(d * k * ue)), int(round(-d * k * un))
        nb = h[rows.start + dr:rows.stop + dr, cols.start + dc:cols.stop + dc]
        dist = PX_M * float(np.hypot(dr, dc))
        with np.errstate(invalid="ignore"):
            out |= (nb - inner) > dist * tan_el + 1.0
    return out


@dataclass
class Pixels:
    """Photo results for the work window: (500 + 2·WORK)² pixels around the own tile."""
    cls: np.ndarray  # u1 photo class (GRASS, DRY, SOIL, GREY, SNOW or UNKNOWN)
    lcorr: np.ndarray  # shadow-corrected brightness, NaN = no photo
    slope_deg: np.ndarray


def classify_photo(rgb: np.ndarray, lum_corr: np.ndarray, valid: np.ndarray, dark: np.ndarray,
                   above_snow: np.ndarray) -> np.ndarray:
    """Photo class of white-balanced (and in shadow sky-corrected) RGB floats."""
    s = rgb.sum(axis=-1) + 1e-6
    r, g, b = rgb[..., 0] / s, rgb[..., 1] / s, rgb[..., 2] / s
    exg = 2 * g - r - b
    mx, mn = rgb.max(axis=-1), rgb.min(axis=-1)
    sat = (mx - mn) / np.maximum(mx, 1.0)
    cls = np.full(rgb.shape[:2], GREY, dtype="u1")
    warm = (r - b > RB_DRY) & (sat >= SAT_GREY)
    cls[warm] = np.where(lum_corr[warm] > SOIL_MAX_L, DRY, SOIL)
    cls[warm & (exg > EXG_DRY)] = DRY
    cls[exg > EXG_GREEN] = GRASS
    snow = (lum_corr > SNOW_L) & (sat < SNOW_SAT)
    cls[snow] = np.where(above_snow[snow], SNOW, UNKNOWN)
    cls[dark | ~valid] = UNKNOWN
    return cls


def _box_counts(mask: np.ndarray, r: int) -> np.ndarray:
    """Exact count of True in the (2r+1)² window around each pixel (zero padding)."""
    p = np.pad(mask.astype(np.int32), r)
    s = np.zeros((p.shape[0] + 1, p.shape[1] + 1), dtype=np.int64)
    np.cumsum(np.cumsum(p, axis=0, dtype=np.int64), axis=1, out=s[1:, 1:])
    w = 2 * r + 1
    h, wd = mask.shape
    return s[w:w + h, w:w + wd] - s[:h, w:w + wd] - s[w:w + h, :wd] + s[:h, :wd]


PHOTO_CLASSES = (GRASS, DRY, SOIL, GREY, SNOW)  # tie order: first wins


def majority(cls: np.ndarray, r: int, classes=PHOTO_CLASSES) -> tuple[np.ndarray, np.ndarray]:
    """Most frequent class of `classes` in the (2r+1)² window (ties: earlier class) and its count."""
    best = np.full(cls.shape, UNKNOWN, dtype="u1")
    best_n = np.zeros(cls.shape, dtype=np.int64)
    for c in classes:
        n = _box_counts(cls == c, r)
        better = n > best_n
        best[better], best_n[better] = c, n[better]
    return best, best_n


def coherent(cls: np.ndarray) -> np.ndarray:
    """Isolated pixels take the 3×3 majority, then unknown pixels the FILL_R majority. Exact on the inner
    region at least 1 + FILL_R pixels from the array edge."""
    out = cls.copy()
    maj, _ = majority(cls, 1)
    for c in PHOTO_CLASSES:
        m = cls == c
        own = _box_counts(m, 1)
        iso = m & (own <= ISOLATED) & (maj != UNKNOWN)
        out[iso] = maj[iso]
    fill, n = majority(out, FILL_R)
    hole = (out == UNKNOWN) & (n > 0)
    out[hole] = fill[hole]
    return out


def pixel_mosaic(tiles: dict[tuple[int, int], np.ndarray | None], m: int, channels: int = 0) -> np.ndarray:
    """Like zones.mosaic for RGB tiles (channels > 0) or heights; missing tiles are NaN."""
    size = TILE_PX + 2 * m
    out = np.full((size, size, channels) if channels else (size, size), np.nan)
    for (de, dn), px in tiles.items():
        if px is None:
            continue
        r0, c0 = m - dn * TILE_PX, m + de * TILE_PX
        rs, cs = max(r0, 0), max(c0, 0)
        re, ce = min(r0 + TILE_PX, size), min(c0 + TILE_PX, size)
        if rs < re and cs < ce:
            out[rs:re, cs:ce] = px[rs - r0:re - r0, cs - c0:ce - c0]
    return out


def photo_pixels(e_km: int, n_km: int, rgb_tiles: dict, dem_tiles: dict, params: dict) -> Pixels:
    """Photo class, corrected brightness and slope for the work window of cell (e_km, n_km).
    rgb_tiles / dem_tiles: (de, dn) -> 500×500(×3) or None; params: (de, dn) -> TileParams."""
    m, w = M_PX, WORK
    n = TILE_PX + 2 * w
    hm = pixel_mosaic(dem_tiles, m)
    rgb = pixel_mosaic(rgb_tiles, w, channels=3)
    ge, gn = gradients(hm)
    inner = (slice(m - w, m + TILE_PX + w), slice(m - w, m + TILE_PX + w))
    ge, gn, h = ge[inner], gn[inner], hm[inner]
    slope_deg = np.degrees(np.arctan(np.hypot(np.nan_to_num(ge), np.nan_to_num(gn))))

    gains = np.ones((n, n, 3))
    illum = np.ones((n, n))
    shadow = np.zeros((n, n), dtype=bool)
    for (de, dn), p in params.items():
        r0, c0 = w - dn * TILE_PX, w + de * TILE_PX
        rs, cs, re, ce = max(r0, 0), max(c0, 0), min(r0 + TILE_PX, n), min(c0 + TILE_PX, n)
        if rs >= re or cs >= ce:
            continue
        gains[rs:re, cs:ce] = p.gains
        ci = cos_incidence(ge[rs:re, cs:ce], gn[rs:re, cs:ce], p.sun_az, p.sun_el)
        ci = np.where(np.isfinite(ci), ci, np.sin(np.radians(p.sun_el)))
        cast = cast_shadow(hm, slice(m - w + rs, m - w + re), slice(m - w + cs, m - w + ce), p.sun_az, p.sun_el)
        direct = np.where(cast, 0.0, np.maximum(ci, 0.0)) / np.sin(np.radians(p.sun_el))
        illum[rs:re, cs:ce] = AMBIENT + (1 - AMBIENT) * np.minimum(direct, 1.6)
        shadow[rs:re, cs:ce] = cast | (ci <= 0.05)

    valid = np.isfinite(rgb[..., 0])
    raw_lum = np.where(valid, np.nan_to_num(rgb).mean(axis=-1), 0.0)
    bal = np.nan_to_num(rgb) * gains
    bal = np.where(shadow[..., None], bal * SKY_GAIN, bal)
    lcorr = np.where(valid, bal.mean(axis=-1) / illum, np.nan)
    dark = shadow & (raw_lum < DARK_L)
    ee = e_km * 1000 + PX_M * (np.arange(n) - w) + 1
    nn = n_km * 1000 + 999 - PX_M * (np.arange(n) - w)
    above = np.isfinite(h) & (h > snow_line(ee[None, :], nn[:, None]))
    cls = classify_photo(bal, np.nan_to_num(lcorr), valid, dark, above)
    # snow below the snow line (summer patches): its brightness says nothing about the ground beneath
    lcorr = np.where((cls == UNKNOWN) & valid & ~dark, np.nan, lcorr)
    return Pixels(coherent(cls), lcorr, slope_deg)


# ---------------------------------------------------------------- per column

def smooth_lcorr(lcorr: np.ndarray, r: int = TONE_R) -> np.ndarray:
    """NaN-aware (2r+1)² mean of the corrected brightness, on values quantised to 1/16 so the integral image is
    exact integer arithmetic (identical in every cell that computes the pixel)."""
    if r <= 0:
        return lcorr
    v = np.isfinite(lcorr)
    q = np.where(v, np.rint(np.clip(np.nan_to_num(lcorr), 0, 4000) * 16), 0).astype(np.int64)
    p = np.pad(q, r)
    s = np.zeros((p.shape[0] + 1, p.shape[1] + 1), dtype=np.int64)
    np.cumsum(np.cumsum(p, axis=0), axis=1, out=s[1:, 1:])
    w = 2 * r + 1
    h, wd = q.shape
    tot = s[w:w + h, w:w + wd] - s[:h, w:w + wd] - s[w:w + h, :wd] + s[:h, :wd]
    n = _box_counts(v, r)
    return np.where(v & (n > 0), tot / np.maximum(n, 1) / 16.0, np.nan)


def to_columns(px: np.ndarray, w: int = WORK) -> np.ndarray:
    inner = px[w:w + TILE_PX, w:w + TILE_PX]
    return np.repeat(np.repeat(inner, 2, axis=0), 2, axis=1)


def gmat_columns(pix: Pixels, layers: dict[str, np.ndarray], spans: dict | None = None) -> np.ndarray:
    """GMAT bytes for the cell from the coherent photo pixels and the cell's own layers (priors)."""
    cls = to_columns(pix.cls).copy()
    slope = to_columns(pix.slope_deg)
    lum = to_columns(smooth_lcorr(pix.lcorr))  # one tone per 2 m pixel: half the bytes of a bilinear ramp
    cls[(cls == UNKNOWN) & (slope >= ROCK_SLOPE_DEG)] = GREY  # unreadable shadow on a cliff is rock
    surf = layers.get("SURF")
    if surf is not None:
        unk = cls == UNKNOWN
        cls[unk] = SURF_FALLBACK[surf[unk]]
        glacier = surf == SURF_GLACIER
        cls[glacier & (cls != GREY)] = SNOW
        ice = glacier & (cls == GREY) & (np.nan_to_num(lum) > ICE_L)
        cls[ice] = SNOW
        cls[glacier & (cls == GREY)] = SCREE
        cls[np.isin(surf, (SURF_SCREE, SURF_GRAVEL)) & (cls == GREY)] = SCREE
        rock = (surf == SURF_ROCK) & (cls == GREY)
    else:
        rock = np.zeros(cls.shape, dtype=bool)
    grey = cls == GREY
    rock |= grey & (slope >= ROCK_SLOPE_DEG)
    cls[grey & ~rock] = SCREE
    lith = layers.get("LITH")
    thr = np.full(cls.shape, LIGHT_ROCK_L[zones.UNKNOWN])
    if lith is not None:
        lut = np.array([LIGHT_ROCK_L.get(k, LIGHT_ROCK_L[zones.UNKNOWN]) for k in range(8)])
        thr = lut[lith & 7]
    light = np.where(np.isfinite(lum), lum > thr, thr <= LIGHT_ROCK_L[zones.LIMESTONE])  # no brightness: by LITH
    cls[rock] = np.where(light[rock], ROCK_LIGHT, ROCK_DARK)

    if "CNPY" in layers:
        cls[layers["CNPY"] > CANOPY_M] = TREE
    if "OCLS" in layers:
        tunnel = (layers["OFLG"] & swd.OFLG_TUNNEL) != 0 if "OFLG" in layers else np.zeros(cls.shape, bool)
        ocls = np.where(tunnel, 0, layers["OCLS"])
        cls[np.isin(ocls, BALLAST_OCLS)] = SCREE
        cls[np.isin(ocls, SEALED_OCLS)] = SEALED
    if surf is not None:
        cls[surf == SURF_SEALED] = SEALED
    if spans:
        k = np.array([key for key in spans if 0 <= key[0] < swd.CELL and 0 <= key[1] < swd.CELL], dtype=np.int64)
        if len(k):
            cls[k[:, 1], k[:, 0]] = SEALED
    if "WKND" in layers:
        cls[layers["WKND"] != 0] = WATER
    cls[cls == GREY] = UNKNOWN  # cannot happen; keeps the nibble in range

    tone = np.zeros(cls.shape, dtype="u1")
    tone[:] = TONE_UNKNOWN
    lumf = np.nan_to_num(lum, nan=-1.0)
    for c, (lo, hi) in TONE_RANGE.items():
        m = (cls == c) & (lumf >= 0)
        tone[m] = np.clip(np.rint((lumf[m] - lo) / (hi - lo) * 15), 0, 15).astype("u1")
    return (cls | tone << 4).astype("u1")


# ---------------------------------------------------------------- builder

_lock = threading.Lock()
_shared: dict[Path, tuple[OrthoIndex, Pins]] = {}
_params: dict[str, TileParams] = {}


def _services(cfg: Config) -> tuple[OrthoIndex, Pins]:
    with _lock:
        if cfg.store not in _shared:
            _shared[cfg.store] = (OrthoIndex(), Pins(cfg.store))
        return _shared[cfg.store]


def read_ortho_item(cfg: Config, item: str) -> np.ndarray:
    """RGB of an ortho item. The 2 m COGs are always kept (~160 KB/km², ~6.6 GB for CH), even with keepRaw off:
    each tile is read by the 9 cells around it, and deleting it after use would refetch it up to 9 times and race
    with parallel workers reading it."""
    return read_ortho(fetch(cfg, ortho_href(item)))


class GmatBuilder:
    """LayerBuilder for GMAT; runs after zones and canopy (it reads SURF, LITH, WKND, OCLS, CNPY and BSPN).
    `index`, `pins`, `ortho_for` (item -> RGB) and `alti_for` (item -> heights) are injectable for tests."""

    name = "gmat"
    tags = ("GMAT",)

    def __init__(self, index=None, pins: Pins | None = None, ortho_for=None, alti_for=None):
        self._index, self._pins, self._ortho_for, self._alti_for = index, pins, ortho_for, alti_for

    def ortho_items(self, e_km: int, n_km: int, cfg: Config, pinned: dict | None) -> dict[tuple[int, int], str | None]:
        if pinned and "ortho" in pinned:
            items = {(0, 0): pinned["ortho"]}
            for k, v in (pinned.get("orthoNeighbours") or {}).items():
                e, n = map(int, k.split("-"))
                items[(e - e_km, n - n_km)] = v
            return items
        index, pins = _services(cfg) if (self._index is None or self._pins is None) else (None, None)
        index, pins = self._index or index, self._pins or pins
        out = {}
        for de, dn in [(0, 0)] + NEIGHBOURS:
            e, n = e_km + de, n_km + dn
            if cfg.dev_bbox and not stac.in_dev_area(e, n):
                out[(de, dn)] = None
                continue
            out[(de, dn)] = pins.pin("ortho", tile_key(e, n), lambda e=e, n=n: index.newest(e, n))
        return out

    def build(self, e_km, n_km, cfg, partial, pinned):
        from .build import LayerResult  # build.py imports this module to register the builder

        items = self.ortho_items(e_km, n_km, cfg, pinned)
        sources = {"ortho": items[(0, 0)],
                   "orthoNeighbours": {tile_key(e_km + de, n_km + dn): items.get((de, dn)) for de, dn in NEIGHBOURS}}
        if items[(0, 0)] is None:
            return LayerResult(sources=sources)  # no photo: no GMAT (absent reads as 0)
        alti = zones.ZonesBuilder().tile_items(e_km, n_km, {**(pinned or {}), **partial.sources}) or {}
        read_rgb = self._ortho_for or (lambda it: read_ortho_item(cfg, it))
        read_dem = self._alti_for or (lambda it: zones.read_alti(cfg, it))
        rgb = {d: (read_rgb(it) if it else None) for d, it in items.items()}
        dem = {d: (read_dem(it) if it else None) for d, it in alti.items()}
        params = {d: tile_params(px, dem.get(d)) for d, px in rgb.items() if px is not None}
        pix = photo_pixels(e_km, n_km, rgb, dem, params)
        return LayerResult(layers={"GMAT": gmat_columns(pix, partial.layers, partial.spans)}, sources=sources)
