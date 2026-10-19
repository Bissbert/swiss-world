"""Generate a fixed, ordered chunk set via forceload and time it.

    uv run --project sidecar python tools/acceptance/gen.py AREA [AREA...] [--reverse] [--batch 64] [--out times.tsv]

AREA is a name from areas.py or "x1,z1,x2,z2" in blocks. Chunks are generated in batches (forceload add, poll
`if loaded`, forceload remove), then `save-all flush`. Prints per-batch wall time, ms/chunk and the load average;
`/swiss admin status` before/after gives the mod's own fill() ms/chunk.
"""
from __future__ import annotations

import argparse
import os
import re
import time

from acc import DIM, Rcon
from areas import chunks_of


def status(r: Rcon) -> str:
    return r("swiss admin status").strip()


def parse_status(s: str) -> tuple[int, float]:
    m = re.search(r"chunks (\d+) generated \(([\d.]+) ms/chunk\)", s)
    return (int(m.group(1)), float(m.group(2))) if m else (0, 0.0)


def generate(r: Rcon, chunks: list[tuple[int, int]], batch: int = 64, log=print, timeout: float = 900,
             pause: float = 0.0):
    done, t_all = 0, time.time()
    for i in range(0, len(chunks), batch):
        part = chunks[i:i + batch]
        t0 = time.time()
        for cx, cz in part:
            r(f"execute in {DIM} run forceload add {cx * 16} {cz * 16}")
        pending = list(part)
        while pending:
            pending = [c for c in pending
                       if "passed" not in r(f"execute in {DIM} if loaded {c[0] * 16} 0 {c[1] * 16}")]
            if pending:
                if time.time() - t0 > timeout:
                    log(f"TIMEOUT {len(pending)} chunks pending, e.g. {pending[:4]}")
                    break
                time.sleep(0.5)
        for cx, cz in part:
            r(f"execute in {DIM} run forceload remove {cx * 16} {cz * 16}")
        dt = time.time() - t0
        done += len(part)
        time.sleep(pause)
        log(f"batch {i // batch:3d}: {len(part)} chunks {dt:6.1f}s {1000 * dt / len(part):7.1f} ms/chunk wall "
            f"load {os.getloadavg()[0]:.1f}")
    r("save-all flush")
    return time.time() - t_all


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("areas", nargs="+")
    ap.add_argument("--reverse", action="store_true")
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--pause", type=float, default=0.0, help="seconds between batches (keep the machine usable)")
    a = ap.parse_args()
    chunks = []
    for name in a.areas:
        for c in chunks_of(name):
            if c not in chunks:
                chunks.append(c)
    if a.reverse:
        chunks.reverse()
    r = Rcon()
    n0, ms0 = parse_status(status(r))
    print("before:", status(r))
    wall = generate(r, chunks, a.batch, pause=a.pause)
    n1, ms = parse_status(status(r))
    print("after: ", status(r))
    print(f"TOTAL {len(chunks)} chunks requested, {n1 - n0} newly generated, wall {wall:.1f}s "
          f"({1000 * wall / max(1, len(chunks)):.1f} ms/chunk wall), fill() {(n1 * ms - n0 * ms0) / max(1, n1 - n0):.1f} "
          f"ms/chunk for this run, load {os.getloadavg()[0]:.1f}")


if __name__ == "__main__":
    main()
