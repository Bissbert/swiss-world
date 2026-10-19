"""Fake swissdata sidecar for mod dev tests (docs/sidecar-api.md control plane). Owner: mod-core.

Serves /health, /request, /wait, /prefetch, /generated and /status against a PRIVATE store (never the shared one).
"Building" a cell takes --delay seconds and writes a synthetic cell with swd.py; cell 2632_1170 is the golden
fixture. coverage.bin covers e 2620..2649 / n 1160..1179; everything with e >= --ocean-from-e is outside (ocean),
so walking east from Interlaken reaches the "border".

    cd sidecar && uv run python -I ../tools/fake_sidecar.py --store /tmp/swissworld-mod-core --port 25642

Synthetic terrain (continuous across cells): a valley floor at ~570 m rising towards a peak of ~3300 m at
E 2636500 / N 1171500, so the snow line (~2300 m) and the rock/glacier classes are reachable on foot.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "sidecar"))
import numpy as np  # noqa: E402

from swissdata import swd  # noqa: E402

FIXTURE = Path(__file__).resolve().parent.parent / "fixtures" / "synthetic_2632_1170.swd"
FIXTURE_CELL = (2632, 1170)
E_MIN, N_MIN, W, H = 2620, 1160, 30, 20
PEAK_E, PEAK_N = 2_636_500.0, 1_171_500.0


def height(e: np.ndarray, n: np.ndarray) -> np.ndarray:
    r = np.hypot(e - PEAK_E, n - PEAK_N)
    return 570.0 + 2730.0 * np.exp(-(r / 1800.0) ** 2) + 15.0 * np.sin(e / 90.0) * np.cos(n / 110.0)


def synth(e_km: int, n_km: int) -> swd.Cell:
    i = np.arange(swd.TERR_N)[None, :]
    j = np.arange(swd.TERR_N)[:, None]
    h_nodes = height(e_km * 1000.0 + 2 * i, (n_km + 1) * 1000.0 - 2 * j)
    dx = np.arange(swd.CELL)[None, :]
    dz = np.arange(swd.CELL)[:, None]
    h = height(e_km * 1000.0 + dx + 0.5, (n_km + 1) * 1000.0 - dz - 0.5)
    surf = np.select([h >= 2900, h >= 2500, h >= 2250, h >= 1800, (h < 1500) & ((dx // 200) % 3 == 1)],
                     [6, 4, 5, 17, 2], 0).astype("u1")
    # a small lake in every cell's south-west corner, below the valley floor
    lake = (dz >= 900) & (dx < 80) & (h < 1500)
    surf[lake] = 7
    wlvl = np.full((swd.CELL, swd.CELL), swd.NO_HEIGHT, dtype="<u2")
    wlvl[lake] = swd.encode_height(h[lake] + 0.0)  # flat enough at this size; WaterStage owns the real shape
    wknd = np.where(lake, 1, 0).astype("u1")
    z8 = np.zeros((swd.CELL, swd.CELL), dtype="u1")
    return swd.Cell(e_km, n_km, layers={
        "TERR": swd.encode_height(h_nodes), "SURF": surf, "WLVL": wlvl, "WKND": wknd,
        "OCLS": z8, "OELV": np.full((swd.CELL, swd.CELL), swd.NO_HEIGHT, dtype="<u2"), "OFLG": z8.copy(),
        "INSD": np.ones((swd.CELL, swd.CELL), dtype="u1"),
    })


class Fake:
    def __init__(self, store: Path, delay: float, ocean_from_e: int):
        self.store = store
        self.delay = delay
        self.ocean_from_e = ocean_from_e
        self.lock = threading.Lock()
        self.building: set[tuple[int, int]] = set()
        self.queue = {"now": 0, "prefetch": 0}
        self.requests = 0
        grid = np.zeros((H, W), dtype="u1")
        for e in range(E_MIN, E_MIN + W):
            if e < ocean_from_e:
                grid[:, e - E_MIN] = 2
        if E_MIN <= ocean_from_e - 1 < E_MIN + W:
            grid[:, ocean_from_e - 1 - E_MIN] = 1
        swd.write_atomic(store / "coverage.bin", swd.encode_coverage(E_MIN, N_MIN, grid))
        self.grid = grid

    def coverage(self, e: int, n: int) -> int:
        if not (E_MIN <= e < E_MIN + W and N_MIN <= n < N_MIN + H):
            return 0
        return int(self.grid[n - N_MIN, e - E_MIN])

    def state(self, e: int, n: int, priority: str) -> dict:
        entry: dict = {"cell": [e, n]}
        if self.coverage(e, n) == 0:
            entry["state"] = "outside"
        elif swd.cell_path(self.store, e, n).exists():
            entry["state"] = "ready"
        else:
            with self.lock:
                if (e, n) not in self.building:
                    self.building.add((e, n))
                    self.queue[priority] = self.queue.get(priority, 0) + 1
                    threading.Thread(target=self.build, args=(e, n, priority), daemon=True).start()
            entry["state"] = "building"
        return entry

    def build(self, e: int, n: int, priority: str) -> None:
        time.sleep(self.delay)
        path = swd.cell_path(self.store, e, n)
        if (e, n) == FIXTURE_CELL:
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp = path.with_suffix(".swd.tmp")
            shutil.copyfile(FIXTURE, tmp)
            tmp.replace(path)
        else:
            swd.write_atomic(path, swd.encode(synth(e, n)))
        with self.lock:
            self.building.discard((e, n))
            self.queue[priority] -= 1
        print(f"built {e}_{n}", flush=True)


def make_handler(fake: Fake):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            print("http", self.command, self.path, flush=True)

        def reply(self, code: int, body: dict | None = None):
            data = b"" if body is None else json.dumps(body).encode()
            self.send_response(code)
            if body is not None:
                self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def body(self) -> dict:
            n = int(self.headers.get("Content-Length", 0))
            return json.loads(self.rfile.read(n) or b"{}")

        def do_GET(self):
            u = urlparse(self.path)
            q = parse_qs(u.query)
            if u.path == "/health":
                self.reply(200, {"ok": True, "version": "fake", "formatVersion": 1})
            elif u.path == "/status":
                with fake.lock:
                    q2 = dict(fake.queue, building=len(fake.building))
                cells = list((fake.store / "cells").glob("*.swd")) if (fake.store / "cells").exists() else []
                self.reply(200, {"queue": q2, "store": {"cells": len(cells), "bytes": sum(c.stat().st_size for c in cells)},
                                 "upstream": {"requests": fake.requests, "errors": 0, "lastError": None},
                                 "versions": {"fake": True}})
            elif u.path == "/wait":
                e, n = (int(v) for v in q["cell"][0].split(","))
                deadline = time.time() + int(q.get("timeoutMs", ["20000"])[0]) / 1000
                entry = fake.state(e, n, "now")
                while entry["state"] == "building" and time.time() < deadline:
                    time.sleep(0.1)
                    entry = fake.state(e, n, "now")
                self.reply(200, entry)
            else:
                self.reply(404, {"error": "not found"})

        def do_POST(self):
            u = urlparse(self.path)
            b = self.body()
            if u.path == "/request":
                fake.requests += 1
                self.reply(200, {"cells": [fake.state(e, n, b.get("priority", "now")) for e, n in b.get("cells", [])]})
            elif u.path == "/prefetch":
                r = float(b.get("radiusM", 0))
                e0, n0 = float(b["e"]), float(b["n"])
                cells = {(int((e0 + dx) // 1000), int((n0 + dy) // 1000))
                         for dx in np.arange(-r, r + 1, 500.0) for dy in np.arange(-r, r + 1, 500.0) if dx * dx + dy * dy <= r * r}
                cells.add((int(e0 // 1000), int(n0 // 1000)))
                queued = sum(1 for e, n in cells if fake.state(e, n, "prefetch")["state"] == "building")
                self.reply(202, {"queued": queued})
            elif u.path == "/generated":
                self.reply(204)
            else:
                self.reply(404, {"error": "not found"})

    return Handler


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--store", required=True, type=Path)
    p.add_argument("--port", type=int, default=25642)
    p.add_argument("--delay", type=float, default=1.0, help="seconds to 'build' one cell")
    p.add_argument("--ocean-from-e", type=int, default=2640, help="cells with eKm >= this are outside")
    a = p.parse_args()
    shared = Path(__file__).resolve().parent.parent.parent / "swiss-world" / "store"
    if a.store.resolve() == shared.resolve():
        sys.exit("refusing to write synthetic cells into the shared store")
    (a.store / "cells").mkdir(parents=True, exist_ok=True)
    fake = Fake(a.store, a.delay, a.ocean_from_e)
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), make_handler(fake))
    print(f"fake sidecar on 127.0.0.1:{a.port}, store {a.store}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
