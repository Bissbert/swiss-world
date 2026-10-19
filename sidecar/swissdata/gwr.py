"""GWR (federal building register) attributes by EGID, from the BFS public extract.

Source: https://public.madd.bfs.admin.ch/<canton>.zip (housing-stat.ch "GWR public data", access level A),
regenerated daily. Licence: free use incl. commercial, attribution required
("Bundesamt für Statistik; Eidg. Gebäude- und Wohnungsregister").
The zip is reduced once to a sorted EGID table (GKAT, GKLAS, GBAUP, GASTW) cached in scratch.
"""

from __future__ import annotations

import csv
import io
import os
import threading
import zipfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .config import Config
from .fetch import fetch

URL = "https://public.madd.bfs.admin.ch/{canton}.zip"
CSV_NAME = "gebaeude_batiment_edificio.csv"
CANTONS = ("be",)  # the test bbox lies in canton BE; extend (or switch to ch.zip) for the whole country
_CACHE_VERSION = 1


@dataclass
class GwrTable:
    egid: np.ndarray  # i8, sorted
    category: np.ndarray  # u2 GKAT
    klass: np.ndarray  # u2 GKLAS
    period: np.ndarray  # u2 GBAUP
    floors: np.ndarray  # u1 GASTW (clipped to 255)
    snapshot: str  # export date of the extract (GEXPDAT), e.g. "2026-10-19"

    def lookup(self, egids: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
        """-> (found, GKAT, GKLAS, GBAUP, GASTW); zeros where the EGID is unknown."""
        egids = np.asarray(egids, dtype=np.int64)
        if len(self.egid) == 0:
            pos = np.zeros(len(egids), dtype=np.int64)
            found = np.zeros(len(egids), dtype=bool)
        else:
            pos = np.clip(np.searchsorted(self.egid, egids), 0, len(self.egid) - 1)
            found = (self.egid[pos] == egids) & (egids > 0)

        def pick(a):
            return np.where(found, a[pos] if len(a) else 0, 0).astype(a.dtype)

        return found, pick(self.category), pick(self.klass), pick(self.period), pick(self.floors)


def _int(s: str) -> int:
    try:
        return int(s)
    except ValueError:
        return 0


def parse_csv(text: io.TextIOBase) -> GwrTable:
    reader = csv.reader(text, delimiter="\t")
    header = next(reader)
    col = {name: i for i, name in enumerate(header)}
    ie, ik, icl, ip, ifl, ix = (col[c] for c in ("EGID", "GKAT", "GKLAS", "GBAUP", "GASTW", "GEXPDAT"))
    rows, snapshot = [], ""
    for r in reader:
        egid = _int(r[ie])
        if egid <= 0:
            continue
        rows.append((egid, _int(r[ik]), _int(r[icl]), _int(r[ip]), min(_int(r[ifl]), 255)))
        snapshot = max(snapshot, r[ix])
    a = np.array(rows, dtype=np.int64).reshape(-1, 5)
    a = a[np.argsort(a[:, 0], kind="stable")]
    return GwrTable(a[:, 0], a[:, 1].astype("u2"), a[:, 2].astype("u2"), a[:, 3].astype("u2"),
                    a[:, 4].astype("u1"), snapshot)


def _cache_path(cfg: Config, canton: str) -> Path:
    return cfg.scratch / "gwr" / f"{canton}.v{_CACHE_VERSION}.npz"


def load_canton(cfg: Config, canton: str) -> GwrTable:
    cache = _cache_path(cfg, canton)
    if cache.exists():
        z = np.load(cache)
        return GwrTable(z["egid"], z["category"], z["klass"], z["period"], z["floors"], str(z["snapshot"]))
    with zipfile.ZipFile(fetch(cfg, URL.format(canton=canton))) as zf, zf.open(CSV_NAME) as f:
        table = parse_csv(io.TextIOWrapper(f, encoding="utf-8", newline=""))
    cache.parent.mkdir(parents=True, exist_ok=True)
    tmp = cache.with_name(f"{cache.name}.{os.getpid()}-{threading.get_ident()}.tmp.npz")  # workers may race
    np.savez(tmp, egid=table.egid, category=table.category, klass=table.klass, period=table.period,
             floors=table.floors, snapshot=np.array(table.snapshot))
    os.replace(tmp, cache)
    return table


def load(cfg: Config, cantons: tuple[str, ...] = CANTONS) -> GwrTable:
    """Merged table of all `cantons` (EGIDs are unique nationally)."""
    tables = [load_canton(cfg, c) for c in cantons]
    egid = np.concatenate([t.egid for t in tables])
    order = np.argsort(egid, kind="stable")

    def cat(name):
        return np.concatenate([getattr(t, name) for t in tables])[order]

    return GwrTable(egid[order], cat("category"), cat("klass"), cat("period"), cat("floors"),
                    min(t.snapshot for t in tables))


def source_id(table: GwrTable, cantons: tuple[str, ...] = CANTONS) -> str:
    return f"gwr_{'+'.join(cantons)}_{table.snapshot}"
