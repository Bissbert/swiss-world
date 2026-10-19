import threading

import numpy as np
import pytest
from fastapi.testclient import TestClient

from swissdata import build, coverage, server, swd
from swissdata.config import Config

E, N = 2631, 1170


class GatedTerrain:
    """Builds flat TERR; blocks until `gate` is set, records build order, fails for cells in `fail`."""

    name = "gated"
    tags = ("TERR",)

    def __init__(self):
        self.gate = threading.Event()
        self.started = threading.Event()
        self.order = []
        self.fail = set()

    def build(self, e_km, n_km, cfg, partial, pinned):
        self.order.append((e_km, n_km))
        self.started.set()
        self.gate.wait(10)
        if (e_km, n_km) in self.fail:
            raise RuntimeError("upstream down")
        return build.LayerResult(layers={"TERR": swd.encode_height(np.full((swd.TERR_N, swd.TERR_N), 600.0))},
                                 sources={"alti": f"item-{e_km}-{n_km}"})


def grid_inside():
    g = np.zeros((coverage.HEIGHT, coverage.WIDTH), dtype="u1")
    g[N - 10 - coverage.N_MIN:N + 10 - coverage.N_MIN, E - 10 - coverage.E_MIN:E + 10 - coverage.E_MIN] = coverage.INSIDE
    return g


@pytest.fixture
def setup(tmp_path):
    def make(dev_bbox=False):
        cfg = Config(raw=tmp_path / "raw", store=tmp_path / "store", scratch=tmp_path / "scratch",
                     max_downloads=1, dev_bbox=dev_bbox)
        terr = GatedTerrain()
        app = server.create_app(cfg, builders=[terr, build.DefaultSurface()], grid=grid_inside())
        return cfg, terr, app
    return make


def states(client, cells, priority="now"):
    r = client.post("/request", json={"cells": cells, "priority": priority})
    assert r.status_code == 200
    return {tuple(c["cell"]): c for c in r.json()["cells"]}


def test_request_builds_cell_and_wait_returns_ready(setup):
    cfg, terr, app = setup()
    terr.gate.set()
    with TestClient(app) as client:
        assert client.get("/health").json() == {"ok": True, "version": "0.1.0", "formatVersion": 1}
        assert states(client, [[E, N]])[(E, N)]["state"] in ("queued", "building", "ready")
        assert client.get("/wait", params={"cell": f"{E},{N}", "timeoutMs": 5000}).json()["state"] == "ready"
        cell = swd.decode(swd.cell_path(cfg.store, E, N).read_bytes())
        assert cell.layers["TERR"].shape == (swd.TERR_N, swd.TERR_N)
        assert states(client, [[E, N]])[(E, N)]["state"] == "ready"
        assert terr.order == [(E, N)]


def test_outside_cells(setup):
    _, terr, app = setup()
    with TestClient(app) as client:
        assert states(client, [[2490, 1300], [E + 50, N]])[(2490, 1300)]["state"] == "outside"
        assert client.get("/wait", params={"cell": f"{E + 50},{N}", "timeoutMs": 0}).json()["state"] == "outside"
    assert terr.order == []


def test_now_overtakes_prefetch_and_upgrade_dedups(setup):
    _, terr, app = setup()
    with TestClient(app) as client:
        states(client, [[E, N]])
        assert terr.started.wait(5)  # the only worker is now blocked on (E, N)
        s = states(client, [[E + 1, N], [E + 2, N], [E + 3, N]], priority="prefetch")
        assert [s[(E + i, N)]["position"] for i in (1, 2, 3)] == [0, 1, 2]
        s = states(client, [[E + 3, N], [E + 4, N]], priority="now")
        assert s[(E + 3, N)]["position"] == 0 and s[(E + 4, N)]["position"] == 1
        assert states(client, [[E + 1, N]], priority="now")[(E + 1, N)]["position"] == 2  # upgraded, behind now
        assert states(client, [[E + 2, N]], priority="prefetch")[(E + 2, N)]["position"] == 3  # dedup
        assert client.get("/status").json()["queue"] == {"now": 3, "prefetch": 1, "building": 1}
        assert states(client, [[E + 2, N]])[(E + 2, N)]["position"] == 3  # upgraded, still last
        terr.gate.set()
        for i in range(5):
            client.get("/wait", params={"cell": f"{E + i},{N}", "timeoutMs": 5000})
    assert terr.order == [(E, N), (E + 3, N), (E + 4, N), (E + 1, N), (E + 2, N)]


def test_failed_cell_reports_error_with_retry(setup):
    _, terr, app = setup()
    terr.fail.add((E, N))
    terr.gate.set()
    with TestClient(app) as client:
        states(client, [[E, N]])
        r = client.get("/wait", params={"cell": f"{E},{N}", "timeoutMs": 5000}).json()
        assert r["state"] == "error" and "upstream down" in r["error"] and 0 < r["retryAfterS"] <= 30
        assert states(client, [[E, N]])[(E, N)]["state"] == "error"  # not re-queued before retryAfterS
    assert terr.order == [(E, N)]


def test_dev_bbox_guard(setup):
    _, terr, app = setup(dev_bbox=True)
    with TestClient(app) as client:
        r = states(client, [[E - 9, N]])[(E - 9, N)]  # 2622: inside CH (synthetic grid), outside the test bbox
        assert r["state"] == "error" and "dev test bbox" in r["error"]
    assert terr.order == []


def test_prefetch_generated_status_place(setup):
    _, terr, app = setup()
    terr.gate.set()
    with TestClient(app) as client:
        r = client.post("/prefetch", json={"e": E * 1000 + 500, "n": N * 1000 + 500, "radiusM": 400,
                                           "headingDeg": 90, "speedMps": 50})
        assert r.status_code == 202 and r.json()["queued"] == len(
            server.prefetch_cells(E * 1000 + 500, N * 1000 + 500, 400, 90, 50))
        client.get("/wait", params={"cell": f"{E + 3},{N}", "timeoutMs": 5000})
        assert client.post("/generated", json={"cells": [[E, N]]}).status_code == 204
        st = client.get("/status").json()
        assert set(st) == {"queue", "store", "scratch", "upstream", "versions"}
        assert st["store"]["capBytes"] == 12_000_000_000
        assert client.get("/place", params={"q": "Bern"}).status_code == 501
        assert client.get("/wait", params={"cell": "nope"}).status_code == 400
        assert client.post("/request", json={"cells": [[E, N]], "priority": "later"}).status_code == 400


def test_prefetch_corridor_follows_compass_heading():
    east = server.prefetch_cells(2631_500, 1170_500, 400, 90, 50)  # 3 km corridor eastwards
    assert (2634, 1170) in east and (2628, 1170) not in east and (2631, 1173) not in east
    north = server.prefetch_cells(2631_500, 1170_500, 400, 0, 50)
    assert (2631, 1173) in north and (2634, 1170) not in north
    still = server.prefetch_cells(2631_500, 1170_500, 400, 0, 0)
    assert still == [(2631, 1170)]
    assert east[0] == (2631, 1170)  # nearest first
