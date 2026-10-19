import numpy as np
import pytest

from swissdata import build, canopy, swd
from swissdata.config import Config
from swissdata.manifest import Pins

PX = canopy.DSM_PX


def flat_layers(h: float = 600.0) -> dict[str, np.ndarray]:
    return {"TERR": swd.encode_height(np.full((swd.TERR_N, swd.TERR_N), h))}


def crowns(shape: tuple[int, int], tops: list[tuple[float, float, float, float]]) -> np.ndarray:
    """CHM (rows, cols of 0.5 m pixels) with paraboloid crowns (x_m, y_m, height, radius); y grows southwards."""
    y = (np.arange(shape[0]) + 0.5)[:, None] * 0.5
    x = (np.arange(shape[1]) + 0.5)[None, :] * 0.5
    chm = np.zeros(shape)
    for cx, cy, h, r in tops:
        chm = np.maximum(chm, h * (1 - ((x - cx) ** 2 + (y - cy) ** 2) / r ** 2))
    return chm


def tops_global(chm: np.ndarray) -> list[tuple[float, float]]:
    """Tops of a multi-tile CHM, each tile detected on its own, in global metres."""
    out = []
    for ty in range(chm.shape[0] // PX):
        for tx in range(chm.shape[1] // PX):
            r, c, _ = canopy.detect_tops(chm[ty * PX:(ty + 1) * PX, tx * PX:(tx + 1) * PX])
            out += [((tx * PX + ci + 0.5) * 0.5, (ty * PX + ri + 0.5) * 0.5) for ri, ci in zip(r, c)]
    return out


def matches(found, tops, x, y, radius):
    return [f for f in found if (f[0] - x) ** 2 + (f[1] - y) ** 2 <= radius ** 2]


def test_ground_is_bilinear_terr_at_pixel_centres():
    i = np.arange(swd.TERR_N)[None, :]
    j = np.arange(swd.TERR_N)[:, None]
    g = canopy.ground_at_pixels(600.0 + 0.2 * i - 0.1 * j + 0 * i * j)
    # pixel (r, c) centre lies at node (c/4 + 1/8, r/4 + 1/8)
    for r, c in [(0, 0), (7, 1999), (1999, 3), (1234, 567)]:
        assert abs(g[r, c] - (600.0 + 0.2 * (c / 4 + 0.125) - 0.1 * (r / 4 + 0.125))) < 1e-9


def test_cnpy_column_geometry_threshold_and_masks():
    dsm = np.full((PX, PX), 600.0)
    dsm[2 * 40 + 1, 2 * 30] = 625.4  # one pixel of column dx 30, dz 40 (dz grows southwards)
    dsm[2 * 40, 2 * 31 + 1] = 601.6  # 1.6 m: below the 2 m threshold
    dsm[2 * 41, 2 * 31] = 602.4  # rounds to 2
    dsm[100:110, 100:110] = 615.0  # columns 50..54 x 50..54, a building at (52, 52)
    dsm[300:310, 300:310] = 610.0  # columns 150..154, water at (152, 152)
    dsm[500:510, 500:510] = 612.0  # columns 250..254, bridge deck at (252, 252)
    dsm[700:702, 700:702] = 670.0  # 70 m: no tree; masked with a 1-column ring
    dsm[703, 700] = 630.0
    dsm[2, 2] = np.nan
    layers = flat_layers()
    wknd = np.zeros((swd.CELL, swd.CELL), dtype="u1")
    wknd[152, 152] = 1
    oflg = np.zeros((swd.CELL, swd.CELL), dtype="u1")
    oflg[252, 252] = swd.OFLG_BRIDGE
    layers |= {"WKND": wknd, "OFLG": oflg}
    spans = {(52, 52): [swd.Span(0, 1, 0)]}
    cnpy, _ = canopy.canopy_and_trees(dsm, layers, spans, 2632, 1170)
    assert cnpy[40, 30] == 25 and cnpy[40, 31] == 0 and cnpy[41, 31] == 2 and cnpy[1, 1] == 0
    assert cnpy[52, 52] == 0 and cnpy[51, 53] == 0 and cnpy[50, 50] == 15  # building + ring, beyond it canopy
    assert cnpy[152, 152] == 0 and cnpy[151, 151] == 10
    assert cnpy[252, 252] == 0 and cnpy[251, 251] == 12
    assert cnpy[350, 350] == 0 and cnpy[351, 350] == 0


def test_tops_heights_kinds_and_overlay_drop():
    chm = crowns((PX, PX), [(100.25, 100.25, 30.0, 5.0), (200.25, 100.25, 20.0, 4.0), (300.25, 100.25, 25.0, 4.0),
                            (400.25, 100.25, 25.0, 4.0)])
    layers = flat_layers()
    ocls = np.zeros((swd.CELL, swd.CELL), dtype="u1")
    oflg = np.zeros((swd.CELL, swd.CELL), dtype="u1")
    ocls[100, 300] = 6  # footpath under the third crown: dropped
    ocls[100, 400] = 3  # road in a tunnel under the fourth: kept
    oflg[100, 400] = swd.OFLG_TUNNEL
    layers |= {"OCLS": ocls, "OFLG": oflg}
    mix = np.full((100, 100), canopy.MIX_NODATA, dtype="i1")
    mix[10, 10] = 0  # pure conifer under the first
    mix[10, 20] = 100  # pure broadleaf under the second
    cnpy, trees = canopy.canopy_and_trees(chm + 600.0, layers, {}, 2632, 1170, mix)
    assert [(t.dx, t.dz) for t in trees] == [(100, 100), (200, 100), (400, 100)]
    assert [t.height_m for t in trees] == [30, 20, 25] == [cnpy[t.dz, t.dx] for t in trees]
    assert [t.kind for t in trees] == [swd.KIND_CONIFER, swd.KIND_BROADLEAF, swd.KIND_UNKNOWN]
    # crown: columns >= half the top's height, i.e. within r/sqrt(2) of the top
    assert abs(trees[0].crown_dm - 5 / np.sqrt(2) * 10) <= 4


def test_canopy_on_rock_only_inside_mapped_forest():
    dsm = 615.0 - 1e-4 * np.arange(PX)[:, None] + np.zeros(PX)  # a slight slope: no plateau of candidates
    layers = flat_layers()
    surf = np.zeros((swd.CELL, swd.CELL), dtype="u1")
    surf[:, :500] = 4
    layers["SURF"] = surf
    mix = np.full((100, 100), canopy.MIX_NODATA, dtype="i1")
    mix[:, :20] = 0  # forest over dx 0..199
    cnpy, _ = canopy.canopy_and_trees(dsm, layers, {}, 2632, 1170, mix)
    assert cnpy[5, 100] == 15 and cnpy[5, 300] == 0 and cnpy[5, 700] == 15
    cnpy, _ = canopy.canopy_and_trees(dsm, layers, {}, 2632, 1170, None)
    assert cnpy[5, 100] == 0 and cnpy[5, 700] == 15


def test_variable_window_merges_close_maxima():
    # two bumps 2 m apart on one 30 m crown (window radius 3 m) are one tree; 8 m apart they are two
    one = canopy.detect_tops(crowns((PX, PX), [(100.25, 100.25, 30.0, 6.0), (102.25, 100.25, 29.8, 6.0)]))
    two = canopy.detect_tops(crowns((PX, PX), [(100.25, 100.25, 30.0, 6.0), (108.25, 100.25, 29.8, 6.0)]))
    assert len(one[0]) == 1 and len(two[0]) == 2


@pytest.mark.parametrize("radius", [2.5, 4.0, 6.0])
@pytest.mark.parametrize("axis", ["ew", "ns"])
def test_seam_tree_is_placed_once_by_the_cell_containing_it(radius, axis):
    seam = PX * 0.5  # 1000 m
    offsets = [-6.25, -4.75, -3.25, -2.25, -1.25, 1.25, 2.25, 3.25, 4.75, 6.25]
    tops = [(seam + off, 30.0 + 40.0 * k, 25.0, radius) for k, off in enumerate(offsets)]
    shape = (PX, 2 * PX)
    if axis == "ns":
        tops = [(y, x, h, r) for x, y, h, r in tops]
        shape = (2 * PX, PX)
    found = tops_global(crowns(shape, tops))
    for x, y, _, r in tops:
        hits = matches(found, tops, x, y, r)
        assert len(hits) == 1, (axis, x, y, hits)
        assert abs(hits[0][0] - x) < 0.6 and abs(hits[0][1] - y) < 0.6  # the real top, not a cut crown
    # a top within ~1 m of the seam may be lost (smoothing pushes it onto the edge pixel), never duplicated
    edge = [(seam + off, 30.0 + 40.0 * k, 25.0, radius) for k, off in enumerate([-0.75, -0.25, 0.25, 0.75])]
    if axis == "ns":
        edge = [(y, x, h, r) for x, y, h, r in edge]
    found = tops_global(crowns(shape, edge))
    assert all(len(matches(found, edge, x, y, r)) <= 1 for x, y, _, r in edge)


def test_corner_tree_is_placed_once():
    c = PX * 0.5
    tops = [(c + dx, c + dy, 30.0, 5.0) for dx, dy in [(-1.25, -1.25), (1.25, -1.25), (-1.25, 1.25), (1.25, 1.25)]]
    for t in tops:
        found = tops_global(crowns((2 * PX, 2 * PX), [t]))
        assert len(found) == 1 and abs(found[0][0] - t[0]) < 0.6 and abs(found[0][1] - t[1]) < 0.6


def test_kinds_are_deterministic_and_follow_the_share():
    dx = np.arange(0, 1000, 3)
    dz = (dx * 7) % 1000
    mix = np.full((100, 100), 30, dtype="i1")
    a = canopy.tree_kinds(2632, 1170, dx, dz, mix)
    assert np.array_equal(a, canopy.tree_kinds(2632, 1170, dx, dz, mix))
    assert 0.2 < (a == swd.KIND_BROADLEAF).mean() < 0.4 and set(a.tolist()) == {1, 2}
    assert canopy.tree_kinds(2632, 1170, dx, dz, None).tolist() == [0] * len(dx)


def test_ttop_roundtrip_absent_and_empty():
    cell = swd.Cell(2632, 1170, layers=flat_layers() | {"SURF": np.zeros((swd.CELL, swd.CELL), "u1")})
    assert swd.decode(swd.encode(cell)).trees is None
    cell.trees = []
    assert swd.decode(swd.encode(cell)).trees == []
    cell.trees = [swd.Tree(5, 9, 31, 40, 2), swd.Tree(7, 3, 2, 0, 1)]
    cell.layers["CNPY"] = np.full((swd.CELL, swd.CELL), 3, dtype="u1")
    back = swd.decode(swd.encode(cell))
    assert back.trees == [swd.Tree(7, 3, 2, 0, 1), swd.Tree(5, 9, 31, 40, 2)]  # sorted by (dz, dx)
    assert np.array_equal(back.layers["CNPY"], cell.layers["CNPY"])


class FakeIndex:
    def __init__(self, item):
        self.item, self.calls = item, 0

    def newest(self, e, n):
        self.calls += 1
        return self.item


def test_builder_pins_and_skips_missing_tiles(tmp_path):
    cfg = Config(raw=tmp_path, store=tmp_path, scratch=tmp_path)
    partial = build.LayerResult(layers=flat_layers())
    dsm = crowns((PX, PX), [(100.25, 100.25, 30.0, 5.0)]) + 600.0
    seen = []
    b = canopy.CanopyBuilder(index=FakeIndex("swisssurface3d-raster_2022_2632-1170"), pins=Pins(tmp_path),
                             dsm_for=lambda item: seen.append(item) or dsm, mix_for=lambda e, n: None)
    r = b.build(2632, 1170, cfg, partial, None)
    assert r.sources == {"surface": "swisssurface3d-raster_2022_2632-1170"} and len(r.trees) == 1
    assert r.layers["CNPY"][100, 100] == 30
    # a rebuild keeps the pinned item without asking the index
    b._index = FakeIndex("swisssurface3d-raster_2026_2632-1170")
    b.build(2632, 1170, cfg, partial, {"surface": "swisssurface3d-raster_2022_2632-1170"})
    assert b._index.calls == 0 and seen[-1] == "swisssurface3d-raster_2022_2632-1170"
    # no DSM tile there: neither CNPY nor TTOP, so the mod keeps its statistical trees
    none = canopy.CanopyBuilder(index=FakeIndex(None), pins=Pins(tmp_path / "other"), mix_for=lambda e, n: None)
    r = none.build(2632, 1170, cfg, partial, None)
    assert r.layers == {} and r.trees is None and r.sources == {}
    assert any(isinstance(x, canopy.CanopyBuilder) for x in build.BUILDERS)


def test_builder_deletes_dsm_unless_kept(tmp_path, monkeypatch):
    dsm_file = tmp_path / "dsm.tif"
    monkeypatch.setattr(canopy, "fetch", lambda cfg, url: dsm_file if "surface" in url else tmp_path / "mix.tif")
    monkeypatch.setattr(canopy, "read_dsm", lambda path: np.full((PX, PX), 600.0))
    monkeypatch.setattr(canopy, "read_forest_mix", lambda path, e, n: None)
    monkeypatch.setattr(canopy, "forest_mix_pin", lambda: "landesforstinventar-waldmischungsgrad@2023-10-31")
    partial = build.LayerResult(layers=flat_layers())
    pinned = {"surface": "swisssurface3d-raster_2022_2632-1170"}
    for keep in (True, False):
        dsm_file.write_bytes(b"x")
        cfg = Config(raw=tmp_path, store=tmp_path, scratch=tmp_path, keep_surface_raw=keep)
        r = canopy.CanopyBuilder().build(2632, 1170, cfg, partial, pinned)
        assert dsm_file.exists() == keep and r.trees == []
        assert r.sources["forestMix"] == "landesforstinventar-waldmischungsgrad@2023-10-31"
