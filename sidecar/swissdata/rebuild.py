"""Rebuilds stored cells with the current BUILDERS, keeping each cell's pinned sources. Owner: sidecar-core.

    python -m swissdata.rebuild (--bbox E0 N0 E1 N1 | --all) [--workers N] [--dry-run]

Cells come from manifest.json (live entries only; evicted ones are rebuilt on demand by the server). The bbox
is inclusive in km. With the dev guard on (SWISSDATA_DEV_BBOX, default), cells outside the dev area are never
touched. Order: mod-core's origin area first, then the rest, west to east.

Each cell is assembled with its manifest sources as `pinned`; builders keep those and add keys they did not
have yet (tlm, bathy, buildings, gwr), which are pinned by recording them (build.merge_sources refuses a
changed pin). Workers are processes (voxelising and GDAL are CPU-bound); only the main process writes. Each
cell file is replaced atomically, so a reader (the mod) sees the old or the new cell, never a partial one;
manifest entries are written in batches of BATCH cells or every BATCH_S seconds, so a cell stays ready (with
its old entry) until its new one lands.

Do not run it next to a server that builds the same cells: the last writer of manifest.json wins per cell, which
is harmless (both entries are valid), but the work is wasted.
"""

from __future__ import annotations

import argparse
import logging
import sys
import time
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, wait

from . import build, stac, swd
from .config import Config
from .manifest import cell_key
from .store import Store

log = logging.getLogger(__name__)

BATCH, BATCH_S = 25, 5.0
ORIGIN = stac.DEV_RANGES_LV95[1]  # mod-core's origin area, rebuilt first

_cfg: Config | None = None


def select(manifest: dict, bbox: tuple[int, int, int, int] | None, dev_bbox: bool) -> list[tuple[int, int]]:
    """Live manifest cells inside bbox (None = all), dev-area filtered, origin area first."""
    out = []
    for key, entry in manifest["cells"].items():
        if "evicted" in entry:
            continue
        e, n = map(int, key.split("_"))
        if bbox and not (bbox[0] <= e <= bbox[2] and bbox[1] <= n <= bbox[3]):
            continue
        if dev_bbox and not stac.in_dev_area(e, n):
            log.warning("%s is outside the dev area; skipped", key)
            continue
        out.append((e, n))
    e0, n0, e1, n1 = ORIGIN
    return sorted(out, key=lambda c: (not (e0 <= c[0] <= e1 and n0 <= c[1] <= n1), c))


def _init(cfg: Config) -> None:
    global _cfg
    _cfg = cfg
    logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(processName)s %(name)s: %(message)s")


def rebuild_cell(e_km: int, n_km: int, pinned: dict, cfg: Config | None = None,
                 builders: list | None = None) -> tuple[int, int, bytes, dict]:
    cell, sources = build.assemble(e_km, n_km, cfg or _cfg, pinned, builders)
    return e_km, n_km, swd.encode(cell), build.merge_sources(pinned, sources)


def rebuild(cfg: Config, cells: list[tuple[int, int]], workers: int = 4, builders: list | None = None) -> dict:
    """Rebuilds `cells` in order (with up to `workers` in flight) and returns stats. `builders` (tests) forces
    in-process building, since builder objects need not be picklable."""
    store = Store(cfg)
    t0, pending, failed, written, nbytes = time.monotonic(), [], [], 0, 0
    last_flush = time.monotonic()
    milestones = {}  # cell count -> seconds, logged when the origin area is done

    def flush(force: bool = False) -> None:
        nonlocal pending, last_flush, written, nbytes
        if pending and (force or len(pending) >= BATCH or time.monotonic() - last_flush >= BATCH_S):
            store.write_many(pending, touch=False)
            written += len(pending)
            nbytes += sum(len(c[2]) for c in pending)
            pending, last_flush = [], time.monotonic()
            log.info("%d/%d cells rebuilt (%.0f s)", written, len(cells), time.monotonic() - t0)

    origin_n = sum(1 for e, n in cells if ORIGIN[0] <= e <= ORIGIN[2] and ORIGIN[1] <= n <= ORIGIN[3])

    def done(result: tuple[int, int, bytes, dict]) -> None:
        pending.append(result)
        flush()

    def note_origin(finished: int) -> None:
        if origin_n and finished == origin_n and "origin" not in milestones:
            flush(force=True)
            milestones["origin"] = round(time.monotonic() - t0, 1)
            log.info("origin area (%d cells) done after %.1f s", origin_n, milestones["origin"])

    finished = 0
    if builders is not None or workers <= 1 or len(cells) <= 1:
        for e, n in cells:
            try:
                done(rebuild_cell(e, n, store.manifest.sources(e, n), cfg, builders))
            except Exception as ex:
                log.error("%s failed: %s", cell_key(e, n), ex)
                failed.append(cell_key(e, n))
            finished += 1
            note_origin(finished)
    else:
        # the first cell runs here, so per-process caches on disk (sheets, GWR table) are not built N times at once
        first, rest = cells[0], cells[1:]
        try:
            done(rebuild_cell(*first, store.manifest.sources(*first), cfg))
        except Exception as ex:
            log.error("%s failed: %s", cell_key(*first), ex)
            failed.append(cell_key(*first))
        finished += 1
        with ProcessPoolExecutor(workers, initializer=_init, initargs=(cfg,)) as ex:
            todo, running = iter(rest), {}

            def submit() -> None:
                for e, n in todo:
                    running[ex.submit(rebuild_cell, e, n, store.manifest.sources(e, n))] = (e, n)
                    return

            for _ in range(workers * 2):
                submit()
            while running:
                ready, _ = wait(running, timeout=BATCH_S, return_when=FIRST_COMPLETED)
                for f in ready:
                    e, n = running.pop(f)
                    try:
                        done(f.result())
                    except Exception as err:
                        log.error("%s failed: %s", cell_key(e, n), err)
                        failed.append(cell_key(e, n))
                    finished += 1
                    note_origin(finished)
                    submit()
                flush()
    flush(force=True)
    store.flush()
    seconds = round(time.monotonic() - t0, 1)
    return {"cells": written, "failed": failed, "seconds": seconds, **milestones, "bytes": nbytes,
            "bytesPerCell": nbytes // written if written else 0, "storeBytes": store.bytes, "storeCells": store.count}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m swissdata.rebuild", description=__doc__.split("\n\n")[0])
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--bbox", nargs=4, type=int, metavar=("E0", "N0", "E1", "N1"), help="inclusive, in km")
    g.add_argument("--all", action="store_true")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--dry-run", action="store_true", help="only list how many cells would be rebuilt")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s: %(message)s")
    cfg = Config.from_env()
    cells = select(Store(cfg).manifest.file.read(), tuple(args.bbox) if args.bbox else None, cfg.dev_bbox)
    log.info("%d cells to rebuild in %s", len(cells), cfg.store)
    if args.dry_run or not cells:
        return 0
    stats = rebuild(cfg, cells, args.workers)
    log.info("done: %s", {k: v for k, v in stats.items() if k != "failed"})
    if stats["failed"]:
        log.error("%d cells failed: %s", len(stats["failed"]), ", ".join(stats["failed"][:20]))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
