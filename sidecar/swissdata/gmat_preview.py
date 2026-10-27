"""Preview PNGs of GMAT: the orthophoto beside the classes in false colour (and shaded by tone). Owner: sidecar-ortho.

    python -I -m swissdata.gmat_preview NAME E0 N0 E1 N1 [--out DIR]

Reads the cells of the inclusive km bbox from $SWISSDATA_STORE and the pinned ortho tiles from $SWISSDATA_RAW
(never downloads). Writes DIR/NAME.png: left the photo, middle the classes, right the classes shaded by tone,
with a legend strip. Columns are shown at 1 px each (one 2 m photo pixel is upscaled to 2×2).
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from . import gmat, swd
from .config import Config
from .fetch import cache_path

COLOURS = {
    gmat.UNKNOWN: (255, 0, 255), gmat.TREE: (20, 90, 30), gmat.GRASS: (110, 190, 60), gmat.DRY: (215, 195, 90),
    gmat.SOIL: (150, 100, 55), gmat.ROCK_LIGHT: (215, 215, 205), gmat.ROCK_DARK: (95, 90, 95),
    gmat.SCREE: (165, 140, 125), gmat.SNOW: (240, 250, 255), gmat.WATER: (40, 90, 200), gmat.SEALED: (230, 60, 50),
}
NAMES = {gmat.UNKNOWN: "unknown", gmat.TREE: "tree", gmat.GRASS: "grass", gmat.DRY: "dry grass", gmat.SOIL: "soil",
         gmat.ROCK_LIGHT: "light rock", gmat.ROCK_DARK: "dark rock", gmat.SCREE: "scree", gmat.SNOW: "snow",
         gmat.WATER: "water", gmat.SEALED: "sealed"}


def render(cfg: Config, e0: int, n0: int, e1: int, n1: int) -> tuple[Image.Image, dict[int, float]]:
    w, h = (e1 - e0 + 1) * swd.CELL, (n1 - n0 + 1) * swd.CELL
    photo = np.zeros((h, w, 3), np.uint8)
    cls = np.zeros((h, w), np.uint8)
    tone = np.zeros((h, w), np.uint8)
    man = json.loads((cfg.store / "manifest.json").read_text())["cells"]
    for e in range(e0, e1 + 1):
        for n in range(n0, n1 + 1):
            r, c = (n1 - n) * swd.CELL, (e - e0) * swd.CELL
            p = swd.cell_path(cfg.store, e, n)
            if p.exists():
                g = swd.decode(p.read_bytes()).layers.get("GMAT")
                if g is not None:
                    cls[r:r + swd.CELL, c:c + swd.CELL] = g & 15
                    tone[r:r + swd.CELL, c:c + swd.CELL] = g >> 4
            item = (man.get(f"{e}_{n}", {}).get("sources") or {}).get("ortho")
            if item and cache_path(cfg, gmat.ortho_href(item)).exists():
                rgb = gmat.read_ortho(cache_path(cfg, gmat.ortho_href(item)))
                photo[r:r + swd.CELL, c:c + swd.CELL] = np.repeat(np.repeat(rgb, 2, axis=0), 2, axis=1)
    lut = np.zeros((16, 3), np.uint8)
    for k, col in COLOURS.items():
        lut[k] = col
    false = lut[cls]
    shade = (false * (0.45 + 0.55 * tone[..., None] / 15.0)).astype(np.uint8)
    gap = 8
    img = Image.new("RGB", (3 * w + 2 * gap, h + 40), (255, 255, 255))
    for i, a in enumerate((photo, false, shade)):
        img.paste(Image.fromarray(a), (i * (w + gap), 0))
    d = ImageDraw.Draw(img)
    x = 4
    share = {k: float((cls == k).mean()) for k in COLOURS}
    for k, col in COLOURS.items():
        d.rectangle((x, h + 10, x + 20, h + 30), fill=col, outline=(0, 0, 0))
        label = f"{NAMES[k]} {100 * share[k]:.0f}%"
        d.text((x + 24, h + 14), label, fill=(0, 0, 0))
        x += 30 + 7 * len(label)
    return img, share


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -I -m swissdata.gmat_preview")
    ap.add_argument("name")
    ap.add_argument("bbox", nargs=4, type=int, metavar=("E0", "N0", "E1", "N1"))
    ap.add_argument("--out", type=Path, default=Path(__file__).resolve().parents[2] / ".data-cache/previews/gmat")
    a = ap.parse_args(argv)
    img, share = render(Config.from_env(), *a.bbox)
    a.out.mkdir(parents=True, exist_ok=True)
    img.save(a.out / f"{a.name}.png")
    print(a.out / f"{a.name}.png", {NAMES[k]: round(v, 3) for k, v in share.items() if v})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
