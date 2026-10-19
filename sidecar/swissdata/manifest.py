"""manifest.json (docs/swd-format.md §7) and pins.json, both in the store root. Owner: sidecar-core.

- manifest.json: per cell, the upstream items it was built from. Entries are never removed; an evicted cell
  keeps its `sources` (and gets `"evicted"`), so a rebuild reuses exactly the same items.
- pins.json (sidecar-internal): per upstream *tile*, the item chosen the first time any cell needed it.
  A cell reads its neighbours' tiles for the shared edge, so the neighbour must later be built from the
  same item; pinning per tile rather than per cell guarantees that.

Writes are read-modify-write under an fcntl lock on a sibling .lock file plus an atomic rename, so the
server and a CLI process may share one store.
"""

from __future__ import annotations

import fcntl
import json
import os
import threading
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from . import __version__, swd


def now_iso() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def cell_key(e_km: int, n_km: int) -> str:
    return f"{e_km}_{n_km}"


class JsonFile:
    """A JSON document shared between threads and processes."""

    def __init__(self, path: Path, empty: Callable[[], dict]):
        self.path = Path(path)
        self._empty = empty
        self._tlock = threading.RLock()
        self._data: dict | None = None
        self._mtime: int | None = None

    def read(self) -> dict:
        """Current content (reloaded if another process changed the file). Treat as read-only."""
        with self._tlock:
            try:
                mtime = self.path.stat().st_mtime_ns
            except FileNotFoundError:
                mtime = None
            if self._data is None or mtime != self._mtime:
                self._data = json.loads(self.path.read_text()) if mtime is not None else self._empty()
                self._mtime = mtime
            return self._data

    @contextmanager
    def update(self) -> Iterator[dict]:
        """Locked read-modify-write; the yielded dict is written back atomically on exit."""
        with self._tlock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with open(self.path.with_name(self.path.name + ".lock"), "w") as lf:
                fcntl.flock(lf, fcntl.LOCK_EX)
                self._data = None
                data = self.read()
                yield data
                tmp = self.path.with_name(self.path.name + ".tmp")
                with open(tmp, "w") as f:
                    json.dump(data, f, indent=1, sort_keys=True)
                    f.write("\n")
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp, self.path)
                self._mtime = self.path.stat().st_mtime_ns


class Manifest:
    def __init__(self, store: Path):
        self.file = JsonFile(Path(store) / "manifest.json", lambda: {"formatVersion": swd.FORMAT_VERSION, "cells": {}})

    def entry(self, e_km: int, n_km: int) -> dict | None:
        return self.file.read()["cells"].get(cell_key(e_km, n_km))

    def sources(self, e_km: int, n_km: int) -> dict | None:
        e = self.entry(e_km, n_km)
        return e["sources"] if e else None

    def record(self, e_km: int, n_km: int, sources: dict, size: int) -> None:
        self.record_many([(e_km, n_km, sources, size)])

    def record_many(self, entries: list[tuple[int, int, dict, int]]) -> None:
        """One locked rewrite for many cells (the file is rewritten whole, so batching is what keeps it cheap)."""
        created = now_iso()
        with self.file.update() as m:
            for e_km, n_km, sources, size in entries:
                m["cells"][cell_key(e_km, n_km)] = {
                    "processor": f"swissdata {__version__}",
                    "created": created,
                    "sources": sources,
                    "bytes": size,
                }

    def mark_evicted(self, keys: list[str]) -> None:
        with self.file.update() as m:
            for k in keys:
                if k in m["cells"]:
                    m["cells"][k]["evicted"] = now_iso()


class Pins:
    """Upstream item per (kind, tile). A pinned value is never changed; None pins "no item exists"."""

    def __init__(self, store: Path):
        self.file = JsonFile(Path(store) / "pins.json", dict)

    def get(self, kind: str, key: str) -> tuple[bool, Any]:
        table = self.file.read().get(kind, {})
        return (key in table), table.get(key)

    def pin(self, kind: str, key: str, resolve: Callable[[], Any]) -> Any:
        """Pinned value, or resolve() once and pin it. Resolution happens under the file lock so two
        workers can never pin one tile differently."""
        known, value = self.get(kind, key)
        if known:
            return value
        with self.file.update() as p:
            table = p.setdefault(kind, {})
            if key not in table:
                table[key] = resolve()
            return table[key]
