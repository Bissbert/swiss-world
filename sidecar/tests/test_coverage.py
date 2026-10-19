import numpy as np
import shapely

from swissdata import coverage, swd
from swissdata.config import Config


def test_classify_and_insd_on_synthetic_country():
    e0, n0 = coverage.E_MIN * 1000, coverage.N_MIN * 1000
    # 3 km × 2 km block on the grid lines, plus a half-cell sliver to the east
    ch = shapely.union(shapely.box(e0 + 10_000, n0 + 10_000, e0 + 13_000, n0 + 12_000),
                       shapely.box(e0 + 13_000, n0 + 10_000, e0 + 13_500, n0 + 11_000))
    grid = coverage.classify(ch)
    E, N = coverage.E_MIN, coverage.N_MIN
    assert coverage.state(grid, E + 10, N + 10) == coverage.INSIDE
    assert coverage.state(grid, E + 12, N + 11) == coverage.INSIDE
    assert coverage.state(grid, E + 13, N + 10) == coverage.BORDER
    assert coverage.state(grid, E + 13, N + 11) == coverage.OUTSIDE  # touches only along an edge/corner
    assert coverage.state(grid, E + 9, N + 10) == coverage.OUTSIDE
    assert coverage.state(grid, E - 1, N) == coverage.OUTSIDE  # off the grid
    assert coverage.state(grid, E + coverage.WIDTH, N) == coverage.OUTSIDE
    assert int((grid > 0).sum()) == 7

    m = coverage.insd_mask(ch, E + 13, N + 10)
    assert m.shape == (swd.CELL, swd.CELL)
    assert m[:, :500].all() and not m[:, 500:].any()


def test_border_mask_only_on_border_cells(tmp_path, monkeypatch):
    E, N = coverage.E_MIN, coverage.N_MIN
    grid = np.zeros((coverage.HEIGHT, coverage.WIDTH), dtype="u1")
    grid[10, 10], grid[10, 11] = coverage.INSIDE, coverage.BORDER
    ch = shapely.box((E + 10) * 1000, (N + 10) * 1000, (E + 11) * 1000 + 250, (N + 11) * 1000)
    monkeypatch.setattr(coverage, "load_switzerland", lambda cfg, item: ch)
    cfg = Config(raw=tmp_path, store=tmp_path, scratch=tmp_path)
    b = coverage.BorderMask(grid)
    assert b.build(E + 10, N + 10, cfg, None, None).layers == {}
    r = b.build(E + 11, N + 10, cfg, None, {"boundaries": "swissboundaries3d_test"})
    assert r.sources == {"boundaries": "swissboundaries3d_test"}
    assert r.layers["INSD"][:, :250].all() and not r.layers["INSD"][:, 250:].any()
