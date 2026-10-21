"""GMAT (docs/swd-format.md §6c): fixture crops of real SWISSIMAGE/ALTI tiles, seams, absent photo."""

from pathlib import Path

import numpy as np
import pytest

from swissdata import build, gmat, swd
from swissdata.alti import NEIGHBOURS, TILE_PX
from swissdata.config import Config
from swissdata.manifest import Pins

FIX = Path(__file__).parent / "fixtures" / "gmat"
ROCK = (gmat.ROCK_LIGHT, gmat.ROCK_DARK)
# fixture -> (accepted classes, minimum share of the crop's columns); cut with fixtures/gmat/cut.py, judged by eye
CASES = {
    "meadow": ((gmat.GRASS,), 0.85),  # Faulhorn alp, 2642_1168
    "north_slope_meadow": ((gmat.GRASS,), 0.85),  # Faulhorn, slope away from the sun, 2642_1169
    "forest": ((gmat.TREE,), 0.6),  # Harder, with a forest road and a clearing, 2632_1171
    "rock_face": (ROCK, 0.7),  # Grimsel granite knobs, 2667_1156
    "scree_cone": ((gmat.SCREE,), 0.6),  # Faulhorn scree cone between meadows, 2643_1169
    "snowfield": ((gmat.SNOW,), 0.95),  # Jungfraufirn above the snow line, 2645_1155
    "shadowed_north_cliff": (ROCK, 0.7),  # Faulhorn north face in cast shadow, near black, 2642_1169
}
EDGE = 4  # crop pixels left out at each side (the filters see no photo beyond the crop)


def load(name):
    f = np.load(FIX / f"{name}.npz")
    e, n, r, c = (int(f[k]) for k in "enrc")
    k = f["rgb"].shape[0]
    m = (f["dem_dm"].shape[0] - k) // 2
    hm = np.full((3 * TILE_PX, 3 * TILE_PX), np.nan)
    hm[TILE_PX + r - m:TILE_PX + r + k + m, TILE_PX + c - m:TILE_PX + c + k + m] = f["dem_dm"] / 10.0 + float(f["base"])
    dem = {(de, dn): hm[(1 - dn) * TILE_PX:(2 - dn) * TILE_PX, (1 + de) * TILE_PX:(2 + de) * TILE_PX]
           for de, dn in [(0, 0)] + NEIGHBOURS}
    rgb = np.full((TILE_PX, TILE_PX, 3), np.nan)
    rgb[r:r + k, c:c + k] = f["rgb"]
    layers = {}
    for key in f.files:
        if key.startswith("L_"):
            a = np.zeros((swd.CELL, swd.CELL), dtype=f[key].dtype)
            a[2 * r:2 * (r + k), 2 * c:2 * (c + k)] = f[key]
            layers[key[2:]] = a
    params = {(0, 0): gmat.TileParams(tuple(float(x) for x in f["gains"]), *(float(x) for x in f["sun"]))}
    window = (slice(2 * (r + EDGE), 2 * (r + k - EDGE)), slice(2 * (c + EDGE), 2 * (c + k - EDGE)))
    return e, n, {(0, 0): rgb}, dem, params, layers, window


def classify(name, priors=True):
    e, n, rgb, dem, params, layers, window = load(name)
    pix = gmat.photo_pixels(e, n, rgb, dem, params)
    return (gmat.gmat_columns(pix, layers if priors else {}) & 15)[window]


@pytest.mark.parametrize("name", CASES)
def test_fixture_crops(name):
    classes, share = CASES[name]
    cls = classify(name)
    got = np.isin(cls, classes).mean()
    hist = {int(v): round(float((cls == v).mean()), 2) for v in np.unique(cls)}
    assert got >= share, f"{name}: {got:.2f} of {classes}, classes {hist}"
    assert (cls == gmat.UNKNOWN).mean() < 0.01


def test_snow_needs_the_snow_line():
    """The same bright pixels below the snow line are not snow (summer snow patches), unless TLM says glacier."""
    e, n, rgb, dem, params, layers, window = load("snowfield")
    low = {d: (h - 1500 if h is not None else None) for d, h in dem.items()}
    pix = gmat.photo_pixels(e, n, rgb, low, params)
    no_glacier = {k: v for k, v in layers.items() if k != "SURF"}
    assert not np.any((gmat.gmat_columns(pix, no_glacier) & 15)[window] == gmat.SNOW)
    high = gmat.photo_pixels(e, n, rgb, dem, params)
    assert np.mean((gmat.gmat_columns(high, no_glacier) & 15)[window] == gmat.SNOW) > 0.95


def test_priors_override_the_photo():
    e, n, rgb, dem, params, layers, window = load("meadow")
    pix = gmat.photo_pixels(e, n, rgb, dem, params)
    z = np.zeros((swd.CELL, swd.CELL), np.uint8)
    lay = dict(layers, WKND=z.copy(), CNPY=z.copy(), OCLS=z.copy())
    lay["WKND"][400:410, :] = 1
    lay["CNPY"][420:430, :] = 12
    lay["OCLS"][440:450, :] = 3  # a road
    spans = {(500, 450): [(0, 5)]}
    g = gmat.gmat_columns(pix, lay, spans) & 15
    assert np.all(g[400:410] == gmat.WATER) and np.all(g[420:430] == gmat.TREE) and np.all(g[440:450] == gmat.SEALED)
    assert g[450, 500] == gmat.SEALED
    assert (g >> 4).max() == 0  # class nibble only


def test_brightness_is_shadow_corrected():
    """On uniform ground the corrected brightness depends far less on the terrain's illumination than the photo."""
    for name in ("north_slope_meadow", "scree_cone", "rock_face"):  # sloped, uniform ground
        e, n, rgb, dem, params, layers, window = load(name)
        pix = gmat.photo_pixels(e, n, rgb, dem, params)
        p = params[(0, 0)]
        hm = gmat.pixel_mosaic(dem, gmat.WORK)
        ci = gmat.cos_incidence(*gmat.gradients(hm), p.sun_az, p.sun_el)
        raw = gmat.pixel_mosaic(rgb, gmat.WORK, channels=3).mean(axis=-1)
        ok = np.isfinite(raw) & np.isfinite(ci) & np.isfinite(pix.lcorr)
        before = np.corrcoef(raw[ok], ci[ok])[0, 1]
        after = np.corrcoef(pix.lcorr[ok], ci[ok])[0, 1]
        assert abs(after) < 0.6 * abs(before), (name, before, after)


# --- seams: synthetic tiles, two neighbouring cells computing the same columns ---

E0, N0 = 2640, 1170


def synth(seed=7):
    """3×2 tiles of a DEM with ridges and a cliff, and a photo of grass, rock, scree and snow patches."""
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:2 * TILE_PX, 0:3 * TILE_PX].astype(float)
    h = 2600 + 0.4 * xx + 200 * np.sin(yy / 90) * np.cos(xx / 140) + np.where(xx > 700, 80, 0)
    rgb = np.empty(h.shape + (3,))
    kind = (np.sin(xx / 37) + np.cos(yy / 29) + rng.normal(0, 0.4, h.shape))
    rgb[...] = (90, 130, 60)  # grass
    rgb[kind > 0.6] = (140, 138, 135)  # grey
    rgb[kind > 1.3] = (235, 238, 240)  # snow
    rgb[kind < -0.8] = (150, 120, 90)  # soil
    rgb = np.clip(rgb * (0.6 + 0.4 * np.cos(yy / 50))[..., None] + rng.normal(0, 6, rgb.shape), 0, 255).astype(np.uint8)
    tiles = {}
    for i in range(3):
        for j in range(2):
            sl = (slice((1 - j) * TILE_PX, (2 - j) * TILE_PX), slice(i * TILE_PX, (i + 1) * TILE_PX))
            tiles[(E0 + i, N0 + j)] = (rgb[sl], h[sl])
    return tiles


def cell_gmat(e, n, tiles, drop_rgb=()):
    rgb = {(de, dn): (tiles[(e + de, n + dn)][0] if (e + de, n + dn) in tiles and (e + de, n + dn) not in drop_rgb
                      else None) for de, dn in [(0, 0)] + NEIGHBOURS}
    dem = {(de, dn): (tiles[(e + de, n + dn)][1] if (e + de, n + dn) in tiles else None)
           for de, dn in [(0, 0)] + NEIGHBOURS}
    params = {d: gmat.tile_params(px, dem[d]) for d, px in rgb.items() if px is not None}
    return gmat.photo_pixels(e, n, rgb, dem, params)


def test_neighbouring_cells_agree_at_the_seam():
    tiles = synth()
    a = cell_gmat(E0 + 1, N0, tiles)
    b = cell_gmat(E0 + 1, N0 + 1, tiles)  # north of a
    c = cell_gmat(E0, N0, tiles)  # west of a
    w = gmat.WORK
    # a's work window reaches w pixels into b and c: the photo classes there must be identical
    assert np.array_equal(a.cls[w - 8:w, w:-w], b.cls[w + TILE_PX - 8:w + TILE_PX, w:-w])
    assert np.array_equal(a.cls[w:-w, w - 8:w], c.cls[w:-w, w + TILE_PX - 8:w + TILE_PX])
    sa, sc = gmat.smooth_lcorr(a.lcorr), gmat.smooth_lcorr(c.lcorr)
    assert np.array_equal(sa[w:-w, w - 4:w], sc[w:-w, w + TILE_PX - 4:w + TILE_PX])
    assert len(np.unique(a.cls)) >= 4


def test_absent_photo_gives_no_layer(tmp_path):
    class NoPhoto:
        def newest(self, e, n):
            return None

    cfg = Config(raw=tmp_path, store=tmp_path, scratch=tmp_path)
    b = gmat.GmatBuilder(index=NoPhoto(), pins=Pins(tmp_path), ortho_for=lambda it: pytest.fail("no read"))
    r = b.build(2632, 1170, cfg, build.LayerResult(), None)
    assert r.layers == {} and r.sources["ortho"] is None
    assert any(isinstance(x, gmat.GmatBuilder) for x in build.BUILDERS)
    names = [x.name for x in build.BUILDERS]
    assert names.index("zones") < names.index("canopy") < names.index("gmat") < names.index("default-surface")


def test_builder_uses_pins_and_neighbours(tmp_path):
    tiles = synth()
    item = lambda e, n: f"swissimage-dop10_2024_{e}-{n}"  # noqa: E731
    by_item = {item(e, n): t for (e, n), t in tiles.items()}
    pinned = {"ortho": item(E0 + 1, N0),
              "orthoNeighbours": {f"{E0 + 1 + de}-{N0 + dn}": (item(E0 + 1 + de, N0 + dn)
                                  if (E0 + 1 + de, N0 + dn) in tiles else None) for de, dn in NEIGHBOURS},
              "alti": "A_" + item(E0 + 1, N0),
              "altiNeighbours": {f"{E0 + 1 + de}-{N0 + dn}": ("A_" + item(E0 + 1 + de, N0 + dn)
                                 if (E0 + 1 + de, N0 + dn) in tiles else None) for de, dn in NEIGHBOURS}}
    cfg = Config(raw=tmp_path, store=tmp_path, scratch=tmp_path)
    b = gmat.GmatBuilder(index=None, pins=Pins(tmp_path), ortho_for=lambda it: by_item[it][0],
                         alti_for=lambda it: by_item[it[2:]][1])
    r = b.build(E0 + 1, N0, cfg, build.LayerResult(), pinned)
    g = r.layers["GMAT"]
    assert g.shape == (swd.CELL, swd.CELL) and g.dtype == np.uint8
    assert r.sources["ortho"] == pinned["ortho"] and r.sources["orthoNeighbours"] == pinned["orthoNeighbours"]
    assert np.all((g & 15) <= gmat.SEALED) and np.mean((g & 15) == gmat.UNKNOWN) < 0.01
    assert np.array_equal(g, b.build(E0 + 1, N0, cfg, build.LayerResult(), pinned).layers["GMAT"])  # deterministic


def test_snow_line_matches_the_mod():
    # ClimateLines.snowLine: 2700 m in the north-west, ~3000 m in the south (Valais), lower in the north-east
    assert gmat.snow_line(np.array(2_600_000.0), np.array(1_200_000.0)) == pytest.approx(2700)
    assert gmat.snow_line(np.array(2_600_000.0), np.array(1_100_000.0)) == pytest.approx(3000)
    assert gmat.snow_line(np.array(2_800_000.0), np.array(1_270_000.0)) == pytest.approx(2720)
