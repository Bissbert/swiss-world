"""Acceptance on real data: cell 2632_1170 (Interlaken). Skipped when the raw files are not cached; never
touches the network (sheets are pinned, GWR comes from the cached be.zip)."""

import numpy as np
import pytest
import shapely
from shapely.geometry import box as rect

from swissdata import build, buildings, gwr, swd, voxel
from swissdata.config import Config
from swissdata.fetch import cache_path

E_KM, N_KM = 2632, 1170
# what find_sheets(2632, 1170) returned on 2026-10-19 (pinned like a manifest would)
ITEMS = ["swissbuildings3d_3_0_2021_1208-43", "swissbuildings3d_3_0_2021_1208-44",
         "swissbuildings3d_3_0_2021_1228-21", "swissbuildings3d_3_0_2021_1228-22"]
VICTORIA_EGID = 1346414  # Hotel Victoria-Jungfrau, Höheweg 41 (GWR entrance table)

cfg = Config.from_env()
_missing = [u for u in [buildings.ASSET_URL.format(item=i) for i in ITEMS] + [gwr.URL.format(canton="be")]
            if not cache_path(cfg, u).exists()]
pytestmark = pytest.mark.skipif(bool(_missing), reason=f"raw data not cached: {_missing}")


@pytest.fixture(scope="module")
def result():
    r = buildings.BuildingsBuilder().build(E_KM, N_KM, cfg, build.LayerResult(), {"buildings": ITEMS})
    return r, [buildings.load_sheet(cfg, i) for i in ITEMS]


def footprints_in_cell():
    """Independent reference: projected Building_solid triangles (shapely) intersecting the cell."""
    cell = rect(E_KM * 1000, N_KM * 1000, E_KM * 1000 + 1000, N_KM * 1000 + 1000)
    out = {}
    for item in ITEMS:
        path = buildings.gdb_path(cache_path(cfg, buildings.ASSET_URL.format(item=item)), item)
        for p, t in buildings._read_layer(path, "Building_solid"):
            if len(t) == 0 or t[:, :, 0].max() < cell.bounds[0] or t[:, :, 0].min() > cell.bounds[2] \
                    or t[:, :, 1].max() < cell.bounds[1] or t[:, :, 1].min() > cell.bounds[3]:
                continue
            fp = shapely.union_all(shapely.polygons(t[:, :, :2]))
            area = fp.intersection(cell).area
            if area > 0:
                out[p["UUID"]] = area
    return out


def test_building_count_matches_solids(result):
    (r, sheets) = result
    ref = footprints_in_cell()
    got = {str(sh.uuid[b]) for sh in sheets for b in range(len(sh.uuid))
           if swd.gid_from_uuid(str(sh.uuid[b])) in {x.gid for x in r.buildings}}
    assert len(got) == len(r.buildings)
    # extra BTBL rows may only reach into the cell with their eaves (Roof_solid outside the body footprint)
    e0, n0 = E_KM * 1000, N_KM * 1000
    for u in got - set(ref):
        sh = next(sh for sh in sheets if u in sh.uuid)
        m = (sh.uuid[sh.building] == u) & (sh.e >= e0) & (sh.e < e0 + 1000) & (sh.n >= n0) & (sh.n < n0 + 1000)
        assert np.all(sh.part[m] == buildings.PART_OVERHANG), u
    missing = {u: a for u, a in ref.items() if u not in got}
    print(f"\nBTBL {len(r.buildings)}, solids intersecting the cell {len(ref)}, "
          f"missing (no column centre inside) {len(missing)}: {sorted(round(a, 2) for a in missing.values())}")
    # a building is only in a cell when it covers at least one column centre there: slivers along the cell
    # border are the only allowed difference
    assert all(a < 10.0 for a in missing.values())
    assert len(missing) <= 0.02 * len(ref)


def test_span_volume_matches_solid_volume(result):
    (r, sheets) = result
    gids = {b.gid for b in r.buildings}
    solid = body = 0.0
    for sh in sheets:
        sel = np.array([swd.gid_from_uuid(str(u)) in gids for u in sh.uuid])
        solid += sh.volume[sel].sum()
        s = sel[sh.building]
        body += ((sh.top[s].astype(float) - sh.bottom[s]) / 10.0).sum()
    print(f"\nspan volume {body:.0f} m³ vs Building_solid + Roof_solid volume {solid:.0f} m³ "
          f"({100 * (body / solid - 1):+.2f} %)")
    assert abs(body / solid - 1) < 0.05


def test_gwr_coverage(result):
    (r, _) = result
    with_egid = [b for b in r.buildings if b.egid]
    classed = [b for b in with_egid if b.gwr_class]
    print(f"\n{len(classed)}/{len(with_egid)} buildings with an EGID have a GWR class "
          f"({len(r.buildings) - len(with_egid)} without EGID)")
    assert len(classed) >= 0.8 * len(with_egid)
    assert r.sources["gwr"].startswith("gwr_be_")


def test_victoria_jungfrau(result):
    (r, _) = result
    cell = swd.decode(swd.encode(swd.Cell(E_KM, N_KM, layers={
        "TERR": np.zeros((swd.TERR_N, swd.TERR_N), "<u2"), "SURF": np.zeros((swd.CELL, swd.CELL), "u1")},
        buildings=r.buildings, spans=r.spans)))
    idx = [i for i, b in enumerate(cell.buildings) if b.egid == VICTORIA_EGID]
    assert len(idx) == 1
    i = idx[0]
    v = cell.buildings[i]
    assert v.gwr_class == 1211  # hotel
    # swissBUILDINGS3D: DACH_MAX 604.53 m, GELAENDEPUNKT 566.28 m, GESAMTHOEHE 38.25 m
    assert abs(float(swd.decode_height(v.top_h)) - 604.53) <= 1.0
    tops = [s.top_h for col in cell.spans.values() for s in col if s.building_idx == i]
    print(f"\nVictoria-Jungfrau topH {swd.decode_height(v.top_h)} m, highest span top {swd.decode_height(max(tops))} m "
          f"(DACH_MAX 604.53 m: a pointed tower roof whose apex falls between column centres)")
    assert abs(float(swd.decode_height(max(tops))) - 604.53) <= 1.0
    # the hotel crosses the west cell border; the Höheweg 41 entrance (E 2632011, N 1170668) lies in its bbox
    assert v.min_x < 0 and v.min_x <= 2632011 - E_KM * 1000 <= v.max_x
    assert v.min_z <= N_KM * 1000 + 999 - 1170668 <= v.max_z


def test_deterministic(result):
    (r, _) = result
    r2 = buildings.BuildingsBuilder().build(E_KM, N_KM, cfg, build.LayerResult(), {"buildings": ITEMS})
    assert r2.buildings == r.buildings and r2.spans == r.spans
