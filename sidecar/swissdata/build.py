"""Cell assembly: registered LayerBuilders each contribute layers; core merges them and writes the cell.

Owner: sidecar-core. sidecar-tlm and sidecar-buildings add a builder module and register it in BUILDERS;
they never write cells or manifest.json themselves.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

import numpy as np

from . import swd
from .buildings import BuildingsBuilder
from .config import Config
from .landcover import LandcoverBuilder
from .overlay import OverlayBuilder
from .water import WaterBuilder


@dataclass
class LayerResult:
    layers: dict[str, np.ndarray] = field(default_factory=dict)  # tag -> array, shapes per docs/swd-format.md
    buildings: list[swd.Building] = field(default_factory=list)
    spans: dict[tuple[int, int], list[swd.Span]] = field(default_factory=dict)
    sources: dict[str, object] = field(default_factory=dict)  # merged into manifest "sources"


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


# Order matters: terrain (core) -> tlm (land cover, water, overlays, bathymetry into TERR) -> buildings -> defaults.
# Each workstream inserts its builder here; keep DefaultSurface last.
BUILDERS: list[LayerBuilder] = [WaterBuilder(), LandcoverBuilder(), OverlayBuilder(), BuildingsBuilder(), DefaultSurface()]


def assemble(e_km: int, n_km: int, cfg: Config, pinned: dict[str, object] | None = None,
             builders: list[LayerBuilder] | None = None) -> tuple[swd.Cell, dict[str, object]]:
    """Runs all builders. `pinned` is the cell's manifest "sources" (rebuilds must reuse them)."""
    acc = LayerResult()
    for b in builders if builders is not None else BUILDERS:
        r = b.build(e_km, n_km, cfg, acc, pinned)
        for tag, arr in r.layers.items():
            if tag not in b.tags:
                raise ValueError(f"{b.name} emitted undeclared layer {tag}")
            acc.layers[tag] = arr
        if r.buildings:
            acc.buildings, acc.spans = r.buildings, r.spans
        acc.sources.update(r.sources)
    cell = swd.Cell(e_km, n_km, layers=acc.layers, buildings=acc.buildings, spans=acc.spans)
    return cell, acc.sources
