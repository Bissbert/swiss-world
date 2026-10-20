"""sidecar-tlm: raster helpers, water levels, classification and the Brienz acceptance cell."""

import numpy as np
import pytest
import shapely

from swissdata import bathy, build, landcover, overlay, swd, tlm, water


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
def brienz(tmp_path_factory):
    from swissdata import tlm_preview

    cfg = tlm_preview.private_config(tmp_path_factory.mktemp("store"))
    if not tlm.extract_path(cfg).exists():
        pytest.skip("no TLM extract in $SWISSDATA_RAW (export it as in docs/dev.md)")
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


def test_preview_builders_add_terrain_only_once(monkeypatch):
    from swissdata import tlm_preview

    class Terrain:
        name, tags = "terrain", ("TERR",)

    others = [b for b in build.BUILDERS if "TERR" not in b.tags]
    monkeypatch.setattr(build, "BUILDERS", others)
    assert sum("TERR" in b.tags for b in tlm_preview.builders()) == 1
    monkeypatch.setattr(build, "BUILDERS", [Terrain(), *others])
    assert sum("TERR" in b.tags for b in tlm_preview.builders()) == 1


def test_raeterichsboden_dam(tmp_path):
    from swissdata import tlm_preview

    cfg = tlm_preview.private_config(tmp_path)
    if not tlm.extract_path(cfg).exists():
        pytest.skip("no TLM extract in $SWISSDATA_RAW (export it as in docs/dev.md)")
    cell, _ = build.assemble(2667, 1160, cfg, builders=tlm_preview.builders())
    L = cell.layers
    dam = (L["OFLG"] & swd.OFLG_DAM) > 0
    assert dam.sum() > 5_000
    assert np.all(L["OCLS"][dam] == overlay.DAM_CREST)
    assert np.allclose(swd.decode_height(L["OELV"][dam]), 1770.3, atol=0.05)


def test_rail_centre_continues_under_road_bridge(tmp_path):
    """2647_1177: a 6 m road bridge crosses the Brünig line east of Brienz."""
    from swissdata import tlm_preview

    cfg = tlm_preview.private_config(tmp_path)
    if not tlm.extract_path(cfg).exists():
        pytest.skip("no TLM extract in $SWISSDATA_RAW (export it as in docs/dev.md)")
    e_km, n_km = 2647, 1177
    cell, _ = build.assemble(e_km, n_km, cfg, builders=tlm_preview.builders())
    L = cell.layers
    ground_rails = [f.rail_line for f in overlay.collect(cfg, e_km, n_km) if f.rail_line is not None and not f.flags]
    dz, dx = np.array(sorted({p for line in ground_rails for p in overlay.grid_path(line, e_km, n_km)})).T
    assert np.all(L["OFLG"][dz, dx] & swd.OFLG_RAIL)  # no gap anywhere along the ground-level track

    crossing = (L["OFLG"] & swd.OFLG_RAIL > 0) & (L["OFLG"] & swd.OFLG_BRIDGE > 0) & (L["OCLS"] != overlay.RAIL)
    assert crossing.sum() >= 5
    assert set(np.unique(L["OCLS"][crossing]).tolist()) == {overlay.SECONDARY}  # the road deck stays on top
    clearance = swd.decode_height(L["OELV"][crossing]) - tlm.terr_columns(L["TERR"])[crossing]
    assert np.all(clearance > 5)  # deck well above the rail on the terrain


def test_rail_bit_under_road_bridge_only_for_road_decks():
    assert overlay.under_road_bridge(overlay.SECONDARY, swd.OFLG_BRIDGE)
    assert not overlay.under_road_bridge(overlay.RAIL, swd.OFLG_BRIDGE)  # rail bridge: rail is on the deck
    assert not overlay.under_road_bridge(overlay.SECONDARY, 0)


def _burn_order(tunnels, areas, roads, rails=()):
    return tunnels + areas + roads + overlay.kept_rail_tunnels(tunnels, areas, roads) + list(rails)


def test_rail_tunnel_keeps_its_centre_under_areas_and_paths():
    """Brienz: a pitch, a car park and a footpath over the station tunnel used to take its rail and tunnel bits."""
    e_km, n_km = 2646, 1178
    e0, n0 = e_km * 1000, n_km * 1000
    line = shapely.LineString([(e0 + 100, n0 + 500, 560.0), (e0 + 900, n0 + 500, 568.0)])
    tunnel = overlay.Feature(overlay._buffer(line, 4.0), overlay.RAIL, swd.OFLG_TUNNEL, line=line, rail_line=line)
    areas = [overlay.Feature(shapely.box(e0 + 200, n0 + 480, e0 + 260, n0 + 530), overlay.PITCH),
             overlay.Feature(shapely.box(e0 + 400, n0 + 470, e0 + 436, n0 + 520), overlay.PARKING)]
    path = overlay.Feature(overlay._buffer(shapely.LineString([(e0 + 600, n0 + 400), (e0 + 600, n0 + 600)]), 2.0),
                           overlay.FOOTPATH)
    ocls, oelv, oflg = overlay.rasterise(_burn_order([tunnel], areas, [path]), e_km, n_km)
    dz, dx = np.array(overlay.grid_path(line, e_km, n_km)).T
    assert np.all(oflg[dz, dx] & (swd.OFLG_RAIL | swd.OFLG_TUNNEL) == swd.OFLG_RAIL | swd.OFLG_TUNNEL)
    assert np.all(ocls[dz, dx] == overlay.RAIL)
    assert np.allclose(swd.decode_height(oelv[dz, dx]), 560 + 8 * (dx + 0.5 - 100) / 800, atol=0.1)  # tunnel floor
    beside = 500 - 20  # the areas and the path keep their columns off the tunnel
    assert ocls[beside, 230] == overlay.PITCH and ocls[beside + 30, 420] == overlay.PARKING
    assert ocls[beside, 600] == overlay.FOOTPATH


def test_rail_tunnel_under_a_road_keeps_its_centre_only_where_the_road_runs_along_it():
    e_km, n_km = 2646, 1178
    e0, n0 = e_km * 1000, n_km * 1000
    line = shapely.LineString([(e0 + 100, n0 + 500.5, 560.0), (e0 + 900, n0 + 500.5, 560.0)])
    tunnel = overlay.Feature(overlay._buffer(line, 4.0), overlay.RAIL, swd.OFLG_TUNNEL, line=line, rail_line=line)
    across = overlay.Feature(overlay._buffer(shapely.LineString([(e0 + 300, n0 + 400), (e0 + 300, n0 + 600)]), 6.0),
                             overlay.SECONDARY)
    along = overlay.Feature(overlay._buffer(shapely.LineString([(e0 + 500, n0 + 501), (e0 + 560, n0 + 501)]), 6.0),
                            overlay.MINOR)
    ocls, _, oflg = overlay.rasterise(_burn_order([tunnel], [], [across, along]), e_km, n_km)
    centre = 499  # dz of the column row the line runs through
    assert ocls[centre, 300] == overlay.SECONDARY and not oflg[centre, 300] & swd.OFLG_TUNNEL  # mod bores under it
    assert np.all(ocls[centre, 500:560] == overlay.RAIL)
    assert np.all(oflg[centre, 500:560] & swd.OFLG_RAIL) and np.all(oflg[centre, 500:560] & swd.OFLG_TUNNEL)
    assert ocls[centre - 2, 530] == overlay.MINOR and ocls[centre + 2, 530] == overlay.MINOR  # deck beside the strip


def test_surface_rail_keeps_its_centre_across_a_road():
    e_km, n_km = 2646, 1178
    e0, n0 = e_km * 1000, n_km * 1000
    line = shapely.LineString([(e0 + 100, n0 + 500, 560.0), (e0 + 900, n0 + 500, 560.0)])
    rail = overlay.Feature(overlay._buffer(line, 4.0), overlay.RAIL, rail_line=line)
    road = overlay.Feature(overlay._buffer(shapely.LineString([(e0 + 300, n0 + 400), (e0 + 300, n0 + 600)]), 6.0),
                           overlay.SECONDARY)
    ocls, _, oflg = overlay.rasterise(_burn_order([], [], [road], [rail]), e_km, n_km)
    dz, dx = np.array(overlay.grid_path(line, e_km, n_km)).T
    assert np.all(oflg[dz, dx] & swd.OFLG_RAIL) and np.all(ocls[dz, dx] == overlay.RAIL)


def test_main_line_tag_follows_achse_dkm():
    rail = {"ZAHNRADBAHN": 1, "STANDSEILBAHN": 1, "AUSSER_BETRIEB": 1}
    assert overlay.is_main_line({**rail, "ACHSE_DKM": tlm.BOOL_YES})
    assert not overlay.is_main_line({**rail, "ACHSE_DKM": 1})
    assert not overlay.is_main_line(rail)  # an extract without the attribute: no main line, as before


def test_main_line_bit_only_along_the_main_track():
    """A passing loop: the loop track leaves the main track and joins it again; only the main track gets bit5,
    also where it runs in a tunnel under a car park (kept_rail_tunnels)."""
    e_km, n_km = 2646, 1178
    e0, n0 = e_km * 1000, n_km * 1000
    main = shapely.LineString([(e0 + 100, n0 + 500.5, 560.0), (e0 + 900, n0 + 500.5, 560.0)])
    loop = shapely.LineString([(e0 + 300, n0 + 500.5, 560.0), (e0 + 340, n0 + 505.5, 560.0),
                               (e0 + 460, n0 + 505.5, 560.0), (e0 + 500, n0 + 500.5, 560.0)])
    rails = [overlay.Feature(overlay._buffer(main, 3.0), overlay.RAIL, rail_line=main, main=True),
             overlay.Feature(overlay._buffer(loop, 3.0), overlay.RAIL, rail_line=loop)]
    tline = shapely.LineString([(e0 + 600, n0 + 300.5, 555.0), (e0 + 700, n0 + 300.5, 555.0)])
    tunnel = overlay.Feature(overlay._buffer(tline, 3.0), overlay.RAIL, swd.OFLG_TUNNEL, line=tline,
                             rail_line=tline, main=True)
    park = overlay.Feature(shapely.box(e0 + 620, n0 + 290, e0 + 680, n0 + 310), overlay.PARKING)
    _, _, oflg = overlay.rasterise(_burn_order([tunnel], [park], [], rails), e_km, n_km)
    centre = 499
    assert np.all(oflg[centre, 100:900] & swd.OFLG_RAIL) and np.all(oflg[centre, 100:900] & swd.OFLG_MAIN)
    under_park = oflg[centre + 200, 620:680]  # the tunnel part under the car park keeps bit5 too
    assert np.all(under_park & swd.OFLG_TUNNEL) and np.all(under_park & swd.OFLG_MAIN)
    assert np.all(oflg[centre - 5, 345:455] & swd.OFLG_RAIL)
    assert not np.any(oflg[centre - 5, 345:455] & swd.OFLG_MAIN)
    assert not np.any((oflg & swd.OFLG_MAIN > 0) & (oflg & swd.OFLG_RAIL == 0))  # bit5 only with bit2


def test_brienz_main_line_is_tagged(brienz):
    """The Brünig line through 2646_1178 is one main track; the station's other tracks are not."""
    cfg, L, _ = brienz
    rail = L["OFLG"] & swd.OFLG_RAIL > 0
    main = L["OFLG"] & swd.OFLG_MAIN > 0
    if not main.any():
        pytest.skip("TLM extract without ACHSE_DKM")
    assert np.all(rail[main])
    assert 0.3 < main.sum() / rail.sum() < 0.95
    box = tlm.cell_box(2646, 1178)
    cols = set()
    for g, p in tlm.read(cfg, "TLM_EISENBAHN", tlm.cell_bbox(2646, 1178)):
        if g.intersects(box) and overlay.is_main_line(p):
            cols.update(overlay.grid_path(g, 2646, 1178))
    dz, dx = np.array(sorted(cols)).T
    assert np.mean(main[dz, dx]) > 0.95
