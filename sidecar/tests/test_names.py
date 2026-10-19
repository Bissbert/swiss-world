"""swissNAMES3D gazetteer: parsing (defensive), ranking, multilingual names, /place. Fixture: tests/fixtures/swissnames3d."""

import subprocess
import sys
import zipfile
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient

from swissdata import build, coverage, names, server
from swissdata.config import Config

FIXTURE = Path(__file__).parent / "fixtures" / "swissnames3d"


def make_zip(path: Path, members=names.MEMBERS) -> Path:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for m in members:
            z.write(FIXTURE / m, m)
    return path


@pytest.fixture
def places(tmp_path):
    return names.parse_zip(make_zip(tmp_path / "swissnames3d_2026_2056.csv.zip"))


@pytest.fixture
def index(places):
    return names.PlaceIndex(places, "swissnames3d_2026")


def top(index, q, **kw):
    r = index.search(q, **kw)
    return (r[0]["name"], r[0]["kind"]) if r else None


def test_normalize():
    assert names.normalize("Zürich") == "zurich"
    assert names.normalize("Genève") == "geneve"
    assert names.normalize("  St. Gallen ") == "st gallen"
    assert names.normalize("Biel/Bienne") == "biel bienne"
    assert names.normalize("GROSSE Scheidegg") == names.normalize("Grosse  Scheidegg")


def test_parse_selects_and_groups(places):
    by = {(p.name, p.kind) for p in places}
    assert ("Zürich", "city") in by and ("Zofingen", "town") in by and ("Interlaken", "town") in by
    assert ("Biel", "hamlet") in by and ("Grimselstrasse", "quarter") in by and ("Zürichsee", "lake") in by
    # one entry per object: Genève with its exonyms, Zürich only from the PKT centre (PLY polygon skipped)
    ge = [p for p in places if p.name == "Genève"]
    assert len(ge) == 1 and set(ge[0].aliases) == {"Genf", "Ginevra"}
    assert len([p for p in places if p.name == "Zürich"]) == 1
    biel = next(p for p in places if p.name == "Biel/Bienne")
    assert biel.kind == "city" and {"Biel", "Bienne"} <= set(biel.aliases)
    # bus stops, motorway exits, field names, airport areas, malformed rows are left out
    kinds = {p.kind for p in places}
    assert not kinds & {"Haltestelle Bus", "Ausfahrt"}
    assert sum(1 for p in places if p.name == "Zofingen") == 2  # town + station, not the bus stop
    assert not any(p.name in ("Nowhere", "Garbage", "short") for p in places)
    assert next(p for p in places if p.name == "Höhenweg").h is None  # implausible height dropped
    gp = next(p for p in places if p.name == "Grimselpass")
    assert (gp.kind, gp.h) == ("pass", 2163)


def test_search_accents_case_languages(index):
    assert top(index, "zurich") == ("Zürich", "city")
    assert top(index, "ZÜRICH") == ("Zürich", "city")
    assert top(index, "Geneve") == ("Genève", "city")
    r = index.search("Genf")
    assert r[0]["name"] == "Genève" and r[0]["matched"] == "Genf"
    assert top(index, "ginev") == ("Genève", "city")
    assert top(index, "Bienne") == ("Biel/Bienne", "city")


def test_search_ranking(index):
    # the city beats the exact-name hamlet, both rank before prefix matches
    r = index.search("Biel")
    assert [x["name"] for x in r[:2]] == ["Biel/Bienne", "Biel"] and r[0]["matched"] == "Biel"
    # settlements before other kinds
    assert [x["kind"] for x in index.search("Zofingen")] == ["town", "station"]
    assert [x["name"] for x in index.search("Interlaken")] == ["Interlaken", "Interlaken Ost"]
    # prefix: the pass beats the lake and the Zürich quarter; the road-pass twin and the second lake part collapse
    r = index.search("Grimsel")
    assert [(x["name"], x["kind"]) for x in r] == [("Grimselpass", "pass"), ("Grimselsee", "lake"),
                                                   ("Grimselstrasse", "quarter")]
    assert r[0]["h"] == 2163 and (r[0]["e"], r[0]["n"]) == (2668880, 1157032)
    # bigger settlements first on a bare prefix
    assert [x["name"] for x in index.search("z", limit=3)] == ["Zürich", "Zofingen", "Zürichsee"]
    # a word inside the name
    assert top(index, "moritz") == ("St. Moritz", "town")
    assert top(index, "st moritz") == ("St. Moritz", "town")


def test_search_exact_mode_limits_and_garbage(index):
    assert [x["name"] for x in index.search("Grimsel", exact=True)] == []
    assert [x["name"] for x in index.search("grimselpass", exact=True)] == ["Grimselpass"]
    assert len(index.search("z", limit=1)) == 1
    assert len(index.search("z", limit=10_000)) <= names.MAX_LIMIT
    assert index.search("") == [] and index.search("  ./ ") == [] and index.search("xyzzy") == []
    assert index.search("z" * 10_000) == []


def test_index_roundtrip(places, tmp_path):
    p = tmp_path / "names" / names.INDEX_NAME
    names.write_index(places, p, "swissnames3d_2026")
    item, back = names.read_index(p)
    assert item == "swissnames3d_2026" and back == places
    assert not list(p.parent.glob("*.tmp"))


def test_zip_without_members_or_oversized_is_refused(tmp_path):
    with pytest.raises(KeyError):
        names.parse_zip(make_zip(tmp_path / "a.zip", members=("swissNAMES3D_PKT.csv",)))
    big = tmp_path / "b.zip"
    make_zip(big)
    old = names.MAX_MEMBER_BYTES
    names.MAX_MEMBER_BYTES = 100
    try:
        with pytest.raises(ValueError):
            names.parse_zip(big)
    finally:
        names.MAX_MEMBER_BYTES = old


def test_build_cli_runs_isolated(tmp_path):
    """The real build path: `python -I -m swissdata.names build` (no fetch with --zip), pins the item."""
    z = make_zip(tmp_path / "swissnames3d_2026_2056.csv.zip")
    env = {"SWISSDATA_RAW": str(tmp_path / "raw"), "SWISSDATA_STORE": str(tmp_path / "store"),
           "SWISSDATA_SCRATCH": str(tmp_path / "scratch"), "PATH": "/usr/bin:/bin"}
    (tmp_path / "store").mkdir()
    r = subprocess.run([sys.executable, "-I", "-m", "swissdata.names", "build", "--zip", str(z)],
                       cwd=tmp_path / "store", env=env, capture_output=True, text=True, timeout=120)
    assert r.returncode == 0, r.stderr
    item, places = names.read_index(tmp_path / "store" / "names" / names.INDEX_NAME)
    assert item == "swissnames3d_2026" and any(p.name == "Zürich" for p in places)
    assert '"swissnames3d_2026"' in (tmp_path / "store" / "pins.json").read_text()


def _app(tmp_path, gaz):
    cfg = Config(raw=tmp_path / "raw", store=tmp_path / "store", scratch=tmp_path / "scratch", max_downloads=1,
                 dev_bbox=False)
    g = np.zeros((coverage.HEIGHT, coverage.WIDTH), dtype="u1")
    return server.create_app(cfg, builders=[build.DefaultSurface()], grid=g, gazetteer=gaz(cfg))


def test_place_endpoint(tmp_path, places):
    def ready(cfg):
        g = names.Gazetteer(cfg)
        g.index = names.PlaceIndex(places, "swissnames3d_2026")
        return g

    with TestClient(_app(tmp_path, ready)) as c:
        r = c.get("/place", params={"q": "zurich", "limit": 2}).json()["results"]
        assert r[0] == {"name": "Zürich", "kind": "city", "e": 2683500, "n": 1247184, "h": 418}
        assert c.get("/place", params={"q": "Genf"}).json()["results"][0]["matched"] == "Genf"
        assert c.get("/place", params={"q": "Grimsel", "mode": "exact"}).json() == {"results": []}
        assert c.get("/place", params={"q": "x", "mode": "fuzzy"}).status_code == 400
        assert c.get("/place", params={"q": "x", "limit": 0}).status_code == 422
        assert c.get("/status").json()["versions"]["names"] == "swissnames3d_2026"


def test_place_endpoint_while_building(tmp_path):
    class Pending(names.Gazetteer):
        def ensure(self):  # never touches the network in tests
            self.calls = getattr(self, "calls", 0) + 1

    gaz = {}

    def pending(cfg):
        gaz["g"] = Pending(cfg)
        return gaz["g"]

    with TestClient(_app(tmp_path, pending)) as c:
        r = c.get("/place", params={"q": "Zofingen"})
        assert r.status_code == 503 and r.json()["retryAfterS"] > 0
    assert gaz["g"].calls == 1  # no index on disk: nothing at startup, a build on the first query


def test_gazetteer_loads_existing_index(tmp_path, places):
    cfg = Config(raw=tmp_path / "raw", store=tmp_path / "store", scratch=tmp_path / "scratch")
    names.write_index(places, names.index_path(cfg), "swissnames3d_2026")
    g = names.Gazetteer(cfg)
    g.ensure()
    for _ in range(100):
        if g.index is not None:
            break
        import time
        time.sleep(0.05)
    assert g.state() == "ready" and g.item == "swissnames3d_2026"
