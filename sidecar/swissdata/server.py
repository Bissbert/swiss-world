"""The sidecar HTTP control plane (docs/sidecar-api.md) and its build queue. Owner: sidecar-core.

    uv run python -m swissdata.server            # 127.0.0.1:8080; SWISSDATA_HOST / SWISSDATA_PORT override

One PriorityQueue feeds `max_downloads` worker threads ("now" before "prefetch", FIFO within a priority).
Requesting a queued cell again with a better priority pushes a second heap entry; the stale one is skipped.
A failed cell stays in `error` until its retryAfterS has passed, then the next request re-queues it.
"""

from __future__ import annotations

import heapq
import logging
import math
import os
import threading
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from fastapi import FastAPI, Query, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from . import __version__, build, coverage, stac, swd
from .config import Config
from .fetch import STATS
from .manifest import Pins
from .store import Store

log = logging.getLogger(__name__)

PRIORITIES = {"now": 0, "prefetch": 1}
RETRY_AFTER_S = 30
RETRY_AFTER_DEV_BBOX_S = 3600


@dataclass
class CellState:
    state: str  # queued | building | error
    priority: int = 1
    seq: int = 0
    error: str | None = None
    retry_at: float = 0.0


class BuildQueue:
    def __init__(self, cfg: Config, store: Store, grid: np.ndarray, builders=None):
        self.cfg, self.store, self.grid = cfg, store, grid
        self.builders = builders
        self._heap: list[tuple[int, int, tuple[int, int]]] = []
        self._states: dict[tuple[int, int], CellState] = {}
        self._seq = 0
        self._cv = threading.Condition()
        self._threads: list[threading.Thread] = []
        self._stop = False

    # --- API side -----------------------------------------------------------------------------

    def request(self, cells: list[tuple[int, int]], priority: int) -> list[dict]:
        self.store.touch(cells)
        with self._cv:
            for cell in cells:
                self._enqueue(cell, priority)
            return [self._entry(c) for c in cells]

    def prefetch(self, cells: list[tuple[int, int]]) -> int:
        """Queues cells at prefetch priority; returns how many were newly queued."""
        self.store.touch(cells)
        with self._cv:
            return sum(1 for c in cells if self._enqueue(c, PRIORITIES["prefetch"]))

    def wait(self, cell: tuple[int, int], timeout_s: float) -> dict:
        """Long-polls until ready/outside/error. A cell nobody requested yet is queued at "now" first."""
        deadline = time.monotonic() + timeout_s
        with self._cv:
            self._enqueue(cell, PRIORITIES["now"])  # no-op unless unknown or an error whose retry time has passed
            while True:
                e = self._entry(cell)
                left = deadline - time.monotonic()
                if e["state"] in ("ready", "outside", "error") or left <= 0:
                    return e
                self._cv.wait(left)

    def counts(self) -> dict:
        with self._cv:
            live = [s for s in self._states.values() if s.state in ("queued", "building")]
            return {"now": sum(1 for s in live if s.state == "queued" and s.priority == 0),
                    "prefetch": sum(1 for s in live if s.state == "queued" and s.priority == 1),
                    "building": sum(1 for s in live if s.state == "building")}

    def _enqueue(self, cell: tuple[int, int], priority: int) -> bool:
        """Returns True if the cell was (re)queued or upgraded."""
        if coverage.state(self.grid, *cell) == coverage.OUTSIDE or self.store.is_ready(*cell):
            return False
        s = self._states.get(cell)
        if s is not None:
            if s.state == "building" or (s.state == "error" and time.time() < s.retry_at):
                return False
            if s.state == "queued" and s.priority <= priority:
                return False
        if self.cfg.dev_bbox and not stac.in_dev_area(*cell):
            self._states[cell] = CellState("error", priority, error="outside the dev test bbox (SWISSDATA_DEV_BBOX=1)",
                                           retry_at=time.time() + RETRY_AFTER_DEV_BBOX_S)
            return False
        self._seq += 1
        self._states[cell] = CellState("queued", priority, self._seq)
        heapq.heappush(self._heap, (priority, self._seq, cell))
        self._cv.notify_all()
        return True

    def _entry(self, cell: tuple[int, int]) -> dict:
        out: dict = {"cell": list(cell)}
        if coverage.state(self.grid, *cell) == coverage.OUTSIDE:
            out["state"] = "outside"
            return out
        if self.store.is_ready(*cell):
            out["state"] = "ready"
            return out
        s = self._states.get(cell)
        if s is None:
            out["state"] = "missing"  # not requested (internal; /request and /wait always queue first)
        elif s.state == "queued":
            out["state"] = "queued"
            out["position"] = sum(1 for p, q, c in self._heap
                                  if (p, q) < (s.priority, s.seq) and self._live(p, q, c))
        elif s.state == "error":
            out.update(state="error", error=s.error, retryAfterS=max(0, math.ceil(s.retry_at - time.time())))
        else:
            out["state"] = s.state
        return out

    def _live(self, priority: int, seq: int, cell: tuple[int, int]) -> bool:
        s = self._states.get(cell)
        return s is not None and s.state == "queued" and s.seq == seq

    # --- workers ------------------------------------------------------------------------------

    def start(self) -> None:
        for i in range(self.cfg.max_downloads):
            t = threading.Thread(target=self._work, name=f"build-{i}", daemon=True)
            t.start()
            self._threads.append(t)

    def stop(self) -> None:
        with self._cv:
            self._stop = True
            self._cv.notify_all()

    def _next(self) -> tuple[int, int] | None:
        with self._cv:
            while not self._stop:
                while self._heap:
                    p, q, cell = heapq.heappop(self._heap)
                    if self._live(p, q, cell):
                        self._states[cell].state = "building"
                        return cell
                self._cv.wait()
            return None

    def _work(self) -> None:
        while (cell := self._next()) is not None:
            t0 = time.monotonic()
            try:
                self.build_cell(*cell)
                with self._cv:
                    self._states.pop(cell, None)
                log.info("built %s_%s in %.1fs", *cell, time.monotonic() - t0)
            except Exception as ex:  # one bad cell must not kill the worker
                log.exception("building %s_%s failed", *cell)
                with self._cv:
                    s = self._states[cell]
                    s.state, s.error, s.retry_at = "error", f"{type(ex).__name__}: {ex}", time.time() + RETRY_AFTER_S
            with self._cv:
                self._cv.notify_all()

    def build_cell(self, e_km: int, n_km: int) -> None:
        pinned = self.store.manifest.sources(e_km, n_km)
        cell, sources = build.assemble(e_km, n_km, self.cfg, pinned, self.builders)
        self.store.write(e_km, n_km, swd.encode(cell), sources)


# --- prefetch geometry ------------------------------------------------------------------------

def cells_in_radius(e: float, n: float, r: float) -> set[tuple[int, int]]:
    """Cells whose square comes within r metres of (e, n)."""
    out = set()
    for ek in range(math.floor((e - r) / 1000), math.floor((e + r) / 1000) + 1):
        for nk in range(math.floor((n - r) / 1000), math.floor((n + r) / 1000) + 1):
            dx = max(ek * 1000 - e, 0.0, e - (ek * 1000 + 1000))
            dy = max(nk * 1000 - n, 0.0, n - (nk * 1000 + 1000))
            if dx * dx + dy * dy <= r * r:
                out.add((ek, nk))
    return out


def prefetch_cells(e: float, n: float, radius: float, heading_deg: float, speed: float) -> list[tuple[int, int]]:
    """Radius around the point, plus (if moving) a corridor of max(500, 60·speed) m along the heading.
    Heading is a compass bearing: 0 = north, 90 = east. The corridor is 500 m wide on each side.
    Ordered nearest first."""
    cells = cells_in_radius(e, n, radius)
    if speed > 0:
        length = max(500.0, 60.0 * speed)
        de, dn = math.sin(math.radians(heading_deg)), math.cos(math.radians(heading_deg))
        steps = int(length // 250) + 1
        for k in range(steps + 1):
            d = min(length, k * 250.0)
            cells |= cells_in_radius(e + de * d, n + dn * d, 500.0)
    return sorted(cells, key=lambda c: ((c[0] * 1000 + 500 - e) ** 2 + (c[1] * 1000 + 500 - n) ** 2, c))


# --- HTTP -------------------------------------------------------------------------------------

class RequestBody(BaseModel):
    cells: list[tuple[int, int]]
    priority: str = "now"


class PrefetchBody(BaseModel):
    e: float
    n: float
    radiusM: float = 2000.0
    headingDeg: float = 0.0
    speedMps: float = 0.0


class GeneratedBody(BaseModel):
    cells: list[tuple[int, int]]


def _dir_bytes(path: Path) -> int:
    total = 0
    if path.is_dir():
        for root, _, files in os.walk(path):
            for f in files:
                try:
                    total += os.path.getsize(os.path.join(root, f))
                except OSError:
                    pass
    return total


def create_app(cfg: Config | None = None, builders=None, grid: np.ndarray | None = None) -> FastAPI:
    cfg = cfg or Config.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        g = grid if grid is not None else coverage.ensure_coverage(cfg)
        store = Store(cfg)
        q = BuildQueue(cfg, store, g, builders)
        q.start()
        app.state.cfg, app.state.store, app.state.queue = cfg, store, q
        app.state.scratch = (0.0, 0)
        log.info("swissdata %s: store %s (%d cells), %d workers", __version__, cfg.store, store.count, cfg.max_downloads)
        yield
        q.stop()

    app = FastAPI(title="swissdata", version=__version__, lifespan=lifespan)

    @app.get("/health")
    def health():
        return {"ok": True, "version": __version__, "formatVersion": swd.FORMAT_VERSION}

    @app.post("/request")
    def request(body: RequestBody):
        if body.priority not in PRIORITIES:
            return JSONResponse({"error": f"unknown priority {body.priority!r}"}, status_code=400)
        return {"cells": app.state.queue.request([tuple(c) for c in body.cells], PRIORITIES[body.priority])}

    @app.get("/wait")
    def wait(cell: str, timeoutMs: int = Query(20000, ge=0, le=120000)):
        try:
            e, n = (int(v) for v in cell.split(","))
        except ValueError:
            return JSONResponse({"error": "cell must be eKm,nKm"}, status_code=400)
        return app.state.queue.wait((e, n), timeoutMs / 1000.0)

    @app.post("/prefetch", status_code=202)
    def prefetch(body: PrefetchBody):
        cells = prefetch_cells(body.e, body.n, body.radiusM, body.headingDeg, body.speedMps)
        return {"queued": app.state.queue.prefetch(cells)}

    @app.post("/generated", status_code=204)
    def generated(body: GeneratedBody):
        app.state.store.mark_generated([tuple(c) for c in body.cells])
        return Response(status_code=204)

    @app.get("/status")
    def status():
        store: Store = app.state.store
        at, scratch = app.state.scratch
        if time.monotonic() - at > 30:  # walking the scratch dir is not free
            scratch = _dir_bytes(Path(cfg.scratch))
            app.state.scratch = (time.monotonic(), scratch)
        pins = Pins(cfg.store)
        return {
            "queue": app.state.queue.counts(),
            "store": {"cells": store.count, "bytes": store.bytes, "capBytes": cfg.store_cap_bytes},
            "scratch": {"bytes": scratch, "capBytes": cfg.scratch_cap_bytes},
            "upstream": {"requests": STATS.requests, "errors": STATS.errors, "lastError": STATS.last_error,
                         "downloads": STATS.downloads, "downloadBytes": STATS.download_bytes},
            "versions": {"tlm": pins.get("tlm", "ch")[1], "boundaries": pins.get("boundaries", "ch")[1]},
        }

    @app.get("/place")
    def place(q: str = "", limit: int = 10):
        return JSONResponse({"error": "gazetteer not implemented yet"}, status_code=501)

    return app


def main() -> None:
    import uvicorn

    logging.basicConfig(level=os.environ.get("SWISSDATA_LOG", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    uvicorn.run(create_app(), host=os.environ.get("SWISSDATA_HOST", "127.0.0.1"),
                port=int(os.environ.get("SWISSDATA_PORT", "8080")))


if __name__ == "__main__":
    main()
