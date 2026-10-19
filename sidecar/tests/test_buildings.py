import numpy as np
import pytest

from swissdata import buildings, build, gwr, swd
from swissdata.config import Config
from test_voxel import box

E0, N0 = 2632000, 1170000


def props(uuid, unit, egid=0, art="Gebaeude Einzelhaus"):
    return {"UUID": uuid, "GEBAEUDEEINHEIT": unit, "EGID": egid, "OBJEKTART": art}


def test_newest_per_sheet_ignores_country_items():
    ids = ["swissbuildings3d_3_0_2018_1208-43", "swissbuildings3d_3_0_2021_1208-43",
           "swissbuildings3d_3_0_2024_1228-21", "swissbuildings3d_3_0_2026", "swissbuildings3d_3_0_2021_1228-21x"]
    assert buildings.newest_per_sheet(ids) == ["swissbuildings3d_3_0_2021_1208-43",
                                               "swissbuildings3d_3_0_2024_1228-21"]
    with pytest.raises(ValueError):
        buildings.sheet_of("swissbuildings3d_3_0_2026")


def test_cell_bbox_wgs84_contains_interlaken():
    w, s, e, n = buildings.cell_bbox_wgs84(2632, 1170)
    assert w < 7.8600 < e and s < 46.6850 < n


def test_roof_flags():
    b = np.array([0, 0, 0, 1])
    e = np.array([5, 5, 6, 5])
    n = np.array([7, 7, 7, 7])
    top = swd.encode_height(np.array([603.0, 609.5, 604.2, 610.0]))
    f = buildings.roof_flags(b, e, n, top)
    assert f.tolist() == [0, buildings.ROOF_TOP | buildings.ROOF_HALF, buildings.ROOF_TOP, buildings.ROOF_TOP]


def synthetic_sheet():
    # house crossing the west border of cell 2632_1170 (columns E 2631995..2632004, N 1170500..1170507),
    # its roof skin sticks out 1 m on the south side; plus a free-standing roof of a unit without body (carport)
    body = box(E0 - 5.0, N0 + 500.0, 10.0, 8.0, 600.0, 606.0)
    skin = box(E0 - 5.0, N0 + 499.0, 10.0, 9.0, 606.0, 606.2)
    shed = box(E0 + 20.0, N0 + 520.0, 2.0, 3.0, 601.0, 603.0)
    carport = box(E0 + 40.0, N0 + 40.0, 2.0, 2.0, 602.0, 602.2)
    return buildings.sheet_from_solids(
        "swissbuildings3d_3_0_2021_1208-43",
        [(props("{A}", "{U1}", 1346414), body), (props("{B}", "{U2}", art="Treibhaus"), shed)],
        [(props("{R1}", "{U1}"), skin), (props("{R2}", "{U9}", art="Flugdach"), carport)])


def test_sheet_overhang_and_free_roofs():
    sh = synthetic_sheet()
    assert sh.orphan_roofs == 1 and sh.odd_columns == 0
    assert sh.uuid.tolist() == ["{A}", "{B}", "{R2}"]
    assert abs(sh.volume[0] - (480.0 + 18.0)) < 1e-6  # body + roof skin
    a = sh.building == 0
    assert (sh.part[a] == 0).sum() == 80
    # the skin resting on the body raises it to the roof surface
    assert np.all(sh.top[a & (sh.part == 0)] == swd.encode_height(606.2))
    over = a & (sh.part == 1)
    assert over.sum() == 10 and set(sh.n[over]) == {N0 + 499}
    assert np.all(sh.roof[a] & buildings.ROOF_TOP)
    assert abs(sh.top_m[0] - 606.2) < 1e-9 and sh.base_m[0] == 600.0
    assert sh.objektart.tolist() == [buildings.OBJEKTART["Gebaeude Einzelhaus"], buildings.OBJEKTART["Treibhaus"],
                                    buildings.OBJEKTART["Flugdach"]]
    # the carport: its own row, roof skin only, all overhang (no walls)
    c = sh.building == 2
    assert c.sum() == 4 and np.all(sh.part[c] == buildings.PART_OVERHANG) and np.all(sh.roof[c] & buildings.ROOF_TOP)
    assert (sh.base_m[2], sh.top_m[2]) == (602.0, 602.2) and abs(sh.volume[2] - 0.8) < 1e-6


def test_free_roof_pieces_of_one_unit_form_one_row():
    body = box(E0, N0, 4.0, 4.0, 600.0, 606.0)
    a = box(E0 + 20.0, N0, 2.0, 2.0, 603.0, 603.2)
    b = box(E0 + 22.0, N0, 2.0, 2.0, 603.0, 603.2)
    sh = buildings.sheet_from_solids("swissbuildings3d_3_0_2021_1208-43", [(props("{A}", "{U1}"), body)],
                                     [(props("{R9}", "{U7}", 5), b), (props("{R3}", "{U7}"), a)])
    assert sh.uuid.tolist() == ["{A}", "{R3}"] and sh.egid.tolist() == [0, 5] and sh.orphan_roofs == 2
    assert (sh.building == 1).sum() == 8


def test_free_roofs_without_uuid():
    # some sheets carry roof skins without UUID: they must not break the row-id choice
    a = box(E0 + 20.0, N0, 2.0, 2.0, 603.0, 603.2)
    b = box(E0 + 22.0, N0, 2.0, 2.0, 603.0, 603.2)
    c = box(E0 + 40.0, N0, 2.0, 2.0, 603.0, 603.2)
    sh = buildings.sheet_from_solids("swissbuildings3d_3_0_2021_1208-43", [],
                                     [(props(None, "{U7}"), a), (props("{R3}", "{U7}"), b),
                                      (props(None, "{U8}"), c), (props(None, "{U8}"), a)])
    assert sh.uuid.tolist() == ["{R3}", "{U8}"] and sh.orphan_roofs == 4


def test_cell_buildings_cross_border_and_dz_sign():
    sh = synthetic_sheet()
    table = gwr.GwrTable(np.array([1346414]), np.array([1040], "u2"), np.array([1211], "u2"),
                         np.array([8011], "u2"), np.array([6], "u1"), "2026-10-19")
    blds, spans = buildings.cell_buildings(2632, 1170, [sh], table)
    west, west_spans = buildings.cell_buildings(2631, 1170, [sh], table)
    assert len(blds) == 3 and len(west) == 1
    house = next(b for b in blds if b.egid == 1346414)
    assert house.gid == west[0].gid == swd.gid_from_uuid("{A}")
    assert (house.gwr_class, house.floors, house.gwr_period) == (1211, 6, 8011)
    # dz grows southwards: N 1170507 (north edge row) -> dz 492, overhang row N 1170499 -> dz 500
    assert (house.min_x, house.max_x, house.min_z, house.max_z) == (-5, 4, 492, 500)
    assert (west[0].min_x, west[0].max_x) == (995, 1004)
    hi = blds.index(house)
    assert spans[(0, 500)][0].part == 1 and spans[(0, 500)][0].building_idx == hi
    assert spans[(0, 492)][0].part == 0
    assert (995, 492) in west_spans and (5, 492) not in spans
    assert house.base_h == swd.encode_height(600.0) and house.top_h == swd.encode_height(606.2)
    assert sum(len(c) for c in spans.values()) + sum(len(c) for c in west_spans.values()) == 80 + 10 + 6 + 4


def test_builder_uses_pinned_items(monkeypatch, tmp_path):
    sh = synthetic_sheet()
    monkeypatch.setattr(buildings, "find_sheets", lambda *a: pytest.fail("STAC must not be queried"))
    monkeypatch.setattr(buildings, "load_sheet", lambda cfg, item: sh)
    empty = gwr.GwrTable(np.zeros(0, np.int64), *(np.zeros(0, t) for t in ("u2", "u2", "u2", "u1")), "2026-10-19")
    monkeypatch.setattr(gwr, "load", lambda *a: empty)
    cfg = Config(raw=tmp_path, store=tmp_path, scratch=tmp_path)
    r = buildings.BuildingsBuilder().build(2632, 1170, cfg, build.LayerResult(),
                                           {"buildings": [sh.item]})
    assert r.sources == {"buildings": [sh.item], "gwr": "gwr_be_2026-10-19"}
    assert len(r.buildings) == 3 and r.buildings[0].gwr_class == 0
    assert any(isinstance(b, buildings.BuildingsBuilder) for b in build.BUILDERS)


def test_disjoint_spans():
    S = swd.Span
    col = [S(100, 200, 0), S(150, 180, 1), S(190, 260, 2, 1, 1), S(260, 270, 3)]
    assert buildings.disjoint(col) == [S(100, 200, 0), S(200, 260, 2, 1, 1), S(260, 270, 3)]


def test_sheet_of_2020_schema_links_roofs_by_uuid():
    # the 2020 schema has no GEBAEUDEEINHEIT column; a roof skin carries the UUID of its body
    old = lambda uuid: {"UUID": uuid, "EGID": None, "OBJEKTART": "Gebaeude Einzelhaus"}  # noqa: E731
    body = box(E0 + 10.0, N0 + 10.0, 4.0, 4.0, 600.0, 606.0)
    skin = box(E0 + 10.0, N0 + 10.0, 4.0, 4.0, 606.0, 606.2)
    orphan = box(E0 + 40.0, N0 + 40.0, 2.0, 2.0, 600.0, 600.2)
    sh = buildings.sheet_from_solids("swissbuildings3d_3_0_2020_1250-13", [(old("{A}"), body)],
                                     [(old("{A}"), skin), (old("{X}"), orphan)])
    assert sh.orphan_roofs == 1 and abs(sh.top_m[0] - 606.2) < 1e-9
