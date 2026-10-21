"""The whole-country TLM extract (v3): cells outside the old test box get their layers, cells inside keep theirs."""

import hashlib

import numpy as np
import pytest

from swissdata import build, landcover, overlay, tlm, water

# TLM-only builders (no terrain, so nothing is downloaded): water, land cover, overlay.
# Digests of their layers for interior cells of the old box, recorded with extract v2 and this code (fid-ordered
# tlm.read) on the commit before the national extract; v3 must reproduce them. Unsorted (R-tree order, how the
# stored cells were built), overlapping land cover made SURF of 2646_1178 and 2660_1173 differ between v2 and v3.
V2_DIGESTS = {
    (2646, 1178): "803946ab3ddfc91929e5fcbd",  # Brienz: lake, rivers, Brünig line main/trunk
    (2656, 1175): "aefce2029844da3954123114",  # Meiringen: Aare, station where two main lines merge
    (2632, 1170): "f3d83472df071c3e38d0cd3c",  # Interlaken
    (2660, 1173): "192909bf4a2bb86c70321651",  # Innertkirchen: overlapping land cover
    (2650, 1165): "d8636bf31e777e3f997af418",
}


def tlm_layers(cfg, e_km, n_km):
    cell, _ = build.assemble(e_km, n_km, cfg, None,
                             [water.WaterBuilder(), landcover.LandcoverBuilder(), overlay.OverlayBuilder()])
    return cell.layers


def digest(layers) -> str:
    h = hashlib.sha256()
    for k, v in sorted(layers.items()):
        h.update(k.encode())
        h.update(str(v.dtype).encode())
        h.update(v.tobytes())
    return h.hexdigest()[:24]


@pytest.fixture(scope="module")
def cfg(tmp_path_factory):
    from swissdata import tlm_preview

    cfg = tlm_preview.private_config(tmp_path_factory.mktemp("store"))
    if not tlm.extract_path(cfg).exists():
        pytest.skip("no TLM extract in $SWISSDATA_RAW (export it as in docs/dev.md)")
    return cfg


@pytest.mark.parametrize("cell", sorted(V2_DIGESTS))
def test_interior_cells_are_byte_identical_to_v2(cfg, cell):
    assert digest(tlm_layers(cfg, *cell)) == V2_DIGESTS[cell]


def test_gotthard_pass_road(cfg):
    """2685_1158: the Gotthardstrasse and the Tremola (outside the old box: OCLS was all 0)."""
    L = tlm_layers(cfg, 2685, 1158)
    roads = np.isin(L["OCLS"], [overlay.MAIN, overlay.SECONDARY, overlay.MINOR])
    assert roads.sum() > 5_000
    assert (L["SURF"] != 0).any() and (L["WKND"] > 0).any()  # land cover (rock, scree) and the Reuss


@pytest.mark.parametrize("cell", [(2600, 1199), (2717, 1095)])  # Bern old town on the Aare; Lugano on its lake
def test_cities_outside_the_old_box(cfg, cell):
    L = tlm_layers(cfg, *cell)
    assert np.isin(L["OCLS"], [overlay.MAIN, overlay.SECONDARY, overlay.MINOR]).sum() > 10_000
    assert (L["WKND"] > 0).sum() > 10_000
    assert (L["SURF"] == landcover.URBAN_SEALED).sum() > 50_000


def test_cells_to_rebuild(cfg):
    stale = tlm.missing_in_legacy(cfg, [(2646, 1178), (2656, 1175), (2685, 1158), (2747, 1254), (2639, 1241)])
    assert stale == [(2685, 1158), (2747, 1254), (2639, 1241)]


def test_river_caps_keep_the_legacy_lines_fixed():
    # a: legacy line fed by an outside line b that ends lower than a starts; c flows out of a
    starts = [(0, 0), (5, 5), (1, 0)]
    ends = [(1, 0), (0, 0), (2, 0)]
    zmin = np.array([10.0, 7.5, 9.0])
    free = water.propagate_caps(starts, ends, zmin)
    assert free.tolist() == [7.0, np.inf, 7.0]
    fixed = water.propagate_caps(starts, ends, zmin, fixed={0: np.inf})
    assert fixed.tolist() == [np.inf, np.inf, 10.0]
