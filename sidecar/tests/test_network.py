"""Acceptance checks against real swisstopo data. Downloads into $SWISSDATA_RAW; the store is a tmp dir.

    uv run --group dev pytest -q -m network
"""

import math
import random

import numpy as np
import pytest
import rasterio

from swissdata import alti, coverage, stac, swd
from swissdata.config import Config
from swissdata.fetch import fetch

pytestmark = [pytest.mark.network, pytest.mark.slow]


@pytest.fixture
def cfg(tmp_path):
    env = Config.from_env()
    return Config(raw=env.raw, store=tmp_path / "store", scratch=tmp_path / "scratch")


def pixel(cfg, e, n):
    """Independent check: the COG value at LV95 point (e, n), read from whichever tile holds it."""
    item = stac.AltiIndex().newest(math.floor(e / 1000), math.floor(n / 1000))
    with rasterio.open(fetch(cfg, stac.alti_href(item))) as ds:
        r, c = ds.index(e, n)
        return float(ds.read(1, window=((r, r + 1), (c, c + 1)))[0, 0])


def test_terr_matches_tile_at_random_nodes(cfg):
    e_km, n_km = 2631, 1170
    r = alti.AltiTerrain().build(e_km, n_km, cfg, None, None)
    h = swd.decode_height(r.layers["TERR"])
    assert r.sources["alti"].startswith("swissalti3d_") and r.sources["alti"].endswith(f"_{e_km}-{n_km}")
    rng = random.Random(1170)
    nodes = [(rng.randrange(swd.TERR_N), rng.randrange(swd.TERR_N)) for _ in range(16)]
    nodes += [(0, 0), (500, 500), (0, 250), (250, 500)]  # corners and edges need neighbour tiles
    for i, j in nodes:
        e, n = e_km * 1000 + 2 * i, n_km * 1000 + 1000 - 2 * j
        expected = np.mean([pixel(cfg, e + de, n + dn) for de in (-1, 1) for dn in (-1, 1)])
        assert abs(h[j, i] - expected) <= 0.1, (i, j, h[j, i], expected)


def test_border_states(cfg):
    grid = coverage.ensure_coverage(cfg)
    assert coverage.state(grid, 2490, 1300) == coverage.OUTSIDE
    assert coverage.state(grid, 2631, 1170) == coverage.INSIDE  # Interlaken
    assert coverage.state(grid, 2632, 1170) == coverage.INSIDE
    assert coverage.state(grid, 2610, 1269) == coverage.BORDER  # Basel / Allschwil, on the French border
    insd = coverage.BorderMask().build(2610, 1269, cfg, None, None).layers["INSD"]
    assert 0 < insd.mean() < 1
