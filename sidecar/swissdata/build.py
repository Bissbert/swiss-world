"""Cell assembly: registered LayerBuilders each contribute layers; core merges them and writes the cell.

Owner: sidecar-core. sidecar-tlm and sidecar-buildings add a builder module and register it in BUILDERS;
they never write cells or manifest.json themselves.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

import numpy as np

from . import progress, swd
from .buildings import BuildingsBuilder
from .canopy import CanopyBuilder
from .config import Config
from .landcover import LandcoverBuilder
from .overlay import OverlayBuilder
from .water import WaterBuilder
from .zones import ZonesBuilder


@dataclass
class LayerResult:
    layers: dict[str, np.ndarray] = field(default_factory=dict)  # tag -> array, shapes per docs/swd-format.md
    buildings: list[swd.Building] = field(default_factory=list)
    spans: dict[tuple[int, int], list[swd.Span]] = field(default_factory=dict)
    sources: dict[str, object] = field(default_factory=dict)  # merged into manifest "sources"
    trees: list[swd.Tree] | None = None  # TTOP; None = not emitted


class LayerBuilder(Protocol):
    name: str
    tags: tuple[str, ...]  # layers this builder may emit; two builders must not share a tag
    # earlier builders' results, so e.g. water can read TERR or tlm can merge bathymetry into TERR
    def build(self, e_km: int, n_km: int, cfg: Config, partial: LayerResult,
              pinned: dict[str, object] | None) -> LayerResult: ...


class DefaultSurface:
    """Fallback SURF (all meadow) until sidecar-tlm registers the real land-cover builder."""

    name = "default-surface"
    tags = ("SURF",)

    def build(self, e_km, n_km, cfg, partial, pinned):
        if "SURF" in partial.layers:
            return LayerResult()
        return LayerResult(layers={"SURF": np.zeros((swd.CELL, swd.CELL), dtype="u1")})


# Builder modules import LayerResult inside build() (not at module level): this file imports them here.
from .alti import AltiTerrain  # noqa: E402
from .coverage import BorderMask  # noqa: E402

# Order matters: terrain (core) -> tlm (land cover, water, overlays, bathymetry into TERR) -> zones (reads WKND)
# -> buildings -> canopy (masks the columns of all of these) -> defaults.
# Each workstream inserts its builder here; keep DefaultSurface last.
BUILDERS: list[LayerBuilder] = [AltiTerrain(), BorderMask(), WaterBuilder(), LandcoverBuilder(), ZonesBuilder(),
                                OverlayBuilder(), BuildingsBuilder(), CanopyBuilder(), DefaultSurface()]


def assemble(e_km: int, n_km: int, cfg: Config, pinned: dict[str, object] | None = None,
             builders: list[LayerBuilder] | None = None) -> tuple[swd.Cell, dict[str, object]]:
    """Runs all builders. `pinned` is the cell's manifest "sources" (rebuilds must reuse them)."""
    acc = LayerResult()
    todo = builders if builders is not None else BUILDERS
    prog = progress.current()
    for i, b in enumerate(todo):
        if prog is not None:
            prog.stage, prog.step, prog.steps = b.name, i + 1, len(todo)
        r = b.build(e_km, n_km, cfg, acc, pinned)
        for tag, arr in r.layers.items():
            if tag not in b.tags:
                raise ValueError(f"{b.name} emitted undeclared layer {tag}")
            acc.layers[tag] = arr
        if r.buildings:
            acc.buildings, acc.spans = r.buildings, r.spans
        if r.trees is not None:
            if "TTOP" not in b.tags:
                raise ValueError(f"{b.name} emitted undeclared table TTOP")
            acc.trees = r.trees
        acc.sources.update(r.sources)
    cell = swd.Cell(e_km, n_km, layers=acc.layers, buildings=acc.buildings, spans=acc.spans, trees=acc.trees)
    return cell, acc.sources


class PinChanged(RuntimeError):
    pass


# Pins a builder may replace: their upstream snapshot can vanish for good (GWR publishes daily and keeps no history),
# so the builder falls back to the newest one it has and re-pins (gwr.load_pinned) instead of failing the cell.
SOFT_PINS = frozenset({"gwr"})


def merge_sources(pinned: dict[str, object] | None, new: dict[str, object]) -> dict[str, object]:
    """Manifest sources after a rebuild: the old pins stay (a builder must not change one), new keys are added
    and thereby pinned. Keys no builder re-emits (e.g. a former builder's) are kept as well. SOFT_PINS take the
    builder's new value."""
    old = pinned or {}
    changed = sorted(k for k in old.keys() & new.keys() if old[k] != new[k] and k not in SOFT_PINS)
    if changed:
        raise PinChanged(f"builders changed pinned sources {changed}")
    return {**old, **new}
