"""REGN / LITH / TOPO / HABT (swissdata.zones) on synthetic polygons and DEMs."""

import numpy as np
import pytest
import shapely

from swissdata import build, swd, zones
from swissdata.alti import TILE_PX
from swissdata.config import Config

E0, N0 = 2630, 1169  # SW tile of the synthetic 4×3 tile DEM
TILES_E, TILES_N = 4, 3


def synthetic_dem() -> np.ndarray:
    """(3·500)×(4·500) heights in metres, row 0 = north: hills, a N–S valley, a little noise."""
    rows, cols = TILES_N * TILE_PX, TILES_E * TILE_PX
    y, x = np.mgrid[0:rows, 0:cols] * 2.0
    h = 1200 + 90 * np.sin(x / 300) * np.cos(y / 400) + 25 * np.sin(x / 61 + y / 97)
    h += 0.004 * (x - 2600) ** 2 / 10  # a broad valley around x = 2.6 km
    return h + np.random.default_rng(7).normal(0, 0.2, h.shape)


DEM = synthetic_dem()


def tile(e: int, n: int) -> np.ndarray | None:
    if not (E0 <= e < E0 + TILES_E and N0 <= n < N0 + TILES_N):
        return None
    r, c = (N0 + TILES_N - 1 - n) * TILE_PX, (e - E0) * TILE_PX
    return DEM[r:r + TILE_PX, c:c + TILE_PX]


def sources(e: int, n: int) -> dict:
    def item(e_, n_):
        return f"t_{e_}-{n_}" if tile(e_, n_) is not None else None
    return {"alti": item(e, n), "altiNeighbours": {f"{e + de}-{n + dn}": item(e + de, n + dn)
                                                    for de in (-1, 0, 1) for dn in (-1, 0, 1) if de or dn}}


def tile_for(item: str) -> np.ndarray:
    e, n = map(int, item[2:].split("-"))
    return tile(e, n)


@pytest.fixture(scope="module")
def vectors():
    """One region over the west half of 2631_1170, limestone with a glacier in it, a bog, a fen and an Aue."""
    e0, n0 = 2631_000, 1170_000
    regions = [(shapely.box(e0, n0, e0 + 500, n0 + 1000), zones.regn_code(4, 41))]
    lith = [(shapely.box(e0 - 5000, n0 - 5000, e0 + 5000, n0 + 5000), 40),
            (shapely.box(e0 + 600, n0 + 600, e0 + 800, n0 + 800), zones.GK500_GLACIER),
            (shapely.box(e0, n0, e0 + 100, n0 + 100), zones.GK500_LAKE)]
    bogs = [(shapely.box(e0 + 100, n0 + 100, e0 + 200, n0 + 200), 1),
            (shapely.box(e0 + 200, n0 + 100, e0 + 300, n0 + 200), 3),
            (shapely.box(e0 + 300, n0 + 100, e0 + 400, n0 + 200), 4)]
    fens = [(shapely.box(e0 + 100, n0 + 300, e0 + 200, n0 + 400), 1)]
    auen = [(shapely.box(e0 + 100, n0 + 500, e0 + 200, n0 + 600), 1)]
    return zones.Vectors(regions, lith, bogs, fens, auen)


def run(vec, e, n, wknd=None):
    partial = build.LayerResult(layers={} if wknd is None else {"WKND": wknd}, sources=sources(e, n))
    cfg = Config(raw=None, store=None, scratch=None)
    return zones.ZonesBuilder(vectors=vec, tile_for=tile_for).build(e, n, cfg, partial, None)


def col(dx: int, dz: int) -> tuple[int, int]:
    return dz, dx  # arrays are [dz, dx]


# ---------------------------------------------------------------- REGN / LITH / HABT

def test_lith_lut_classes_fit_three_bits():
    lut = zones.lith_lut()
    for code, (cls, detail) in zones.LITHOHAUPT.items():
        assert 1 <= cls <= 5 and 0 <= detail <= 7
        assert lut[code] & 7 == cls and lut[code] >> 5 == detail
    assert lut[zones.GK500_LAKE] == zones.QUATERNARY | zones.LITH_LAKE
    assert lut[zones.GK500_GLACIER] == 0
    assert zones.LITHOHAUPT[24] == (zones.MOLASSE, 2)  # Nagelfluh
    assert zones.LITHOHAUPT[60][0] == zones.CRYSTALLINE and zones.LITHOHAUPT[32][0] == zones.SCHIST


def test_region_lithology_and_habitats(vectors):
    r = run(vectors, 2631, 1170)
    regn, lith, hab = r.layers["REGN"], r.layers["LITH"], r.layers["HABT"]
    inside = zones.regn_code(4, 41)
    assert regn[col(10, 500)] == inside
    assert regn[col(990, 500)] == inside | zones.REGN_FILLED  # east half: nearest region
    assert lith[col(700, 100)] == zones.LIMESTONE  # Kalkstein, detail 0
    assert lith[col(650, 350)] == zones.LIMESTONE | zones.LITH_GLACIER  # glacier at N 1170.65 km: rock below
    assert lith[col(50, 950)] == zones.QUATERNARY | zones.LITH_LAKE
    assert hab[col(150, 850)] == zones.HAB_RAISED_BOG
    assert hab[col(250, 850)] == zones.HAB_BOG_MARGIN
    assert hab[col(350, 850)] == 0  # Hochmoor Typ 4: open water, left to WKND
    assert hab[col(150, 650)] == zones.HAB_FEN
    assert hab[col(150, 450)] >> zones.HAB_AUE_SHIFT == 1  # glacier forefield
    assert r.sources == {s.key: s.pin for s in zones.SOURCES}


def test_fill_is_the_same_for_every_cell(vectors):
    """Columns outside every region polygon take the nearest region from one national grid, so the east
    neighbour sees the same value along the shared edge."""
    a = run(vectors, 2631, 1170).layers["REGN"]
    b = run(vectors, 2632, 1170).layers["REGN"]
    assert (b != 0).all()
    assert (a[:, -1] == b[:, 0]).all()


def test_check_pinned():
    zones.check_pinned(None)
    zones.check_pinned({s.key: s.pin for s in zones.SOURCES})
    with pytest.raises(ValueError):
        zones.check_pinned({"lithology": "geologie-geotechnik-gk500-lithologie_hauptgruppen@2030-01-01"})


# ---------------------------------------------------------------- TOPO

def test_topo_agrees_across_a_seam(vectors):
    """Cells A and B are built from different 3×3 tile sets; a block centred on their seam, computed straight from
    the DEM, must equal A's east half next to B's west half byte for byte."""
    a = run(vectors, 2631, 1170).layers["TOPO"]
    b = run(vectors, 2632, 1170).layers["TOPO"]
    m, half = zones.MARGIN, TILE_PX // 2
    r0 = (N0 + TILES_N - 1 - 1170) * TILE_PX  # global row of the cells' north edge
    c0 = (2631 - E0) * TILE_PX + half  # seam block starts mid-A
    block = zones.topo_pixels(DEM[r0 - m:r0 + TILE_PX + m, c0 - m:c0 + TILE_PX + m])
    seam = zones.topo_columns(block, None)
    assert np.array_equal(seam[:, :swd.CELL // 2], a[:, swd.CELL // 2:])
    assert np.array_equal(seam[:, swd.CELL // 2:], b[:, :swd.CELL // 2])


def test_topo_is_independent_of_the_crop():
    m = zones.MARGIN
    r0, c0 = TILE_PX, TILE_PX
    small = zones.topo_pixels(DEM[r0 - m:r0 + 200 + m, c0 - m:c0 + 200 + m])
    wide = zones.topo_pixels(DEM[r0 - m - 40:r0 + 200 + m + 40, c0 - m - 40:c0 + 200 + m + 40], m + 40)
    assert np.array_equal(small, wide)


def test_topo_classes_on_a_valley():
    """A V-shaped N–S valley floor at x = 520 m with long planar flanks, tilted 5 % to the south."""
    n, m = 500, zones.MARGIN
    y, x = np.mgrid[0:n + 2 * m, 0:n + 2 * m] * 2.0
    h = 1000 + 0.6 * np.abs(x - 520) - 0.05 * y
    t = zones.topo_pixels(h)
    tpi300, flow, wind = t >> 2 & 3, t >> 4 & 3, t >> 6 & 3
    floor = 520 // 2 - m  # inner column of the floor
    assert tpi300[250, floor] == zones.TPI_VALLEY
    assert flow[400, floor] == zones.FLOW_CHANNEL  # the thalweg collects both flanks
    east = floor + 250  # 500 m east: planar west-facing flank
    assert tpi300[250, east] == zones.TPI_MID and t[250, east] & 3 == zones.TPI_MID
    assert flow[250, east] == zones.FLOW_NONE
    assert wind[250, east] == zones.WIND_EXPOSED  # upwind (west) terrain falls away
    assert wind[250, floor - 50] == zones.WIND_LEE  # east-facing flank: upwind terrain rises


def test_water_columns_have_no_flow():
    px = np.full((TILE_PX, TILE_PX), zones.FLOW_CHANNEL << 4 | zones.TPI_RIDGE, dtype="u1")
    wknd = np.zeros((swd.CELL, swd.CELL), dtype="u1")
    wknd[:10] = 1
    cols = zones.topo_columns(px, wknd)
    assert (cols[:10] == zones.TPI_RIDGE).all()
    assert (cols[10:] == px[0, 0]).all()


def test_missing_alti_pin_skips_topo(vectors):
    partial = build.LayerResult(sources={})
    r = zones.ZonesBuilder(vectors=vectors, tile_for=tile_for).build(2631, 1170, None, partial, None)
    assert "TOPO" not in r.layers and "REGN" in r.layers


# ---------------------------------------------------------------- contract

def test_zone_layers_round_trip():
    rng = np.random.default_rng(1)
    layers = {"TERR": np.zeros((swd.TERR_N, swd.TERR_N), dtype="<u2"), "SURF": np.zeros((swd.CELL, swd.CELL), "u1")}
    for tag in ("REGN", "LITH", "TOPO", "HABT"):
        layers[tag] = rng.integers(0, 256, (swd.CELL, swd.CELL), dtype="u1")
    data = swd.encode(swd.Cell(2631, 1170, layers=layers))
    back = swd.decode(data)
    for tag in ("REGN", "LITH", "TOPO", "HABT"):
        assert np.array_equal(back.layers[tag], layers[tag])
    assert swd.encode(back) == data


def test_registered_after_landcover():
    names = [b.name for b in build.BUILDERS]
    assert names.index("tlm-landcover") < names.index("zones") < names.index("default-surface")


def test_read_alti_deletes_only_what_it_downloaded(tmp_path, monkeypatch):
    cfg = Config(raw=tmp_path, store=None, scratch=None, keep_raw=False)
    kept = zones.cache_path(cfg, zones.stac.alti_href("swissalti3d_2025_2632-1170"))
    kept.parent.mkdir(parents=True)
    kept.write_bytes(b"x")

    def fake_fetch(cfg_, url):
        p = zones.cache_path(cfg_, url)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"x")
        return p

    monkeypatch.setattr(zones, "fetch", fake_fetch)
    monkeypatch.setattr(zones, "read_tile", lambda p: np.zeros((TILE_PX, TILE_PX)))
    zones.read_alti(cfg, "swissalti3d_2025_2632-1170")
    zones.read_alti(cfg, "swissalti3d_2025_2633-1170")
    assert kept.exists()
    assert not zones.cache_path(cfg, zones.stac.alti_href("swissalti3d_2025_2633-1170")).exists()
