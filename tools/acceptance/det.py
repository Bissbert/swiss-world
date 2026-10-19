"""Determinism / load-order check: per-section block hashes of full chunks, compared between two worlds.

    uv run --project sidecar python tools/acceptance/det.py hash AREA|@chunks.json [...] OUT.json
    uv run --project sidecar python tools/acceptance/det.py compare A.json B.json

World A: generate a chunk set; hash. Stop, wipe the level, start with the same level-seed, generate the same set in
reverse order (gen.py --reverse); hash; compare. Sections that differ are listed with the differing block names
(ores are expected if the vanilla ore pass is seeded per world - the seed is pinned, so they should match too).
"""
from __future__ import annotations

import collections
import json
import sys
from pathlib import Path

import acc
from areas import chunks_of


def hash_cmd(areas: list[str], out: str):
    res = {}
    for a in areas:
        for cx, cz in chunks_of(a):
            ch = acc.load_chunk(cx, cz)
            if ch is None or ch.status != "minecraft:full":
                continue
            res[f"{cx},{cz}"] = {str(k): v for k, v in ch.section_hashes().items()}
    Path(out).write_text(json.dumps(res))
    print(f"{len(res)} full chunks hashed -> {out}")


def compare(a: str, b: str):
    A, B = json.loads(Path(a).read_text()), json.loads(Path(b).read_text())
    common = sorted(set(A) & set(B))
    diff = [(k, s) for k in common for s in set(A[k]) | set(B[k]) if A[k].get(s) != B[k].get(s)]
    chunks = sorted({k for k, _ in diff})
    n_sec = sum(len(A[k]) for k in common)
    print(f"{len(common)} chunks in both ({len(A)} / {len(B)}); {n_sec} sections; differing sections {len(diff)} "
          f"in {len(chunks)} chunks")
    for k, s in diff[:10]:
        cx, cz = map(int, k.split(","))
        print(f"  chunk {k} (x {cx * 16} z {cz * 16}) section {s} (y {int(s) * 16}..{int(s) * 16 + 15})")
    return diff


def detail(a_dir: str, diff_json: str):
    """Block-level diff for the listed sections between the current world (ACC_DIM) and another region dir."""
    other = Path(a_dir)
    diff = json.loads(Path(diff_json).read_text())
    kinds = collections.Counter()
    for k, s in diff[:200]:
        cx, cz = map(int, k.split(","))
        c1 = acc.load_chunk(cx, cz)
        c2 = acc.load_chunk(cx, cz, other)
        for y in range(int(s) * 16, int(s) * 16 + 16):
            for lz in range(16):
                for lx in range(16):
                    b1, b2 = c1.block(lx, y, lz), c2.block(lx, y, lz)
                    if b1 != b2:
                        kinds[(b1.split("[")[0], b2.split("[")[0])] += 1
    print("differing block pairs (this world, other):", kinds.most_common(15))


if __name__ == "__main__":
    if sys.argv[1] == "hash":
        hash_cmd(sys.argv[2:-1], sys.argv[-1])
    elif sys.argv[1] == "compare":
        d = compare(sys.argv[2], sys.argv[3])
        if len(sys.argv) > 4:
            Path(sys.argv[4]).write_text(json.dumps(d))
    elif sys.argv[1] == "detail":
        detail(sys.argv[2], sys.argv[3])
