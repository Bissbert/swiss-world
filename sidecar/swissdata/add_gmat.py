"""Adds GMAT to stored cells in place, without a full rebuild. Owner: sidecar-ortho.

    python -I -m swissdata.add_gmat [--only-missing-gmat | --force] [--bbox E0 N0 E1 N1] [--workers N] [--dry-run]

Only cells without a GMAT layer are touched (--force redoes them). Each cell is decoded, the GMAT builder runs
on its existing layers (SURF, LITH, WKND, OCLS, CNPY, BSPN as priors) with its manifest sources as pins, and the
cell is re-encoded: every other layer stays byte-identical. Cell files are replaced atomically; the manifest
gets the new `ortho` / `orthoNeighbours` pins and byte size in one locked rewrite per batch. With the dev guard on
(SWISSDATA_DEV_BBOX, default) cells outside the dev area are skipped, as in rebuild.
"""

from __future__ import annotations

import argparse
import logging
import sys
import time
from concurrent.futures import ProcessPoolExecutor

from . import build, rebuild, swd
from .config import Config
from .gmat import GmatBuilder
from .manifest import Manifest, cell_key

log = logging.getLogger(__name__)

BATCH = 25


def add_one(cfg: Config, e_km: int, n_km: int, pinned: dict, force: bool = False,
            builder: GmatBuilder | None = None) -> tuple[int, dict] | None:
    """Adds GMAT to one stored cell. Returns (new size, merged sources), or None if skipped."""
    path = swd.cell_path(cfg.store, e_km, n_km)
    if not path.exists():
        return None
    cell = swd.decode(path.read_bytes())
    if "GMAT" in cell.layers and not force:
        return None
    partial = build.LayerResult(layers=dict(cell.layers), buildings=cell.buildings, spans=cell.spans,
                                sources=dict(pinned))
    r = (builder or GmatBuilder()).build(e_km, n_km, cfg, partial, pinned)
    cell.layers.pop("GMAT", None)
    cell.layers.update(r.layers)
    data = swd.encode(cell)
    swd.write_atomic(path, data)
    return len(data), build.merge_sources(pinned, r.sources)


def _work(args):
    cfg, e, n, pinned, force = args
    t0 = time.time()
    return e, n, add_one(cfg, e, n, pinned, force), time.time() - t0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -I -m swissdata.add_gmat", description=__doc__.split("\n\n")[0])
    ap.add_argument("--bbox", nargs=4, type=int, metavar=("E0", "N0", "E1", "N1"), help="inclusive, in km")
    ap.add_argument("--workers", type=int, default=4)
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--only-missing-gmat", action="store_true", help="the default: skip cells that have GMAT")
    g.add_argument("--force", action="store_true", help="also redo cells that already have GMAT")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = Config.from_env()
    man = Manifest(cfg.store)
    m = man.file.read()
    cells = rebuild.select(m, tuple(a.bbox) if a.bbox else None, cfg.dev_bbox)
    log.info("%d cells selected in %s", len(cells), cfg.store)
    if a.dry_run:
        return 0
    jobs = [(cfg, e, n, m["cells"][cell_key(e, n)].get("sources") or {}, a.force) for e, n in cells]
    done, pending, t0 = 0, [], time.time()

    def flush():
        with man.file.update() as mm:
            for key, size, src in pending:
                mm["cells"][key]["bytes"], mm["cells"][key]["sources"] = size, src
        pending.clear()

    with ProcessPoolExecutor(a.workers) as ex:
        for e, n, res, dt in ex.map(_work, jobs, chunksize=1):
            if res is None:
                continue
            size, src = res
            pending.append((cell_key(e, n), size, src))
            done += 1
            log.info("%s_%s: %.1f s, %d bytes", e, n, dt, size)
            if len(pending) >= BATCH:
                flush()
    flush()
    log.info("added GMAT to %d/%d cells in %.0f s", done, len(cells), time.time() - t0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
