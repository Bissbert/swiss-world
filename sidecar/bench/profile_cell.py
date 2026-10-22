"""Profiles cold cell builds: per-builder wall/CPU, network vs compute, downloads, peak RSS, byte-identity digest.

    python bench/profile_cell.py --store S --raw R --scratch X --out DIR [--cprofile] CELL...   (CELL = 2633_1170)

Runs the cells sequentially in this process exactly as the server's build_cell does (manifest sources as pins, STAC
lookups for unpinned tiles), but writes the .swd and a sources JSON to --out instead of the store. Prints one JSON
line per cell. Use a private store/raw/scratch (cp -c clones), never the user's.
"""
from __future__ import annotations

import argparse
import cProfile
import hashlib
import json
import os
import pstats
import resource
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import httpx  # noqa: E402

from swissdata import build, fetch as fetchmod, swd  # noqa: E402
from swissdata.config import Config  # noqa: E402
from swissdata.store import Store  # noqa: E402

NET = {"time": 0.0, "calls": 0, "downloads": 0, "bytes": 0, "stac": 0, "http": 0}


def _wrap_fetch():
    orig = fetchmod.fetch

    def fetch(cfg, url, *a, **k):
        dest = fetchmod.cache_path(cfg, url)
        had = dest.exists()
        t = time.perf_counter()
        r = orig(cfg, url, *a, **k)
        if not had:
            NET["time"] += time.perf_counter() - t
            NET["downloads"] += 1
            NET["bytes"] += r.stat().st_size
        NET["calls"] += 1
        return r

    orig_json = fetchmod.get_json

    def get_json(url, params=None, *a, **k):
        t = time.perf_counter()
        try:
            return orig_json(url, params, *a, **k)
        finally:
            NET["time"] += time.perf_counter() - t
            NET["stac"] += 1

    for m in list(sys.modules.values()):
        if getattr(m, "__name__", "").startswith("swissdata"):
            if getattr(m, "fetch", None) is orig:
                m.fetch = fetch
            if getattr(m, "get_json", None) is orig_json:
                m.get_json = get_json
    fetchmod.fetch, fetchmod.get_json = fetch, get_json

    send = httpx.Client.send

    def counted(self, request, **kw):
        NET["http"] += 1
        return send(self, request, **kw)

    httpx.Client.send = counted


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--store", required=True)
    ap.add_argument("--raw", required=True)
    ap.add_argument("--scratch", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--cprofile", action="store_true")
    ap.add_argument("--label", default="")
    ap.add_argument("cells", nargs="+")
    a = ap.parse_args()
    for ref in (a.store, a.raw, a.scratch):
        if "sidecar-speed" not in ref:
            sys.exit(f"refusing non-private path {ref}")
    os.environ.update(SWISSDATA_RAW=a.raw, SWISSDATA_STORE=a.store, SWISSDATA_SCRATCH=a.scratch)
    cfg = Config.from_env()  # same defaults as the server (keep_surface_raw only with SWISSDATA_KEEP_RAW=1)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    _wrap_fetch()
    store = Store(cfg)
    per_builder: dict[str, list[float]] = {}
    for b in build.BUILDERS:
        orig = b.build

        def timed(*args, _o=orig, _n=b.name, **kw):
            t, c = time.perf_counter(), time.process_time()
            try:
                return _o(*args, **kw)
            finally:
                p = per_builder.setdefault(_n, [0.0, 0.0])
                p[0] += time.perf_counter() - t
                p[1] += time.process_time() - c

        b.build = timed
    for key in a.cells:
        e, n = map(int, key.split("_"))
        per_builder.clear()
        net0 = dict(NET)
        pinned = store.manifest.sources(e, n)
        prof = cProfile.Profile() if a.cprofile else None
        t0, c0 = time.perf_counter(), time.process_time()
        if prof:
            prof.enable()
        cell, sources = build.assemble(e, n, cfg, pinned, None)
        data = swd.encode(cell)
        if prof:
            prof.disable()
        wall, cpu = time.perf_counter() - t0, time.process_time() - c0
        sources = build.merge_sources(pinned, sources)
        (out / f"{key}.swd").write_bytes(data)
        (out / f"{key}.sources.json").write_text(json.dumps(sources, sort_keys=True, indent=1))
        rec = {"label": a.label, "cell": key, "wall_s": round(wall, 2), "cpu_s": round(cpu, 2),
               "net_s": round(NET["time"] - net0["time"], 2), "downloads": NET["downloads"] - net0["downloads"],
               "dl_mb": round((NET["bytes"] - net0["bytes"]) / 1e6, 1), "stac_calls": NET["stac"] - net0["stac"],
               "http_calls": NET["http"] - net0["http"], "bytes": len(data),
               "sha": hashlib.sha256(data).hexdigest()[:16],
               "peak_rss_mb": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e6, 0),
               "builders": {k: [round(v[0], 2), round(v[1], 2)] for k, v in per_builder.items()}}
        print(json.dumps(rec), flush=True)
        if prof:
            prof.dump_stats(str(out / f"{key}.prof"))
            s = pstats.Stats(prof)
            s.sort_stats("cumulative").print_stats(35)


if __name__ == "__main__":
    main()
