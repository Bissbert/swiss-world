import io
import logging

import numpy as np
import pytest

from swissdata import build, gwr
from swissdata.config import Config

TSV = ("EGID\tGDEKT\tGKAT\tGKLAS\tGBAUP\tGASTW\tGEXPDAT\n"
       "1350894\tBE\t1020\t1110\t8013\t2\t2026-10-19\n"
       "1346414\tBE\t1040\t1211\t8011\t6\t2026-10-19\n"
       "\tBE\t1020\t\t\t\t2026-10-19\n"
       "99\tBE\t1060\t\t\t300\t2026-10-19\n")


def test_parse_and_lookup():
    t = gwr.parse_csv(io.StringIO(TSV))
    assert t.egid.tolist() == [99, 1346414, 1350894] and t.snapshot == "2026-10-19"
    found, gkat, gklas, gbaup, gastw = t.lookup(np.array([1346414, 0, 5, 99]))
    assert found.tolist() == [True, False, False, True]
    assert (gkat[0], gklas[0], gbaup[0], gastw[0]) == (1040, 1211, 8011, 6)
    assert gklas[3] == 0 and gastw[3] == 255  # missing class, floors clipped to u8
    assert gwr.source_id(t) == "gwr_be_2026-10-19"


def table(snapshot):
    return gwr.GwrTable(np.array([7]), np.array([1020], "u2"), np.array([1110], "u2"), np.array([8013], "u2"),
                        np.array([2], "u1"), snapshot)


def test_soft_pin(monkeypatch, tmp_path, caplog):
    cfg = Config(raw=tmp_path / "raw", store=tmp_path / "store", scratch=tmp_path / "scratch")
    monkeypatch.setattr(gwr, "load", lambda *a: table("2026-10-19"))
    t, sid = gwr.load_pinned(cfg)
    assert sid == "gwr_be_2026-10-19" and gwr.snapshot_path(cfg, sid).exists() and t.klass.tolist() == [1110]
    # a newer extract arrives; the archived pin keeps being honoured without reading the current one
    monkeypatch.setattr(gwr, "load", lambda *a: table("2026-11-02"))
    assert gwr.load_pinned(cfg, "gwr_be_2026-10-19")[1] == "gwr_be_2026-10-19"
    # a pin whose archive is gone falls back to the newest snapshot, with a warning instead of an error
    with caplog.at_level(logging.WARNING, logger="swissdata.gwr"):
        assert gwr.load_pinned(cfg, "gwr_be_2026-01-05")[1] == "gwr_be_2026-11-02"
    assert "gwr_be_2026-01-05" in caplog.text
    assert gwr.snapshots(cfg) == ["gwr_be_2026-10-19", "gwr_be_2026-11-02"]
    # another worker's half-written temp archive is not a snapshot (it sorted last and was read: BadZipFile)
    (cfg.store / "gwr" / "gwr_be_2026-11-02.npz.1-2.tmp.npz").write_bytes(b"partial")
    assert gwr.snapshots(cfg) == ["gwr_be_2026-10-19", "gwr_be_2026-11-02"]


def test_merge_sources_soft_gwr_pin():
    old = {"buildings": ["b"], "gwr": "gwr_be_2026-01-05"}
    assert build.merge_sources(old, {"buildings": ["b"], "gwr": "gwr_be_2026-11-02"})["gwr"] == "gwr_be_2026-11-02"
    with pytest.raises(build.PinChanged):
        build.merge_sources(old, {"buildings": ["c"], "gwr": "gwr_be_2026-11-02"})
