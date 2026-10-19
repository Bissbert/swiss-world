"""swissNAMES3D gazetteer for `/swiss goto <place>` (GET /place, docs/sidecar-api.md). Owner: sidecar-core.

    python -I -m swissdata.names build [--zip PATH]     # fetch once into $SWISSDATA_RAW, write the index
    python -I -m swissdata.names search zurich           # query the index from the shell

Source: STAC collection `ch.swisstopo.swissnames3d`, newest item, asset `<item>_2056.csv.zip` (~33 MB, three
`;`-separated CSVs in UTF-8 with BOM: PKT, PLY, LIN). Licence: swisstopo open-government data, "© swisstopo".

The zip is untrusted input: only the two known members are opened (never extracted), their declared and actual
sizes are capped, every row is validated (name length, control characters, LV95 bounds) and a bad row is skipped.
The server parses it in a `python -I` subprocess (see `Gazetteer.ensure`).

Index (`<store>/names/places.v1.tsv.gz`, a few MB): one line per place object, the first line a `#` header with
the pinned item id. Columns: display name, aliases (`|`-joined), kind, tier, size, E, N, height (may be empty).

Selection:
- Settlements are PKT `TLM_SIEDLUNGSNAME_ZENTRUM` points (centre of the place). The PLY polygons carry the same
  settlements again and are skipped. Ort/Ortsteil are tier 0 (sized by EINWOHNERKATEGORIE); Quartier and
  Quartierteil are city quarters (tier 3): "Grimselstrasse" in Zürich must not beat Grimselpass.
- Peaks and passes (tier 1), lakes, glaciers, valleys, massifs (tier 2, from PLY), stations (tier 4), other named
  places (tier 5). Bus stops, motorway exits, field names and land-use areas are left out.
- Names of one object (same UUID) collapse into one entry: Genève (Endonym) + Genf, Ginevra (Exonyms); the
  official endonym is shown. A `Namenspaar` "Biel/Bienne" also gets "Biel" and "Bienne" as aliases.

Search is accent-, case- and punctuation-insensitive ("zurich" -> Zürich, "st gallen" -> St. Gallen).
Ranking: exact match, then prefix, then a word inside the name; within that tier, size, shorter name.
Near-duplicates (same display name and tier within 1.5 km, e.g. Grimselpass as pass and road pass) are dropped.
"""

from __future__ import annotations

import argparse
import bisect
import csv
import gzip
import io
import logging
import math
import os
import re
import subprocess
import sys
import threading
import time
import unicodedata
import zipfile
from dataclasses import dataclass
from pathlib import Path

from .config import Config

log = logging.getLogger(__name__)

COLLECTION = "ch.swisstopo.swissnames3d"
INDEX_NAME = "places.v1.tsv.gz"
MEMBERS = ("swissNAMES3D_PKT.csv", "swissNAMES3D_PLY.csv")
MAX_MEMBER_BYTES = 400_000_000  # PKT is 82 MB uncompressed; anything far beyond that is not swissNAMES3D
MAX_NAME = 120
MAX_QUERY = 64
MAX_LIMIT = 25
E_RANGE, N_RANGE = (2_480_000, 2_840_000), (1_070_000, 1_300_000)

# (file, OBJEKTART) -> (kind, tier, weight). Smaller tier ranks first; weight orders kinds within a tier.
SETTLEMENT = "TLM_SIEDLUNGSNAME_ZENTRUM"
KINDS: dict[tuple[str, str], tuple[str, int, int]] = {
    ("PKT", "Hauptgipfel"): ("peak", 1, 30),
    ("PKT", "Alpiner Gipfel"): ("peak", 1, 30),
    ("PKT", "Pass"): ("pass", 1, 30),
    ("PKT", "Strassenpass"): ("pass", 1, 30),
    ("PKT", "Gipfel"): ("peak", 1, 20),
    ("PKT", "Haupthuegel"): ("hill", 1, 10),
    ("PKT", "Huegel"): ("hill", 1, 0),
    ("PKT", "Felskopf"): ("peak", 1, 0),
    ("PLY", "See"): ("lake", 2, 30),
    ("PLY", "Gletscher"): ("glacier", 2, 20),
    ("PLY", "Haupttal"): ("valley", 2, 20),
    ("PLY", "Massiv"): ("massif", 2, 10),
    ("PLY", "Tal"): ("valley", 2, 0),
    ("PLY", "Seeinsel"): ("island", 2, 0),
    ("PKT", "Haltestelle Bahn"): ("station", 4, 30),
    ("PKT", "Haltestelle Schiff"): ("pier", 4, 20),
    ("PKT", "Uebrige Bahnen"): ("cable car", 4, 10),
    ("PKT", "Aussichtspunkt"): ("viewpoint", 5, 20),
    ("PKT", "Wasserfall"): ("waterfall", 5, 20),
    ("PKT", "Grotte, Hoehle"): ("cave", 5, 20),
    ("PLY", "Staumauer"): ("dam", 5, 20),
    ("PLY", "Staudamm"): ("dam", 5, 20),
    ("PLY", "Historisches Areal"): ("historic site", 5, 20),
    ("PLY", "Klosterareal"): ("monastery", 5, 20),
    ("PLY", "Zooareal"): ("zoo", 5, 10),
    ("PLY", "Landschaftsname"): ("region", 5, 10),
    ("PLY", "Gebiet"): ("area", 5, 0),
    ("PKT", "Gebaeude"): ("building", 5, 0),
    ("PKT", "Offenes Gebaeude"): ("building", 5, 0),
    ("PKT", "Sakrales Gebaeude"): ("church", 5, 0),
    ("PKT", "Kapelle"): ("chapel", 5, 0),
    ("PKT", "Turm"): ("tower", 5, 0),
    ("PKT", "Denkmal"): ("monument", 5, 0),
    ("PKT", "Erratischer Block"): ("boulder", 5, 0),
}

# EINWOHNERKATEGORIE -> representative population (orders settlements; also picks city/town/village/hamlet)
POPULATION = {
    "< 20": 10, "20 bis 49": 35, "50 bis 99": 75, "100 bis 999": 500, "1'000 bis 1'999": 1500,
    "2'000 bis 9'999": 5000, "10'000 bis 49'999": 25000, "50'000 bis 100'000": 75000, "> 100'000": 200000,
}


def settlement_kind(objektart: str, pop: int) -> tuple[str, int] | None:
    """(kind, tier) of a settlement-centre point; None for unknown types."""
    if objektart in ("Ort", "Ortsteil"):
        if pop >= 50_000:
            return "city", 0
        if pop >= 2_000:
            return "town", 0
        if pop >= 100:
            return "village", 0
        return ("hamlet" if objektart == "Ort" else "district"), 0
    if objektart in ("Quartier", "Quartierteil"):
        return "quarter", 3
    return None


_PUNCT = re.compile(r"[^0-9a-z]+")
_CTRL = re.compile(r"[\x00-\x1f\x7f]")


def normalize(s: str) -> str:
    """Accent-, case- and punctuation-insensitive key: "St. Gallen" -> "st gallen", "Genève" -> "geneve"."""
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch)).casefold()
    s = s.replace("ß", "ss")
    return _PUNCT.sub(" ", s).strip()


@dataclass
class Place:
    name: str
    aliases: tuple[str, ...]
    kind: str
    tier: int
    size: int  # population for settlements, kind weight otherwise
    e: int
    n: int
    h: int | None

    def to_json(self, matched: str | None = None) -> dict:
        out = {"name": self.name, "kind": self.kind, "e": self.e, "n": self.n, "h": self.h}
        if matched and matched != self.name:
            out["matched"] = matched
        return out


# --- building the index from the zip ------------------------------------------------------------------------

def _clean_name(s: str | None) -> str | None:
    if not s:
        return None
    s = _CTRL.sub("", s).strip()
    if not s or len(s) > MAX_NAME or s in ("k_W", "-"):
        return None
    return s


def _int(s: str | None) -> int | None:
    try:
        v = float(s)  # Z has decimals in some rows
    except (TypeError, ValueError):
        return None
    return int(round(v)) if math.isfinite(v) else None


_NAME_PRIORITY = {"Endonym": 0, "Namenspaar": 0, "einfacher Name": 1, "Exonym": 3}
_STATUS_PRIORITY = {"offiziell": 0, "ueblich": 1, "informell": 2}


def _rows(z: zipfile.ZipFile, member: str):
    info = z.getinfo(member)  # KeyError if missing
    if info.file_size > MAX_MEMBER_BYTES:
        raise ValueError(f"{member}: {info.file_size} bytes declared, refusing")
    csv.field_size_limit(10_000)
    with z.open(info) as raw:
        limited = _Capped(raw, MAX_MEMBER_BYTES)
        text = io.TextIOWrapper(limited, encoding="utf-8-sig", errors="replace", newline="")
        yield from csv.DictReader(text, delimiter=";")


class _Capped(io.RawIOBase):
    """Fails a read past `cap` bytes, whatever the zip header claimed."""

    def __init__(self, f, cap: int):
        self.f, self.left = f, cap

    def readable(self) -> bool:
        return True

    def readinto(self, b) -> int:
        data = self.f.read(min(len(b), self.left + 1))
        if len(data) > self.left:
            raise ValueError("zip member larger than allowed")
        self.left -= len(data)
        b[:len(data)] = data
        return len(data)


def parse_zip(path: Path) -> list[Place]:
    """Places from a swissNAMES3D CSV zip. Malformed rows are skipped, a structurally wrong zip raises."""
    groups: dict[str, dict] = {}  # object UUID -> {"names": [(prio, name)], "kind": ..., ...}
    skipped = 0
    with zipfile.ZipFile(path) as z:
        for member in MEMBERS:
            src = "PKT" if "_PKT" in member else "PLY"
            for row in _rows(z, member):
                try:
                    art = row.get("OBJEKTART") or ""
                    if src == "PKT" and row.get("OBJEKTKLASSE_TLM") == SETTLEMENT:
                        pop = POPULATION.get(row.get("EINWOHNERKATEGORIE") or "", 0)
                        sk = settlement_kind(art, pop)
                        if sk is None:
                            continue
                        kind, tier, size = sk[0], sk[1], pop
                    elif (src, art) in KINDS:
                        kind, tier, size = KINDS[(src, art)]
                    else:
                        continue
                    name = _clean_name(row.get("NAME"))
                    e, n = _int(row.get("E")), _int(row.get("N"))
                    if name is None or e is None or n is None or not (E_RANGE[0] <= e <= E_RANGE[1]) \
                            or not (N_RANGE[0] <= n <= N_RANGE[1]):
                        skipped += 1
                        continue
                    h = _int(row.get("Z"))
                    if h is not None and not (-100 <= h <= 5000):
                        h = None
                    uid = (row.get("UUID") or "")[:64] or f"{src}:{e}:{n}:{name}"
                    g = groups.setdefault(f"{src}{uid}", {"names": [], "kind": kind, "tier": tier, "size": size,
                                                          "e": e, "n": n, "h": h})
                    prio = (_STATUS_PRIORITY.get(row.get("STATUS") or "", 3),
                            _NAME_PRIORITY.get(row.get("NAMEN_TYP") or "", 2))
                    g["names"].append((prio, name, row.get("NAMEN_TYP") == "Namenspaar"))
                except (AttributeError, TypeError, ValueError):
                    skipped += 1
    places = []
    for g in groups.values():
        names = sorted(set(g["names"]))
        display = names[0][1]
        aliases: list[str] = []
        for _, nm, pair in names:  # a Namenspaar "Biel/Bienne" is also found as "Biel" and as "Bienne"
            for a in [nm, *(p.strip() for p in nm.split("/") if pair)]:
                if a and a != display and a not in aliases:
                    aliases.append(a)
        places.append(Place(display, tuple(aliases), g["kind"], g["tier"], g["size"], g["e"], g["n"], g["h"]))
    places.sort(key=lambda p: (p.tier, -p.size, p.name, p.e, p.n))
    log.info("swissNAMES3D: %d places (%d rows skipped)", len(places), skipped)
    return places


def write_index(places: list[Place], path: Path, item: str) -> None:
    """Atomic: temp file in the same directory, then rename."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    with gzip.open(tmp, "wt", encoding="utf-8", newline="\n") as f:
        f.write(f"#swissnames3d\t{item}\t{len(places)}\n")
        for p in places:
            f.write("\t".join([p.name, "|".join(p.aliases), p.kind, str(p.tier), str(p.size), str(p.e), str(p.n),
                               "" if p.h is None else str(p.h)]) + "\n")
    os.replace(tmp, path)


def read_index(path: Path) -> tuple[str, list[Place]]:
    places = []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        if len(header) < 2 or header[0] != "#swissnames3d":
            raise ValueError(f"{path}: not a place index")
        for line in f:
            c = line.rstrip("\n").split("\t")
            if len(c) != 8:
                continue
            places.append(Place(c[0], tuple(a for a in c[1].split("|") if a), c[2], int(c[3]), int(c[4]),
                                int(c[5]), int(c[6]), int(c[7]) if c[7] else None))
    return header[1], places


# --- search ---------------------------------------------------------------------------------------------------

EXACT, PREFIX, WORD = 0, 1, 2


class PlaceIndex:
    """Sorted normalized keys with bisect for prefix lookup. ~100k places, ~250k keys, a few ms per query."""

    def __init__(self, places: list[Place], item: str = ""):
        self.places, self.item = places, item
        keys: list[tuple[str, int, int, str]] = []  # (key, word?, place index, the spelling it came from)
        for i, p in enumerate(places):
            seen = set()
            for spelling in (p.name, *p.aliases):
                k = normalize(spelling)
                if not k or k in seen:
                    continue
                seen.add(k)
                keys.append((k, 0, i, spelling))
                words = k.split(" ")
                for w in range(1, len(words)):  # "st moritz" also under "moritz"
                    sub = " ".join(words[w:])
                    if len(sub) >= 3 and sub not in seen:
                        keys.append((sub, 1, i, spelling))
        keys.sort()
        self._keys = keys
        self._k = [k[0] for k in keys]

    def __len__(self) -> int:
        return len(self.places)

    def search(self, q: str, limit: int = 10, exact: bool = False) -> list[dict]:
        nq = normalize(q[:MAX_QUERY])
        limit = max(1, min(MAX_LIMIT, limit))
        if not nq:
            return []
        best: dict[int, tuple[int, str]] = {}  # place -> (match class, spelling)
        lo = bisect.bisect_left(self._k, nq)
        for j in range(lo, len(self._k)):
            k, word, i, spelling = self._keys[j]
            if not k.startswith(nq):
                break
            if k == nq:
                cls = WORD if word else EXACT
            elif exact:
                continue
            else:
                cls = WORD if word else PREFIX
            if i not in best or (cls, spelling != self.places[i].name) < (best[i][0], best[i][1] != self.places[i].name):
                best[i] = (cls, spelling)
        ranked = sorted(best.items(), key=lambda it: (it[1][0], self.places[it[0]].tier, -self.places[it[0]].size,
                                                      len(self.places[it[0]].name), self.places[it[0]].name, it[0]))
        out: list[dict] = []
        kept: list[Place] = []
        for i, (cls, spelling) in ranked:
            p = self.places[i]
            near = 5000 if p.tier == 2 else 1500  # a lake's or glacier's parts lie far apart
            if any(k.name == p.name and k.tier == p.tier and abs(k.e - p.e) + abs(k.n - p.n) < near for k in kept):
                continue
            kept.append(p)
            out.append(p.to_json(spelling))
            if len(out) >= limit:
                break
        return out


# --- lifecycle in the server ------------------------------------------------------------------------------------

def index_path(cfg: Config) -> Path:
    return cfg.store / "names" / INDEX_NAME


def latest_asset(item_id: str) -> str:
    from .stac import ASSETS
    return f"{ASSETS}/{COLLECTION}/{item_id}/{item_id}_2056.csv.zip"


def build(cfg: Config, zip_path: Path | None = None) -> Path:
    """Fetches the pinned (else newest) item once into raw and writes the index. Run as `python -I -m`."""
    from .fetch import fetch
    from .manifest import Pins

    pins = Pins(cfg.store)
    if zip_path is None:
        from .stac import latest_item_id
        item = pins.pin("names", "ch", lambda: latest_item_id(COLLECTION))
        zip_path = fetch(cfg, latest_asset(item))
    else:
        item = pins.pin("names", "ch", lambda: zip_path.name.removesuffix("_2056.csv.zip"))
    places = parse_zip(zip_path)
    if len(places) < 1000 and zip_path.stat().st_size > 1_000_000:
        raise ValueError(f"only {len(places)} places parsed from {zip_path}; refusing to write the index")
    out = index_path(cfg)
    write_index(places, out, item)
    return out


class Gazetteer:
    """Loads the index; if it is missing, builds it once in a `python -I` subprocess (the zip is untrusted)."""

    RETRY_S = 300

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.index: PlaceIndex | None = None
        self.error: str | None = None
        self._lock = threading.Lock()
        self._building = False
        self._failed_at = 0.0

    @property
    def item(self) -> str | None:
        return self.index.item if self.index else None

    def ensure(self) -> None:
        """Non-blocking: starts loading/building in the background unless done, running, or recently failed."""
        with self._lock:
            if self.index is not None or self._building or time.monotonic() - self._failed_at < self.RETRY_S \
                    and self._failed_at:
                return
            self._building = True
        threading.Thread(target=self._load, name="gazetteer", daemon=True).start()

    def _load(self) -> None:
        try:
            path = index_path(self.cfg)
            if not path.exists():
                self.cfg.store.mkdir(parents=True, exist_ok=True)
                log.info("building the place index (swissNAMES3D) in a subprocess")
                r = subprocess.run([sys.executable, "-I", "-m", "swissdata.names", "build"],
                                   cwd=self.cfg.store, env=_child_env(self.cfg), capture_output=True, text=True,
                                   timeout=1800)
                if r.returncode != 0:
                    raise RuntimeError(f"index build failed: {(r.stderr or r.stdout).strip()[-400:]}")
            item, places = read_index(path)
            idx = PlaceIndex(places, item)
            with self._lock:
                self.index, self.error = idx, None
            log.info("place index: %d places from %s", len(idx), item)
        except Exception as ex:  # keep serving cells; /place answers 503 with the error
            log.exception("place index unavailable")
            with self._lock:
                self.error, self._failed_at = f"{type(ex).__name__}: {ex}"[:300], time.monotonic()
        finally:
            with self._lock:
                self._building = False

    def state(self) -> str:
        with self._lock:
            return "ready" if self.index else "building" if self._building else "error" if self.error else "idle"


def _child_env(cfg: Config) -> dict:
    env = {k: v for k, v in os.environ.items() if not k.startswith("PYTHON")}
    env.update(SWISSDATA_RAW=str(cfg.raw), SWISSDATA_STORE=str(cfg.store), SWISSDATA_SCRATCH=str(cfg.scratch))
    return env


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -I -m swissdata.names")
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--zip", type=Path, help="use this swissNAMES3D CSV zip instead of fetching")
    s = sub.add_parser("search")
    s.add_argument("q")
    s.add_argument("--limit", type=int, default=10)
    s.add_argument("--exact", action="store_true")
    a = ap.parse_args(argv)
    logging.basicConfig(level=os.environ.get("SWISSDATA_LOG", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = Config.from_env()
    if a.cmd == "build":
        out = build(cfg, a.zip)
        print(f"{out} ({out.stat().st_size} bytes)")
    else:
        item, places = read_index(index_path(cfg))
        for r in PlaceIndex(places, item).search(a.q, a.limit, a.exact):
            print(r)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
