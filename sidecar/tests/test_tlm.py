"""sidecar-tlm: raster helpers, water levels, classification and the Brienz acceptance cell."""

import numpy as np
import pytest
import shapely

from swissdata import bathy, build, landcover, overlay, swd, tlm, water
from swissdata.config import Config


def test_burn_rows_are_dz_from_the_north():
    e_km, n_km = 2646, 1178
    nw = shapely.box(2_646_000, 1_178_998, 2_646_003, 1_179_000)  # 3 x 2 m in the NW corner
    a = tlm.burn([(nw, 7)], e_km, n_km)
    assert a.shape == (1000, 1000)
    assert a[:2, :3].tolist() == [[7, 7, 7], [7, 7, 7]]
    assert a.sum() == 7 * 6


def test_burn_nodes_and_column_points_share_the_contract_geometry():
    e_km, n_km = 2646, 1178
    m = tlm.burn_nodes([(shapely.Point(2_646_010, 1_178_990).buffer(0.5), 1)], e_km, n_km)
    assert np.argwhere(m).tolist() == [[5, 5]]  # node (i=5, j=5) at E = e0 + 10, N = n0 + 990
    p = tlm.column_points(e_km, n_km, np.array([0]), np.array([0]))[0]
    assert (p.x, p.y) == (2_646_000.5, 1_178_999.5)


def test_terr_columns_interpolates_between_nodes():
    i = np.arange(swd.TERR_N)
    h = np.broadcast_to(1000.0 + 2.0 * i, (swd.TERR_N, swd.TERR_N))  # slope 1 m/m eastwards
    cols = tlm.terr_columns(swd.encode_height(h))
    assert np.allclose(cols[0, :3], [1000.5, 1001.5, 1002.5])
    assert np.allclose(cols[:, 10], 1010.5)


def test_value_along_samples_z():
    line = shapely.LineString([(0, 0, 10), (10, 0, 0)])
    z = tlm.value_along(np.array([line, line]), shapely.points([2, 7], [3, -1]))
    assert np.allclose(z, [8, 3])


def test_propagate_levels_is_monotonic_stepped_and_capped_at_confluences():
    trib = np.array([[0, 10, 120.0], [0, 5, 104.0], [0, 0, 103.6]])  # ends where main starts
    main = np.array([[0, 0, 103.9], [5, 0, 104.5], [10, 0, 99.2]])  # bump upstream of a drop
    side = np.array([[5, 5, 50.0], [6, 6, 51.0]])  # unconnected, goes uphill
    t, m, s = water.propagate_levels([trib, main, side])
    assert t.tolist() == [120, 104, 103]
    assert m.tolist() == [103, 103, 99]  # capped by the tributary's end, never rises
    assert s.tolist() == [50, 50]


def test_propagate_levels_survives_a_cycle():
    a = np.array([[0, 0, 10.0], [1, 0, 9.0]])
    b = np.array([[1, 0, 9.0], [0, 0, 8.0]])
    la, lb = water.propagate_levels([a, b])
    assert la[-1] >= lb[0] or lb[-1] >= la[0]
    assert np.all(np.diff(la) <= 0) and np.all(np.diff(lb) <= 0)


def test_grid_path_is_4_connected():
    line = shapely.LineString([(2_646_000.2, 1_178_999.7), (2_646_030.4, 1_178_970.1), (2_646_060.0, 1_178_985.0)])
    path = overlay.grid_path(line, 2646, 1178)
    steps = np.abs(np.diff(np.array(path), axis=0)).sum(axis=1)
    assert path[0] == (0, 0)
    assert set(steps.tolist()) == {1}


def test_road_and_rail_classes():
    road = {"OBJEKTART": 10, "KUNSTBAUTE": 100, "BELAGSART": 100}
    assert overlay.road_class(road) == (overlay.MINOR, 4.0)
    assert overlay.road_class({**road, "BELAGSART": overlay.UNPAVED}) == (overlay.TRACK, 4.0)
    assert overlay.road_class({**road, "KUNSTBAUTE": overlay.ROAD_STEPS})[0] == overlay.STEPS
    assert overlay.road_class({**road, "OBJEKTART": 13}) is None  # Autozug
    rail = {"ZAHNRADBAHN": 1, "STANDSEILBAHN": 1, "AUSSER_BETRIEB": 1}
    assert overlay.rail_class(rail) == overlay.RAIL
    assert overlay.rail_class({**rail, "ZAHNRADBAHN": tlm.BOOL_YES}) == overlay.RACK


def test_classify_uses_altitude():
    raw = np.array([[-1, -1, 112, 112, 112, 107, 107, landcover.ORCHARD]])
    alt = np.array([[600, 1900, 600, 1000, 1500, 600, 1200, 600.0]])
    assert landcover.classify(raw, alt).tolist() == [[
        landcover.MEADOW, landcover.ALPINE, landcover.BROADLEAF, landcover.MIXED, landcover.CONIFER,
        landcover.GRAVEL, landcover.SCREE, landcover.ORCHARD]]


def test_bathy_nodes_average_the_four_surrounding_pixels():
    m = np.full((1002, 1002), np.nan)
    m[0:2, 0:2] = [[1, 2], [3, np.nan]]
    nodes = bathy.at_nodes(m)
    assert nodes.shape == (501, 501)
    assert nodes[0, 0] == 2.0 and np.isnan(nodes[0, 1])


def test_pinned_other_tlm_release_is_refused():
    tlm.check_pinned({"tlm": tlm.TLM_ITEM})
    tlm.check_pinned(None)
    with pytest.raises(ValueError):
        tlm.check_pinned({"tlm": "swisstlm3d_2020-03"})


def test_builders_are_registered_before_default_surface():
    names = [b.name for b in build.BUILDERS]
    assert names[-1] == "default-surface"
    assert names.index("tlm-water") < names.index("tlm-landcover") < names.index("default-surface")


# --- acceptance: the real Brienz cell (needs the shared raw cache) ---

@pytest.fixture(scope="module")
def brienz():
    cfg = Config.from_env()
    if not tlm.extract_path(cfg).exists():
        pytest.skip("no TLM extract in $SWISSDATA_RAW (run python -I -m swissdata.tlm)")
    from swissdata import tlm_preview

    cell, sources = build.assemble(2646, 1178, cfg, builders=tlm_preview.builders())
    return cfg, cell.layers, sources


def test_brienz_surface(brienz):
    _, L, _ = brienz
    s = set(np.unique(L["SURF"]).tolist())
    assert landcover.LAKE in s
    assert s & {landcover.BROADLEAF, landcover.CONIFER, landcover.MIXED}
    assert s & {landcover.URBAN_SEALED, landcover.URBAN_GREEN}


def test_brienz_lake_level_and_bed(brienz):
    _, L, sources = brienz
    lake = L["WKND"] == water.WKND_LAKE
    assert lake.sum() > 50_000
    wlvl = swd.decode_height(L["WLVL"])
    assert np.all(np.abs(wlvl[lake] - 564) <= 0.2)
    terr = tlm.terr_columns(L["TERR"])
    wet = L["WKND"] > 0
    assert np.all(terr[wet] < wlvl[wet])
    assert np.nanmin(terr[lake]) < 564 - 50  # surveyed bed, not a flat plate
    assert sources["bathy"] == ["swissbathy3d_brienzersee"] and sources["tlm"] == tlm.TLM_ITEM
    assert np.all(np.isnan(wlvl[~wet]))


def test_brienz_rail_along_bruenig_line(brienz):
    cfg, L, _ = brienz
    box = tlm.cell_box(2646, 1178)
    cols = set()
    for g, p in tlm.read(cfg, "TLM_EISENBAHN", tlm.cell_bbox(2646, 1178)):
        if g.intersects(box) and p["KUNSTBAUTE"] == tlm.KUNSTBAUTE_NONE:
            cols.update(overlay.grid_path(g, 2646, 1178))
    assert len(cols) > 500
    dz, dx = np.array(sorted(cols)).T
    assert np.mean(L["OCLS"][dz, dx] == overlay.RAIL) > 0.95
    assert np.mean(L["OFLG"][dz, dx] & swd.OFLG_RAIL > 0) > 0.95


def test_brienz_roads_and_bridges(brienz):
    _, L, _ = brienz
    s = set(np.unique(L["OCLS"]).tolist())
    assert {overlay.SECONDARY, overlay.MINOR, overlay.FOOTPATH} <= s
    bridge = (L["OFLG"] & swd.OFLG_BRIDGE) > 0
    assert bridge.any()
    assert np.all(L["OELV"][bridge] != swd.NO_HEIGHT)
    plain = (L["OFLG"] & (swd.OFLG_BRIDGE | swd.OFLG_TUNNEL | swd.OFLG_DAM)) == 0
    assert np.all(L["OELV"][plain] == swd.NO_HEIGHT)
