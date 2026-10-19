"""Dam check: dam pixels (from oracle dams.json: [x, z, crest_m]) must be solid near crest height,
and at least one column within 4 blocks must have water on top (the reservoir behind).
Usage: dams.py OUTDIR [STEP]   (read-only, uses the Anvil reader)"""
import json, math, sys
from collections import defaultdict
from pathlib import Path
import acc

def main():
    out = Path(sys.argv[1]); step = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    px = json.load(open(out / "dams.json"))[::step]
    groups = defaultdict(lambda: [0, 0, 0, 0, None])  # sampled, solid, crest±2, water nearby, example fail
    for x, z, crest in px:
        top = acc.top_at(x, z)
        if top is None:
            continue
        g = groups[(x // 256, z // 256)]
        g[0] += 1
        y = top - 1
        name = acc.block_at(x, y, z) or ""
        solid = "water" not in name and "air" not in name and "leaves" not in name
        g[1] += solid
        ok_h = abs(y - math.floor(acc.y_of(crest))) <= 2
        g[2] += solid and ok_h
        wet = any("water" in (acc.block_at(x + dx, (acc.top_at(x + dx, z + dz) or 0) - 1, z + dz) or "")
                  for dx in range(-4, 5) for dz in range(-4, 5) if acc.chunk_at(x + dx, z + dz) is not None)
        g[3] += wet
        if (not solid or not ok_h) and g[4] is None:
            g[4] = (x, y, z, name[:40], round(crest, 1))
    tot = [0, 0, 0, 0]
    for k, g in sorted(groups.items()):
        e, n = acc.lv95_of(k[0] * 256, k[1] * 256)
        print(f"dam group ~E {e:.0f} N {n:.0f}: sampled {g[0]}, solid {g[1]}, solid at crest±2 {g[2]}, water within 4 {g[3]}; first fail {g[4]}")
        tot = [a + b for a, b in zip(tot, g[:4])]
    print(f"TOTAL sampled {tot[0]}, solid {tot[1]}, crest±2 {tot[2]}, water within 4 {tot[3]}")

if __name__ == "__main__":
    main()
