import json
from pathlib import Path

import numpy as np

from swissdata import fixture, swd

FIX = Path(__file__).resolve().parents[2] / "fixtures"


def test_roundtrip_and_determinism():
    a = swd.encode(fixture.build())
    b = swd.encode(fixture.build())
    assert a == b
    cell = swd.decode(a)
    assert cell.border and len(cell.buildings) == 1


def test_golden_fixture_matches_expected():
    data = (FIX / "synthetic_2632_1170.swd").read_bytes()
    assert data == swd.encode(fixture.build()), "fixture is stale: run python -m swissdata.fixture"
    exp = json.loads((FIX / "synthetic_2632_1170.expected.json").read_text())
    cell = swd.decode(data)
    for n in exp["terrNodes"]:
        assert abs(float(swd.decode_height(cell.layers["TERR"][n["j"], n["i"]])) - n["h"]) < 1e-9
    for c in exp["columns"]:
        dx, dz = c["dx"], c["dz"]
        assert cell.layers["SURF"][dz, dx] == c["surf"]
        w = float(swd.decode_height(cell.layers["WLVL"][dz, dx]))
        assert (np.isnan(w) and c["wlvl"] is None) or abs(w - (c["wlvl"] or 0)) < 1e-9
        assert cell.layers["OCLS"][dz, dx] == c["ocls"]
        assert cell.layers["OFLG"][dz, dx] == c["oflg"]
        assert cell.layers["INSD"][dz, dx] == c["insd"]
    p = exp["spanProbe"]
    s = cell.spans[(p["dx"], p["dz"])][0]
    assert abs(float(swd.decode_height(s.top_h)) - p["top"]) < 1e-9
    assert cell.buildings[0].gid == int(exp["buildings"][0]["gid"])
