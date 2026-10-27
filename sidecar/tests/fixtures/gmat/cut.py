"""Cuts the GMAT test fixtures from real tiles in $SWISSDATA_RAW and a store with SURF/LITH/CNPY/... (dev tool).

    python -I tests/fixtures/gmat/cut.py OUTDIR PNGDIR name:e_km:n_km:row:col ...   (row/col: 2 m pixel of the crop centre)

Used: meadow:2642:1168:175:225 north_slope_meadow:2642:1169:90:60 forest:2632:1171:150:225 rock_face:2667:1156:50:170
scree_cone:2643:1169:40:290 snowfield:2645:1155:230:230 shadowed_north_cliff:2642:1169:135:345
"""
import sys, json, numpy as np
from pathlib import Path
from PIL import Image
from swissdata import gmat, swd, zones
from swissdata.alti import TILE_PX
from swissdata.config import Config
K, M = 40, 132
cfg = Config.from_env()
man = json.loads((cfg.store / "manifest.json").read_text())["cells"]
out, png = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True); png.mkdir(parents=True, exist_ok=True)
for spec in sys.argv[3:]:
    name, e, n, r, c = spec.split(":"); e, n, r, c = int(e), int(n), int(r) - K // 2, int(c) - K // 2
    src = man[f"{e}_{n}"]["sources"]
    rgb = gmat.read_ortho(gmat.cache_path(cfg, gmat.ortho_href(src["ortho"])))
    alti = zones.ZonesBuilder().tile_items(e, n, src)
    dem_tiles = {d: zones.read_alti(cfg, it) if it else None for d, it in alti.items()}
    p = gmat.tile_params(rgb, dem_tiles[(0, 0)])
    hm = gmat.pixel_mosaic(dem_tiles, TILE_PX)  # 1500², own tile at (500, 500)
    dem = hm[TILE_PX + r - M:TILE_PX + r + K + M, TILE_PX + c - M:TILE_PX + c + K + M]
    base = float(np.floor(np.nanmin(dem)))
    cell = swd.decode(swd.cell_path(cfg.store, e, n).read_bytes()).layers
    pri = {k: cell[k][2 * r:2 * (r + K), 2 * c:2 * (c + K)] for k in ("SURF", "LITH", "CNPY", "WKND", "OCLS", "OFLG") if k in cell}
    np.savez_compressed(out / f"{name}.npz", e=e, n=n, r=r, c=c, rgb=rgb[r:r + K, c:c + K],
                        dem_dm=np.rint((dem - base) * 10).astype(np.uint16), base=base,
                        gains=np.array(p.gains), sun=np.array([p.sun_az, p.sun_el]), **{f"L_{k}": v for k, v in pri.items()})
    Image.fromarray(rgb[max(r - 40, 0):r + K + 40, max(c - 40, 0):c + K + 40]).resize((480, 480), Image.NEAREST).save(png / f"{name}_ctx.png")
    print(name, p, (out / f"{name}.npz").stat().st_size)
