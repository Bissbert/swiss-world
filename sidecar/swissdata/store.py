"""The cell store: atomic cell writes, readiness, size accounting and LRU eviction. Owner: sidecar-core.

A cell is *ready* only if its file exists **and** manifest.json has a live (non-evicted) entry for it; a
file without an entry (e.g. a stray fixture) is rebuilt over.

Eviction (when the cells exceed `store_cap_bytes`): cells reported via POST /generated go first, then the
rest; within each group the least recently requested/built first. Access times live in lru.json (sidecar-
internal), not in file atimes, which macOS does not keep reliably. lru.json is advisory, so it is written at
most every LRU_FLUSH_S (and on flush()); manifest.json is written per write()/write_many() call.

Cell files are replaced atomically (unique temp file in cells/, fsync, rename), so a reader such as the mod
sees either the old or the new cell, never a partial one.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from collections.abc import Iterable
from pathlib import Path

from . import swd
from .config import Config
from .manifest import JsonFile, Manifest, cell_key

log = logging.getLogger(__name__)

LRU_FLUSH_S = 10.0


def write_atomic(path: Path, data: bytes) -> None:
    """Like swd.write_atomic, but with a per-writer temp name: the server and a rebuild may write at once."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{os.getpid()}-{threading.get_ident()}.tmp")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


class Store:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.root = Path(cfg.store)
        self.manifest = Manifest(self.root)
        self._lru = JsonFile(self.root / "lru.json", lambda: {"access": {}, "generated": {}})
        self._lock = threading.Lock()
        self._sizes: dict[str, int] = {}
        self._access: dict[str, float] = dict(self._lru.read()["access"])
        self._generated: dict[str, float] = dict(self._lru.read()["generated"])
        self._persisted = float("-inf")
        cells = self.root / "cells"
        if cells.is_dir():
            for p in cells.glob("*.swd"):
                self._sizes[p.stem] = p.stat().st_size

    def path(self, e_km: int, n_km: int) -> Path:
        return swd.cell_path(self.root, e_km, n_km)

    def is_ready(self, e_km: int, n_km: int) -> bool:
        entry = self.manifest.entry(e_km, n_km)
        return entry is not None and "evicted" not in entry and self.path(e_km, n_km).exists()

    def touch(self, cells: Iterable[tuple[int, int]]) -> None:
        now = time.time()
        with self._lock:
            for e, n in cells:
                self._access[cell_key(e, n)] = now

    def write(self, e_km: int, n_km: int, data: bytes, sources: dict) -> None:
        """Atomically writes the cell, records it in the manifest, then enforces the cap."""
        self.write_many([(e_km, n_km, data, sources)])

    def write_many(self, cells: list[tuple[int, int, bytes, dict]], touch: bool = True) -> None:
        """Writes each file atomically, then records all of them with one manifest rewrite. A rebuild passes
        touch=False so it does not reorder the LRU."""
        for e_km, n_km, data, _ in cells:
            write_atomic(self.path(e_km, n_km), data)
        self.manifest.record_many([(e, n, sources, len(data)) for e, n, data, sources in cells])
        now = time.time()
        keys = set()
        with self._lock:
            for e_km, n_km, data, _ in cells:
                key = cell_key(e_km, n_km)
                keys.add(key)
                self._sizes[key] = len(data)
                if touch or key not in self._access:
                    self._access[key] = now
                    self._generated.pop(key, None)
        self.evict(protect=keys)

    def mark_generated(self, cells: Iterable[tuple[int, int]]) -> None:
        now = time.time()
        with self._lock:
            for e, n in cells:
                self._generated[cell_key(e, n)] = now
        self._persist(force=True)  # rare, and eviction order depends on it

    @property
    def bytes(self) -> int:
        with self._lock:
            return sum(self._sizes.values())

    @property
    def count(self) -> int:
        with self._lock:
            return len(self._sizes)

    def evict(self, protect: set[str] = frozenset()) -> list[str]:
        """Deletes cells until the store fits its cap; returns the evicted keys."""
        with self._lock:
            total = sum(self._sizes.values())
            if total <= self.cfg.store_cap_bytes:
                victims = []
            else:
                order = sorted((k for k in self._sizes if k not in protect),
                               key=lambda k: (k not in self._generated, self._access.get(k, 0.0), k))
                victims = []
                for k in order:
                    if total <= self.cfg.store_cap_bytes:
                        break
                    victims.append(k)
                    total -= self._sizes[k]
            for k in victims:
                (self.root / "cells" / f"{k}.swd").unlink(missing_ok=True)
                del self._sizes[k]
                self._generated.pop(k, None)
        if victims:
            self.manifest.mark_evicted(victims)
            log.info("evicted %d cells (%s)", len(victims), ", ".join(victims[:5]))
        self._persist(force=bool(victims))
        return victims

    def flush(self) -> None:
        self._persist(force=True)

    def _persist(self, force: bool = False) -> None:
        with self._lock:
            if not force and time.monotonic() - self._persisted < LRU_FLUSH_S:
                return
            self._persisted = time.monotonic()
            access, generated = dict(self._access), dict(self._generated)
        with self._lru.update() as d:
            d["access"], d["generated"] = access, generated
