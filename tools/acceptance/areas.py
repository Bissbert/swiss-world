"""Named test areas (LV95 rectangles) -> ordered chunk lists. Coordinates from swissNAMES3D 2026 / the cell data."""
from __future__ import annotations

import json
import math
import os
from pathlib import Path

from acc import block_of

# name: (E_min, N_min, E_max, N_max) in LV95 metres
AREAS = {
    # 512 m x 512 m town square around Höhematte / Interlaken West-Ost centre
    "interlaken": (2632100, 1170300, 2632612, 1170812),
    # Harder Kulm summit area, forest/mountain
    "harder": (2631460, 1171740, 2631716, 1171996),
    # 256 m square centred on the cell corner E 2633000 / N 1171000 (two seams)
    "seam": (2632872, 1170872, 2633128, 1171128),
    # Grimsel Hospiz: Spitallamm/Seeuferegg dams, Totensee, pass
    "grimsel": (2667900, 1156700, 2669700, 1158300),
    # Grimsel road hairpins between Handegg and Räterichsboden
    "hairpins": (2666300, 1159300, 2667700, 1162900),
    # Handegg fall
    "handegg": (2666450, 1162700, 2666750, 1162950),
    # Giessbach falls
    "giessbach": (2644400, 1175650, 2644900, 1176250),
    # Aareschlucht (gorge between Meiringen and Innertkirchen)
    "aareschlucht": (2658900, 1173950, 2659700, 1174400),
    # Aare at Interlaken (Bödeli), for the stability test
    "aare": (2631500, 1170750, 2632300, 1171100),
}


def chunks_of(name: str) -> list[tuple[int, int]]:
    if name in AREAS:
        e1, n1, e2, n2 = AREAS[name]
        x1, z2 = block_of(e1, n1)
        x2, z1 = block_of(e2, n2)
    elif name.startswith("@"):
        # @file.json: list of [cx, cz]
        return [tuple(c) for c in json.loads(Path(name[1:]).read_text())]
    else:
        x1, z1, x2, z2 = (int(v) for v in name.split(","))
    xs, zs = sorted((x1 >> 4, x2 >> 4)), sorted((z1 >> 4, z2 >> 4))
    return [(cx, cz) for cz in range(zs[0], zs[1] + 1) for cx in range(xs[0], xs[1] + 1)]


if __name__ == "__main__":
    import sys
    for a in sys.argv[1:] or AREAS:
        c = chunks_of(a)
        print(a, len(c), "chunks", f"{len(c) * 256 / 1e6:.3f} km²")
