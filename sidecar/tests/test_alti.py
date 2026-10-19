import numpy as np
import pytest
import rasterio
from rasterio.transform import from_origin

from swissdata import alti, swd
from swissdata.config import Config
from swissdata.manifest import Pins

NODATA = -9999.0


def plane(e, n):
    return 600.0 + 0.013 * (e - 2_630_000) - 0.021 * (n - 1_170_000)


def write_tile(path, e_km, n_km, holes=()):
    c = np.arange(500)
    e = e_km * 1000 + 2 * c[None, :] + 1.0  # pixel centres
    n = n_km * 1000 + 999 - 2 * c[:, None]
    a = plane(e, n).astype("float32")
    for r, col in holes:
        a[r, col] = NODATA
    with rasterio.open(path, "w", driver="GTiff", width=500, height=500, count=1, dtype="float32",
                       crs="EPSG:2056", transform=from_origin(e_km * 1000, n_km * 1000 + 1000, 2, 2),
                       nodata=NODATA) as ds:
        ds.write(a, 1)


class FakeIndex:
    def __init__(self, tiles):
        self.tiles = tiles

    def newest(self, e, n):
        return f"swissalti3d_2025_{e}-{n}" if (e, n) in self.tiles else None


@pytest.fixture
def world(tmp_path):
    tiles = {(e, n) for e in range(2630, 2634) for n in range(1169, 1172)}
    paths = {}
    for e, n in tiles:
        item = f"swissalti3d_2025_{e}-{n}"
        paths[item] = tmp_path / f"{item}.tif"
        write_tile(paths[item], e, n, holes=[(10, 10), (11, 10), (10, 11)] if (e, n) == (2631, 1170) else ())
    cfg = Config(raw=tmp_path / "raw", store=tmp_path / "store", scratch=tmp_path / "scratch", dev_bbox=False)
    builder = alti.AltiTerrain(index=FakeIndex(tiles), pins=Pins(cfg.store),
                               tiles=alti.TileSource(cfg, path_for=lambda item: paths[item]))
    return cfg, builder, paths


def terr(builder, cfg, e, n, pinned=None):
    r = builder.build(e, n, cfg, None, pinned)
    return swd.decode_height(r.layers["TERR"]), r.sources


def test_nodes_are_bilinear_at_pixel_corners(world):
    cfg, builder, _ = world
    h, _ = terr(builder, cfg, 2631, 1170)
    i = np.arange(501)
    expected = plane(2631_000 + 2 * i[None, :], 1171_000 - 2 * i[:, None])
    assert h.shape == (501, 501)
    assert np.abs(h - expected).max() <= 0.05 + 1e-9  # dm rounding only; holes filled from a plane


def test_shared_edges_identical(world):
    cfg, builder, _ = world
    a, _ = terr(builder, cfg, 2631, 1170)
    east, _ = terr(builder, cfg, 2632, 1170)
    south, _ = terr(builder, cfg, 2631, 1169)
    assert np.array_equal(a[:, 500], east[:, 0])
    assert np.array_equal(a[500, :], south[0, :])


def test_missing_neighbour_uses_available_pixels_and_stays_seamless(world):
    cfg, builder, _ = world
    edge, src = terr(builder, cfg, 2633, 1170)  # no tile east of 2633
    west, _ = terr(builder, cfg, 2632, 1170)
    assert src["altiNeighbours"]["2634-1170"] is None
    assert not np.isnan(edge).any()
    assert np.array_equal(edge[:, 0], west[:, 500])


def test_sources_and_pinned_rebuild_identical(world):
    cfg, builder, _ = world
    h1, src = terr(builder, cfg, 2631, 1170)
    assert src["alti"] == "swissalti3d_2025_2631-1170"
    assert src["altiNeighbours"]["2632-1171"] == "swissalti3d_2025_2632-1171"
    h2, src2 = terr(builder, cfg, 2631, 1170, pinned=src)
    assert src2 == src and np.array_equal(h1, h2, equal_nan=True)


def test_tile_pins_are_reused(world, tmp_path):
    cfg, builder, _ = world
    terr(builder, cfg, 2631, 1170)
    known, value = Pins(cfg.store).get("alti", "2632-1170")
    assert known and value == "swissalti3d_2025_2632-1170"


def test_fill_nodata_closes_large_holes():
    a = np.arange(100, dtype=float).reshape(10, 10)
    mask = np.zeros_like(a, dtype=bool)
    mask[2:8, 2:8] = True
    out = alti.fill_nodata(a, mask)
    assert np.isfinite(out).all() and np.array_equal(out[~mask], a[~mask])
    assert out[mask].min() >= a[~mask].min() and out[mask].max() <= a[~mask].max()


def test_concurrent_edge_writes_do_not_collide(world):
    import threading

    cfg, builder, paths = world
    src = builder._tiles
    errors = []

    def run():
        try:
            for _ in range(5):
                src._save_edges("swissalti3d_2025_2631-1170", np.zeros((500, 500)))
        except Exception as ex:  # noqa: BLE001
            errors.append(ex)

    threads = [threading.Thread(target=run) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert errors == []
