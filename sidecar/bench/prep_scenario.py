"""Makes a private scenario from a clone base (store/raw/scratch under BASE): `cp -c` clones, the target cells'
manifest entries and cell files removed, and with --cold their pins, raw downloads and scratch caches removed too
(so the build re-resolves STAC and re-downloads everything specific to the cell). Never touches BASE.

    python bench/prep_scenario.py BASE DEST [--cold] CELL...
"""
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

args = [x for x in sys.argv[1:] if x != "--cold"]
cold = "--cold" in sys.argv
base, dest, cells = Path(args[0]), Path(args[1]), args[2:]
for p in (base, dest):
    assert "sidecar-speed" in str(p.resolve()), p
if dest.exists():
    shutil.rmtree(dest)
dest.mkdir(parents=True)
for d in ("store", "raw", "scratch"):
    subprocess.run(["cp", "-c", "-R", str(base / d), str(dest / d)], check=True)
man = json.loads((dest / "store/manifest.json").read_text())
pins = json.loads((dest / "store/pins.json").read_text())
raw = dest / "raw/data.geo.admin.ch"
for key in cells:
    e, n = map(int, key.split("_"))
    ent = man["cells"].pop(key, None)
    (dest / f"store/cells/{key}.swd").unlink(missing_ok=True)
    if not cold:
        continue
    for de in (-1, 0, 1):
        for dn in (-1, 0, 1):
            t = f"{e+de}-{n+dn}"
            for kind in ("alti", "ortho"):
                item = pins.get(kind, {}).pop(t, None)
                if item:
                    coll = {"alti": "ch.swisstopo.swissalti3d", "ortho": "ch.swisstopo.swissimage-dop10"}[kind]
                    shutil.rmtree(raw / coll / item, ignore_errors=True)
                    (dest / f"scratch/alti-edges/{item}.npz").unlink(missing_ok=True)
    item = pins.get("surface", {}).pop(f"{e}-{n}", None)
    if item:
        shutil.rmtree(raw / "ch.swisstopo.swisssurface3d-raster" / item, ignore_errors=True)
    for b in (ent or {}).get("sources", {}).get("buildings", []):
        shutil.rmtree(raw / "ch.swisstopo.swissbuildings3d_3_0" / b, ignore_errors=True)
        for f in (dest / "scratch/buildings").glob(f"{b}.*"):
            f.unlink()
(dest / "store/manifest.json").write_text(json.dumps(man))
(dest / "store/pins.json").write_text(json.dumps(pins))
print("ok", dest)
