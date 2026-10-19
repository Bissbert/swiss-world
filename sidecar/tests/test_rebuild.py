import numpy as np
import pytest

from swissdata import build, rebuild, swd
from swissdata.build import LayerResult
from swissdata.config import Config
from swissdata.store import Store


def cfg_for(tmp_path, dev_bbox=True):
    return Config(raw=tmp_path / "raw", store=tmp_path / "store", scratch=tmp_path / "scratch", dev_bbox=dev_bbox)


class Terrain:
    """Stands in for AltiTerrain: keeps a pinned item, else pins "alti-new"."""

    name, tags = "terrain", ("TERR",)

    def build(self, e_km, n_km, cfg, partial, pinned):
        item = (pinned or {}).get("alti", "alti-new")
        return LayerResult(layers={"TERR": np.full((swd.TERR_N, swd.TERR_N), len(item), dtype="<u2")},
                           sources={"alti": item})


class Tlm:
    name, tags = "tlm", ("SURF",)

    def __init__(self, item="tlm-1"):
        self.item = item

    def build(self, e_km, n_km, cfg, partial, pinned):
        return LayerResult(layers={"SURF": np.ones((swd.CELL, swd.CELL), dtype="u1")}, sources={"tlm": self.item})


def test_merge_sources_keeps_pins_and_adds_new_keys():
    old = {"alti": "a", "altiNeighbours": {"1-1": None}, "gone": 1}
    assert build.merge_sources(old, {"alti": "a", "tlm": "t"}) == {**old, "tlm": "t"}
    assert build.merge_sources(None, {"tlm": "t"}) == {"tlm": "t"}
    with pytest.raises(build.PinChanged):
        build.merge_sources(old, {"alti": "b"})


def test_select_orders_origin_first_and_skips_evicted_and_outside_dev_area():
    entries = {"2650_1160": {}, "2632_1170": {}, "2627_1164": {}, "2640_1177": {"evicted": "x"},
               "2700_1200": {}, "2629_1160": {}}
    manifest = {"cells": entries}
    assert rebuild.select(manifest, None, True) == [(2627, 1164), (2632, 1170), (2629, 1160), (2650, 1160)]
    assert (2700, 1200) in rebuild.select(manifest, None, False)
    assert rebuild.select(manifest, (2628, 1160, 2640, 1170), True) == [(2632, 1170), (2629, 1160)]


def test_rebuild_keeps_pins_adds_tlm_and_rewrites_cells(tmp_path):
    cfg = cfg_for(tmp_path)
    store = Store(cfg)
    old = {"alti": "alti-old", "altiNeighbours": {"2631-1170": "n"}}
    cell, _ = build.assemble(2632, 1170, cfg, old, [Terrain(), build.DefaultSurface()])
    store.write(2632, 1170, swd.encode(cell), old)
    stats = rebuild.rebuild(cfg, rebuild.select(store.manifest.file.read(), None, True), builders=[Terrain(), Tlm()])
    assert stats["cells"] == 1 and not stats["failed"] and stats["bytesPerCell"] == stats["bytes"]
    assert Store(cfg).manifest.sources(2632, 1170) == {**old, "tlm": "tlm-1"}
    c = swd.decode(store.path(2632, 1170).read_bytes())
    assert int(c.layers["SURF"][0, 0]) == 1 and int(c.layers["TERR"][0, 0]) == len("alti-old")


def test_rebuild_refuses_a_changed_pin_and_leaves_the_cell(tmp_path):
    cfg = cfg_for(tmp_path)
    store = Store(cfg)
    cell, _ = build.assemble(2632, 1170, cfg, None, [Terrain(), Tlm("tlm-1")])
    data = swd.encode(cell)
    store.write(2632, 1170, data, {"alti": "alti-new", "tlm": "tlm-1"})
    stats = rebuild.rebuild(cfg, [(2632, 1170)], builders=[Terrain(), Tlm("tlm-2")])
    assert stats["failed"] == ["2632_1170"] and stats["cells"] == 0
    assert store.path(2632, 1170).read_bytes() == data
    assert store.manifest.sources(2632, 1170)["tlm"] == "tlm-1"
