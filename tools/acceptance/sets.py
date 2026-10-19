"""Build the chunk lists for the sweep from oracle output (run oracle.py, rails.py plan, ways.py plan first).

    uv run --project sidecar python tools/acceptance/sets.py OUTDIR
Writes OUTDIR/chunks-<name>.json for: dams, falls, handegg-fall, lake, plus the AREAS boxes; prints sizes.
"""
import json, sys
from pathlib import Path
import acc
from areas import AREAS, chunks_of

out = Path(sys.argv[1])
def ring(pts, r=1):
    return sorted({((x >> 4) + i, (z >> 4) + k) for x, z in pts for i in range(-r, r + 1) for k in range(-r, r + 1)})
sets = {}
sets["dams"] = ring([(p[0], p[1]) for p in json.loads((out / "dams.json").read_text())], 1)
sets["falls"] = ring([(p[0], p[1]) for p in json.loads((out / "waterfall-falls.json").read_text())], 2)
sets["handegg-fall"] = ring([(p[0], p[1]) for p in json.loads((out / "waterfall-handegg.json").read_text())], 2)
o = json.loads((out / "oracle.json").read_text())["brienzersee_deepest"]
sets["lake"] = ring([(o["x"], o["z"])], 2)
for name in ("interlaken", "harder", "seam", "aare", "aareschlucht"):
    sets[name] = chunks_of(name)
for k, v in sets.items():
    (out / f"chunks-{k}.json").write_text(json.dumps(v))
for k in sorted(set(sets) | {"rail", "road"}):
    p = out / f"chunks-{k}.json"
    print(f"{k:14s} {len(json.loads(p.read_text())):6d} chunks")
