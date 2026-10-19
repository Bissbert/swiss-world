"""Region-file bytes per km² for chunk lists: sum of each chunk's sectors (location table count x 4096, plus any
external .mcc file). Only chunks saved with Status minecraft:full count.

    uv run --project sidecar python tools/acceptance/size.py AREA|@chunks.json ...
"""
import os, struct, sys
from pathlib import Path
import acc
from areas import chunks_of

def chunk_bytes(cx, cz, rdir):
    p = rdir / f"r.{cx >> 5}.{cz >> 5}.mca"
    if not p.exists():
        return None
    with open(p, "rb") as f:
        f.seek(4 * ((cx & 31) + 32 * (cz & 31)))
        loc = struct.unpack(">I", f.read(4))[0]
    if loc == 0:
        return None
    ext = rdir / f"c.{cx}.{cz}.mcc"
    return (loc & 0xFF) * 4096 + (ext.stat().st_size if ext.exists() else 0)

def main():
    rdir = acc.region_dir()
    for name in sys.argv[1:]:
        tot = n = full = 0
        for cx, cz in chunks_of(name):
            b = chunk_bytes(cx, cz, rdir)
            if b is None:
                continue
            c = acc.chunk_nbt(cx, cz, rdir)
            if c.get("Status") != "minecraft:full":
                continue
            tot += b; full += 1
        km2 = full * 256 / 1e6
        print(f"{name}: {full} full chunks, {tot / 1e6:.1f} MB, {tot / 1e6 / km2 if km2 else 0:.0f} MB/km², "
              f"{tot / max(full, 1) / 1024:.1f} KB/chunk")
    allb = sum(p.stat().st_size for p in rdir.iterdir())
    print(f"region dir total {allb / 1e6:.1f} MB ({len(list(rdir.glob('*.mca')))} .mca, {len(list(rdir.glob('*.mcc')))} .mcc)")

main()
