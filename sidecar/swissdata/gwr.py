"""GWR (federal building register) attributes by EGID, from the BFS public extract.

Source: https://public.madd.bfs.admin.ch/<canton>.zip (housing-stat.ch "GWR public data", access level A),
regenerated daily. Licence: free use incl. commercial, attribution required
("Bundesamt für Statistik; Eidg. Gebäude- und Wohnungsregister").
The zip is reduced once to a sorted EGID table (GKAT, GKLAS, GBAUP, GASTW) cached in scratch.

Old snapshots cannot be re-downloaded, so every snapshot used is archived for good as
`<store>/gwr/<source id>.npz` (~7 MB per canton). The manifest pin `gwr` is soft: a pinned snapshot is used while its
archive exists; otherwise the newest archived snapshot is used and re-pinned, with a warning (`load_pinned`).
"""

from __future__ import annotations

import csv
import io
import logging
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

log = logging.getLogger(__name__)
_SNAPSHOTS: dict[Path, GwrTable] = {}  # archived snapshots never change, so one read per process


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


def _read(path: Path) -> GwrTable:
    z = np.load(path)
    return GwrTable(z["egid"], z["category"], z["klass"], z["period"], z["floors"], str(z["snapshot"]))


def _write(path: Path, table: GwrTable) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.{os.getpid()}-{threading.get_ident()}.tmp.npz")  # workers may race
    np.savez(tmp, egid=table.egid, category=table.category, klass=table.klass, period=table.period,
             floors=table.floors, snapshot=np.array(table.snapshot))
    os.replace(tmp, path)


def load_canton(cfg: Config, canton: str) -> GwrTable:
    cache = _cache_path(cfg, canton)
    if cache.exists():
        return _read(cache)
    with zipfile.ZipFile(fetch(cfg, URL.format(canton=canton))) as zf, zf.open(CSV_NAME) as f:
        table = parse_csv(io.TextIOWrapper(f, encoding="utf-8", newline=""))
    _write(cache, table)
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


def snapshot_path(cfg: Config, sid: str) -> Path:
    return cfg.store / "gwr" / f"{sid}.npz"


def snapshots(cfg: Config, cantons: tuple[str, ...] = CANTONS) -> list[str]:
    """Archived snapshot ids of `cantons`, oldest first (the ids end in the ISO export date)."""
    prefix = f"gwr_{'+'.join(cantons)}_"
    # skip _write's temp files (…npz.<pid>-<tid>.tmp.npz): a racing worker would read a half-written archive
    return sorted(p.stem for p in (cfg.store / "gwr").glob(f"{prefix}*.npz")
                  if p.stem[len(prefix):][:1].isdigit() and not p.name.endswith(".tmp.npz"))


def _snapshot(cfg: Config, sid: str) -> GwrTable:
    path = snapshot_path(cfg, sid)
    if path not in _SNAPSHOTS:
        _SNAPSHOTS[path] = _read(path)
    return _SNAPSHOTS[path]


def load_pinned(cfg: Config, pinned: str | None = None, cantons: tuple[str, ...] = CANTONS) -> tuple[GwrTable, str]:
    """-> (table, source id). Soft pin: the pinned snapshot while its archive exists, else the newest one."""
    if pinned and snapshot_path(cfg, pinned).exists():
        return _snapshot(cfg, pinned), pinned
    current = load(cfg, cantons)
    sid = source_id(current, cantons)
    if not snapshot_path(cfg, sid).exists():
        _write(snapshot_path(cfg, sid), current)
    newest = snapshots(cfg, cantons)[-1]
    if pinned:
        log.warning("GWR snapshot %s is not archived; using %s and re-pinning", pinned, newest)
    return _snapshot(cfg, newest), newest
