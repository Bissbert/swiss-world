"""WLVL/WKND from swissTLM3D, plus lake beds and river beds carved into TERR. Owner: sidecar-tlm.

- Lakes: the TLM land-cover polygons "Stehende Gewaesser"; the level is the median Z of the polygon,
  rounded to whole metres (Brienzersee 563.7 -> 564 m). Reservoir when a dam touches it, pond when small. The bed comes from
  swissBATHY3D (bathy.py) or a synthetic depth that grows with the distance to the shore.
- Rivers and streams: TLM watercourse lines (digitised downstream) form a network. Each line gets a water
  level that is monotonic downstream across confluences and floored to whole metres, so the surface is
  stepped (flat runs, 1 m drops). Wide rivers use the land-cover polygon "Fliessgewaesser" for their
  footprint; other lines are buffered to 1 m (unnamed) or 2 m (named). TERR is carved below the level so
  the water is visible, which also keeps narrow gorges (Aareschlucht) wet and narrow.
- Waterfalls: TLM watercourse parts with VERLAUF "Wasserfall" and TLM single objects "Wasserfall"
  (Giessbach, Handegg, Reichenbach) become WKND 6.

Levels depend only on TLM data, never on the cell's TERR, so neighbouring cells agree at their seams.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import numpy as np
import shapely

from . import bathy, swd, tlm
from .config import Config

WKND_LAKE, WKND_RIVER, WKND_STREAM, WKND_RESERVOIR, WKND_POND, WKND_FALL = 1, 2, 3, 4, 5, 6

LC_RIVER, LC_LAKE = 5, 10  # TLM_BODENBEDECKUNG: Fliessgewaesser, Stehende Gewaesser
FLOW_KINDS = (0, 4)  # TLM_FLIESSGEWAESSER: Bisse/Suone, Fliessgewaesser
VERLAUF_SURFACE, VERLAUF_FALL = 100, 500
DAM_KINDS = (0, 1)  # TLM_STAUBAUTE: Staumauer, Staudamm
FALL_OBJECT = 9  # TLM_EINZELOBJEKT: Wasserfall

POND_MAX_M2 = 5_000
STREAM_HALF_WIDTH = {False: 0.71, True: 1.0}  # by "has a name"; 0.71 keeps 1 m lines 8-connected
STREAM_DEPTH, RIVER_DEPTH = 1.0, 2.0
CARVE_EXTRA = 1.5  # carve TERR nodes this far beyond the water so bilinear columns stay below the level
SYNTH_MIN, SYNTH_SLOPE, SYNTH_MAX = 1.5, 0.12, 60.0  # synthetic lake depth = min(MIN + SLOPE·d, MAX)
FALL_OBJECT_RADIUS = 4.0
NEAR = 250.0  # m around the cell searched for river centre lines


@dataclass
class Lake:
    geom: object
    level: float
    kind: int


def lakes_near(cfg: Config, e_km: int, n_km: int) -> list[Lake]:
    box = tlm.cell_box(e_km, n_km, 2)  # edge TERR nodes reach 1 m beyond the cell
    polys = [g for g, p in tlm.read(cfg, "TLM_BODENBEDECKUNG", tlm.cell_bbox(e_km, n_km, 2))
             if p["OBJEKTART"] == LC_LAKE and g.intersects(box)]
    if not polys:
        return []
    dams = [g for g, p in tlm.read(cfg, "TLM_STAUBAUTE", tlm.cell_bbox(e_km, n_km, 3000))
            if p["OBJEKTART"] in DAM_KINDS]
    lakes = []
    for g in polys:
        # Whole metres like the river steps: a Minecraft water surface is a block top (Brienzersee 563.7 -> 564).
        level = float(np.round(np.median(shapely.get_coordinates(g, include_z=True)[:, 2])))
        if any(d.distance(g) < 30 for d in dams):
            kind = WKND_RESERVOIR
        elif g.area < POND_MAX_M2:
            kind = WKND_POND
        else:
            kind = WKND_LAKE
        lakes.append(Lake(shapely.force_2d(g), level, kind))
    return lakes


@dataclass(frozen=True)
class RiverNetwork:
    lines: np.ndarray  # LineString Z, Z = stepped water level, densified to <= 1 m
    fall: np.ndarray  # bool, VERLAUF Wasserfall
    named: np.ndarray  # bool
    tree: shapely.STRtree


@lru_cache(maxsize=2)
def river_network(extract: Path) -> RiverNetwork:
    """Water levels for every surface watercourse in the extract, monotonic downstream across the network."""
    segs: list[tuple[np.ndarray, bool, bool]] = []
    for g, p in tlm.read_all(extract, "TLM_FLIESSGEWAESSER"):
        if p["OBJEKTART"] not in FLOW_KINDS or p["VERLAUF"] not in (VERLAUF_SURFACE, VERLAUF_FALL):
            continue
        for part in tlm.single_lines(g):
            c = shapely.get_coordinates(shapely.segmentize(part, 1.0), include_z=True)
            if len(c) >= 2:
                segs.append((c, p["VERLAUF"] == VERLAUF_FALL, p["NAME"] is not None))
    levels = propagate_levels([c for c, _, _ in segs])
    lines = np.array([shapely.linestrings(c[:, 0], c[:, 1], lv) for (c, _, _), lv in zip(segs, levels)])
    return RiverNetwork(lines, np.array([f for _, f, _ in segs], dtype=bool),
                        np.array([n for _, _, n in segs], dtype=bool), shapely.STRtree(lines))


def propagate_levels(coords: list[np.ndarray]) -> list[np.ndarray]:
    """Per line (x, y, z coordinates, digitised downstream): z made non-increasing along the line and
    never above the end level of any line flowing into its start, then floored to whole metres."""
    def key(xy):
        return (round(float(xy[0]), 1), round(float(xy[1]), 1))

    ending_at = defaultdict(list)
    for k, c in enumerate(coords):
        ending_at[key(c[-1])].append(k)
    preds = [ending_at.get(key(c[0]), []) for c in coords]
    succs = defaultdict(list)
    for k, ps in enumerate(preds):
        for p in ps:
            succs[p].append(k)
    indeg = [len(ps) for ps in preds]
    out_level = [np.inf] * len(coords)
    result: list[np.ndarray | None] = [None] * len(coords)
    ready = [k for k in range(len(coords)) if indeg[k] == 0]
    done = 0
    while done < len(coords):
        if not ready:  # a cycle (braided channel): continue with the lowest unprocessed index
            ready = [min(k for k in range(len(coords)) if result[k] is None)]
        k = ready.pop()
        if result[k] is not None:
            continue
        cap = min((out_level[p] for p in preds[k]), default=np.inf)
        z = np.minimum.accumulate(np.minimum(coords[k][:, 2], cap))
        result[k] = np.floor(z)
        out_level[k] = float(result[k][-1])
        done += 1
        for s in succs[k]:
            indeg[s] -= 1
            if indeg[s] == 0:
                ready.append(s)
    return result


class WaterBuilder:
    name = "tlm-water"
    tags = ("WLVL", "WKND")

    def build(self, e_km, n_km, cfg, partial, pinned):
        from .build import LayerResult  # build.py imports this module to register the builder

        tlm.check_pinned(pinned)
        lakes = lakes_near(cfg, e_km, n_km)
        net = river_network(tlm.ensure_extract(cfg))
        near = net.tree.query(tlm.cell_box(e_km, n_km, NEAR))
        near = near[np.argsort(near)]
        box = tlm.cell_box(e_km, n_km, 5)
        river_polys = [shapely.force_2d(g) for g, p in tlm.read(cfg, "TLM_BODENBEDECKUNG", tlm.cell_bbox(e_km, n_km))
                       if p["OBJEKTART"] == LC_RIVER and g.intersects(box)]

        # Footprints, later shapes win: streams < river areas < lakes.
        shapes = [(net.lines[k].buffer(STREAM_HALF_WIDTH[bool(net.named[k])]), WKND_STREAM) for k in near
                  if net.lines[k].intersects(box)]
        shapes += [(g, WKND_RIVER) for g in river_polys]
        kind = tlm.burn(shapes, e_km, n_km, dtype="uint8")
        lake_idx = tlm.burn([(lk.geom, i + 1) for i, lk in enumerate(lakes)], e_km, n_km)
        level = np.full(kind.shape, np.nan)

        flowing = (kind > 0) & (lake_idx == 0)
        if flowing.any() and len(near):
            dz, dx = np.nonzero(flowing)
            lv, fall = self._levels_at(net, near, tlm.column_points(e_km, n_km, dz, dx))
            level[dz, dx] = lv
            kind[dz[fall], dx[fall]] = WKND_FALL
            self._mark_fall_objects(cfg, e_km, n_km, kind, flowing)
        else:
            kind[flowing] = 0
        for i, lk in enumerate(lakes):
            m = lake_idx == i + 1
            level[m] = lk.level
            kind[m] = lk.kind

        sources = {"tlm": tlm.TLM_ITEM, "bathy": []}
        if "TERR" in partial.layers:
            terr = swd.decode_height(partial.layers["TERR"])
            sources["bathy"] = self._lake_beds(cfg, e_km, n_km, terr, lakes, pinned)
            self._carve_rivers(e_km, n_km, terr, net, near, river_polys)
            partial.layers["TERR"] = swd.encode_height(terr)
            dry = ~(tlm.terr_columns(partial.layers["TERR"]) < level)  # e.g. lake-shore columns on the bank
            kind[dry] = 0
            level[dry] = np.nan
        kind[np.isnan(level)] = 0
        return LayerResult(layers={"WLVL": swd.encode_height(level), "WKND": kind}, sources=sources)

    @staticmethod
    def _levels_at(net: RiverNetwork, near: np.ndarray, pts: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        sub = shapely.STRtree(net.lines[near])
        idx = sub.query_nearest(pts, all_matches=False)[1]
        k = near[idx]
        # floor again: between two vertices of different whole-metre levels the interpolation is fractional
        return np.floor(tlm.value_along(net.lines[k], pts)), net.fall[k]

    @staticmethod
    def _mark_fall_objects(cfg, e_km, n_km, kind, flowing):
        pts = [g for g, p in tlm.read(cfg, "TLM_EINZELOBJEKT", tlm.cell_bbox(e_km, n_km, FALL_OBJECT_RADIUS))
               if p["OBJEKTART"] == FALL_OBJECT]
        if pts:
            near_fall = tlm.burn([(shapely.force_2d(p).buffer(FALL_OBJECT_RADIUS), 1) for p in pts], e_km, n_km,
                                 dtype="uint8")
            kind[(near_fall == 1) & flowing] = WKND_FALL

    @staticmethod
    def _lake_beds(cfg, e_km, n_km, terr, lakes, pinned) -> list[str]:
        if not lakes:
            return []
        idx = tlm.burn_nodes([(lk.geom, i + 1) for i, lk in enumerate(lakes)], e_km, n_km)
        level = np.full(terr.shape, np.nan)
        for i, lk in enumerate(lakes):
            level[idx == i + 1] = lk.level
        merged, used = bathy.merge(cfg, e_km, n_km, terr, level,
                                   pinned.get("bathy") if pinned and "bathy" in pinned else None)
        for i, lk in enumerate(lakes):
            j, ii = np.nonzero((idx == i + 1) & ~merged)
            if len(j):
                d = shapely.distance(tlm.node_points(e_km, n_km, j, ii), lk.geom.boundary)
                bed = lk.level - np.minimum(SYNTH_MIN + SYNTH_SLOPE * d, SYNTH_MAX)
                terr[j, ii] = np.fmin(terr[j, ii], bed)
        return used

    def _carve_rivers(self, e_km, n_km, terr, net, near, river_polys):
        shapes = [(net.lines[k].buffer(STREAM_HALF_WIDTH[bool(net.named[k])] + CARVE_EXTRA), 1) for k in near]
        shapes += [(g, 2) for g in river_polys]
        m = tlm.burn_nodes(shapes, e_km, n_km, dtype="uint8")
        j, i = np.nonzero(m)
        if not len(j) or not len(near):
            return
        lv, _ = self._levels_at(net, near, tlm.node_points(e_km, n_km, j, i))
        depth = np.where(m[j, i] == 2, RIVER_DEPTH, STREAM_DEPTH)
        terr[j, i] = np.fmin(terr[j, i], lv - depth)
