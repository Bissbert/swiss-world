"""Dev tool: PNG previews of the sidecar-tlm layers for a cell. Owner: sidecar-tlm.

    uv run --group dev python -I -m swissdata.tlm_preview 2646 1178 2668 1157

Uses sidecar-core's terrain builder (swissdata.alti) when it is present, otherwise StandInTerrain: the
cell's own swissALTI3D 2 m tile without neighbour edges (edge nodes are the mean of the pixels inside).
"""

from __future__ import annotations

import sys
from pathlib import Path

import httpx
import numpy as np
import rasterio

from . import build, swd, tlm
from .config import Config
from .fetch import USER_AGENT, cache_path, fetch

STAC = "https://data.geo.admin.ch/api/stac/v0.9/collections/ch.swisstopo.swissalti3d/items"


class StandInTerrain:
    name = "alti-stand-in"
    tags = ("TERR",)

    def build(self, e_km, n_km, cfg, partial, pinned):
        item = newest_alti(cfg, e_km, n_km)
        with rasterio.open(fetch(cfg, alti_url(item))) as src:
            px = src.read(1, masked=True).filled(np.nan).astype(np.float64)
        p = np.pad(px, 1, mode="edge")  # pixel (r, c) has its centre at E = e0 + 2c + 1, N = n0 + 999 - 2r
        nodes = (p[:-1, :-1] + p[:-1, 1:] + p[1:, :-1] + p[1:, 1:]) / 4
        return build.LayerResult(layers={"TERR": swd.encode_height(nodes)}, sources={"alti": item})


def alti_url(item: str) -> str:
    return f"https://data.geo.admin.ch/ch.swisstopo.swissalti3d/{item}/{item}_2_2056_5728.tif"


def newest_alti(cfg: Config, e_km: int, n_km: int) -> str:
    """Newest swissALTI3D item for the tile; an already cached one wins, so tests run offline."""
    items = [f"swissalti3d_{year}_{e_km}-{n_km}" for year in range(2026, 2018, -1)]
    for item in items:
        if cache_path(cfg, alti_url(item)).exists():
            return item
    for item in items:
        r = httpx.get(f"{STAC}/{item}", headers={"User-Agent": USER_AGENT}, timeout=30)
        if r.status_code == 200:
            return item
    raise LookupError(f"no swissALTI3D tile {e_km}-{n_km}")


def terrain_builder():
    try:
        from .alti import AltiTerrain
    except ImportError:
        return StandInTerrain()
    return AltiTerrain()


def builders() -> list:
    return [terrain_builder(), *build.BUILDERS]


SURF_COLOURS = ["#9bd06a", "#3f8f3a", "#1f5e2e", "#2f7a35", "#8a8a8a", "#b5ab9a", "#e8f4ff", "#2a6fd6",
                "#4a9bea", "#6f8f6a", "#555555", "#7fbf7f", "#c9a0dc", "#8e3b6e", "#a8c27a", "#e0d2a0",
                "#e5cf6a", "#c2d68a"]


def preview(e_km: int, n_km: int, cfg: Config, out: Path | None = None) -> list[Path]:
    """Writes one PNG per layer to `out` (default: previews/ next to the shared raw cache)."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.colors import ListedColormap

    cell, _ = build.assemble(e_km, n_km, cfg, builders=builders())
    L = cell.layers
    out = out or cfg.raw.parent / "previews"
    out.mkdir(parents=True, exist_ok=True)
    extent = (e_km * 1000, e_km * 1000 + 1000, n_km * 1000, n_km * 1000 + 1000)
    terr = swd.decode_height(L["TERR"])
    wlvl = swd.decode_height(L["WLVL"])
    oelv = swd.decode_height(L["OELV"])
    panels = {
        "TERR": (terr, dict(cmap="terrain")),
        "SURF": (L["SURF"], dict(cmap=ListedColormap(SURF_COLOURS), vmin=-0.5, vmax=17.5, interpolation="nearest")),
        "WKND": (np.ma.masked_equal(L["WKND"], 0), dict(cmap="tab10", vmin=0.5, vmax=10.5, interpolation="nearest")),
        "WLVL": (wlvl, dict(cmap="viridis")),
        "WDEPTH": (wlvl - tlm.terr_columns(L["TERR"]), dict(cmap="Blues")),
        "OCLS": (np.ma.masked_equal(L["OCLS"], 0), dict(cmap="tab20", vmin=0.5, vmax=20.5, interpolation="nearest")),
        "OELV": (oelv, dict(cmap="plasma")),
        "OFLG": (np.ma.masked_equal(L["OFLG"], 0), dict(cmap="tab20b", vmin=0.5, vmax=31.5, interpolation="nearest")),
    }
    paths = []
    for tag, (a, kw) in panels.items():
        fig, ax = plt.subplots(figsize=(8, 8), dpi=125)
        im = ax.imshow(a, extent=extent, **kw)
        fig.colorbar(im, ax=ax, shrink=0.8)
        ax.set_title(f"{e_km}_{n_km} {tag}")
        p = out / f"{e_km}_{n_km}_{tag}.png"
        fig.savefig(p, bbox_inches="tight")
        plt.close(fig)
        paths.append(p)
    return paths


def main(argv: list[str]) -> None:
    cfg = Config.from_env()
    for e, n in zip(argv[0::2], argv[1::2]):
        for p in preview(int(e), int(n), cfg):
            print(p)


if __name__ == "__main__":
    main(sys.argv[1:])
