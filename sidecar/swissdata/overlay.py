"""OCLS/OELV/OFLG from swissTLM3D roads, railways, dams and traffic areas (docs/swd-format.md §5). Owner: sidecar-tlm.

Every feature is buffered to its width and burnt once, in priority order (later wins): tunnels, areas,
roads from minor to major, railways, dams, bridges. Bridges, tunnels and roads over dams carry their
deck/floor height from the TLM line Z in OELV; dams carry their crest (highest Z) and the dam-body flag.
Where a road bridge spans a ground-level railway, the column keeps the road deck (OCLS, OELV, bit0) and
also the rail-centre bit2, which then means a rail on the terrain under the deck (see NOTES-sidecar-tlm.md).
Everything else follows the terrain. The grid is burnt one column wider on every side so the edge flag
(a road column next to a non-overlay column) agrees across cell seams.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import shapely

from . import swd, tlm

MOTORWAY, MAIN, SECONDARY, MINOR, TRACK, FOOTPATH, STEPS = 1, 2, 3, 4, 5, 6, 7
RAIL, RACK, PLATFORM, DAM_CREST, RUNWAY, PITCH, PARKING = 8, 9, 10, 11, 12, 13, 14

# TLM_STRASSE OBJEKTART -> (OCLS, width m). Skipped: Autozug, Faehre, Markierte Spur, Klettersteig.
ROADS = {
    2: (MOTORWAY, 11.0),  # Autobahn
    21: (MAIN, 10.0),  # Autostrasse
    0: (MAIN, 7.0), 1: (MAIN, 7.0), 3: (MAIN, 7.0), 4: (MAIN, 7.0),  # Aus-/Einfahrt, Raststaette, Verbindung
    8: (MAIN, 10.0), 20: (MAIN, 8.0),  # 10m, 8m Strasse
    9: (SECONDARY, 6.0), 10: (MINOR, 4.0), 11: (MINOR, 3.0),  # 6m, 4m, 3m Strasse
    5: (MINOR, 4.0), 6: (MINOR, 4.0), 23: (MINOR, 4.0), 12: (MINOR, 6.0),  # Zufahrt, Dienstzufahrt, Provisorium, Platz
    15: (FOOTPATH, 2.0), 18: (FOOTPATH, 2.0), 16: (FOOTPATH, 1.0), 17: (FOOTPATH, 1.0),  # 2m/1m Weg (Fragment)
}
UNPAVED = 200  # TLM_BELAGSART: Natur
UNPAVED_TRACK = (10, 11)  # 4m/3m Strasse without hard surface are tracks
ROAD_BRIDGE = (200, 300, 400, 450, 600)  # Bruecke (mit Galerie, gedeckt, mit Treppe), Steg
ROAD_TUNNEL = (1000, 1100, 1200)  # Tunnel, Unterfuehrung (mit Treppe)
ROAD_DAM = (500, 800)  # Staudamm, Staumauer/Wehr
ROAD_STEPS = 900  # Treppe

# TLM_EISENBAHN OBJEKTART -> bed width m: Normalspur, Schmalspur, Schmalspur mit Normalspur, Kleinbahn
RAIL_WIDTH = {0: 4.0, 2: 3.0, 4: 4.0, 5: 2.0}
RAIL_BRIDGE = (200, 300, 500)  # Bruecke, Bruecke mit Galerie, Gedeckte Bruecke
RAIL_TUNNEL = (800, 900)  # Tunnel, Unterfuehrung
RAIL_DAM = (600, 700)  # Staudamm, Staumauer/Wehr

DAMS = (0, 1, 3)  # TLM_STAUBAUTE: Staumauer, Staudamm, Wehr
DAM_LINE_WIDTH = 4.0
PARKING_AREAS = (5, 10)  # TLM_VERKEHRSAREAL: Oeffentlicher/Privater Parkplatz
PLATFORM_KIND, HARD_RUNWAYS = 4, (3, 6)  # TLM_VERKEHRSBAUTE_PLY: Perron; Hartbelagpiste, Rollfeld Hartbelag

MIN_HALF_WIDTH = 0.71  # keeps 1 m ways 8-connected when burnt at column centres
EDGE_CLASSES = (MOTORWAY, MAIN, SECONDARY, MINOR, TRACK)
MARGIN = 20.0


@dataclass
class Feature:
    geom: object  # 2D footprint
    ocls: int
    flags: int = 0
    line: object = None  # 3D line whose Z gives OELV
    elev: float = np.nan  # constant OELV (dams)
    rail_line: object = None  # centre line for OFLG.bit2


class OverlayBuilder:
    name = "tlm-overlay"
    tags = ("OCLS", "OELV", "OFLG")

    def build(self, e_km, n_km, cfg, partial, pinned):
        from .build import LayerResult  # build.py imports this module to register the builder

        tlm.check_pinned(pinned)
        feats = collect(cfg, e_km, n_km)
        ocls, oelv, oflg = rasterise(feats, e_km, n_km)
        return LayerResult(layers={"OCLS": ocls, "OELV": oelv, "OFLG": oflg}, sources={"tlm": tlm.TLM_ITEM})


def road_class(p: dict) -> tuple[int, float] | None:
    if p["KUNSTBAUTE"] == ROAD_STEPS and p["OBJEKTART"] in ROADS:
        return STEPS, ROADS[p["OBJEKTART"]][1]
    c = ROADS.get(p["OBJEKTART"])
    if c and p["OBJEKTART"] in UNPAVED_TRACK and p["BELAGSART"] == UNPAVED:
        return TRACK, c[1]
    return c


def rail_class(p: dict) -> int:
    no_rails = (p["ZAHNRADBAHN"] == tlm.BOOL_YES or p["STANDSEILBAHN"] == tlm.BOOL_YES
                or p["AUSSER_BETRIEB"] == tlm.BOOL_YES)
    return RACK if no_rails else RAIL


def _buffer(line, width: float):
    # Round caps: consecutive TLM segments meet at angles, and flat caps would leave a notch at every joint.
    return shapely.force_2d(line).buffer(max(width / 2, MIN_HALF_WIDTH))


def collect(cfg, e_km: int, n_km: int) -> list[Feature]:
    """All overlay features near the cell, in burn order."""
    bbox = tlm.cell_bbox(e_km, n_km, MARGIN)
    tunnels, areas, roads, rails, dams, bridges = [], [], [], [], [], []

    for g, p in tlm.read(cfg, "TLM_STRASSE", bbox):
        c = road_class(p)
        if c is None:
            continue
        ocls, width = c
        for part in tlm.single_lines(g):
            f = Feature(_buffer(part, width), ocls)
            k = p["KUNSTBAUTE"]
            if k in ROAD_TUNNEL:
                f.flags, f.line = swd.OFLG_TUNNEL, part
                tunnels.append(f)
            elif k in ROAD_BRIDGE:
                f.flags, f.line = swd.OFLG_BRIDGE, part
                bridges.append(f)
            else:
                if k in ROAD_DAM:
                    f.line = part
                roads.append(f)
    roads.sort(key=lambda f: -f.ocls)  # minor first, so major roads win at junctions
    bridges.sort(key=lambda f: -f.ocls)

    for g, p in tlm.read(cfg, "TLM_EISENBAHN", bbox):
        ocls = rail_class(p)
        width = RAIL_WIDTH.get(p["OBJEKTART"], 3.0)
        for part in tlm.single_lines(g):
            f = Feature(_buffer(part, width), ocls, rail_line=part if ocls == RAIL else None)
            k = p["KUNSTBAUTE"]
            if k in RAIL_TUNNEL:
                f.flags, f.line = swd.OFLG_TUNNEL, part
                tunnels.append(f)
            elif k in RAIL_BRIDGE:
                f.flags, f.line = swd.OFLG_BRIDGE, part
                bridges.append(f)
            else:
                if k in RAIL_DAM:
                    f.line = part
                rails.append(f)

    for g, p in tlm.read(cfg, "TLM_STAUBAUTE", bbox):
        if p["OBJEKTART"] not in DAMS:
            continue
        crest = float(shapely.get_coordinates(g, include_z=True)[:, 2].max())
        fp = shapely.force_2d(g)
        if fp.geom_type in ("LineString", "MultiLineString"):
            fp = fp.buffer(DAM_LINE_WIDTH / 2, cap_style="flat")
        dams.append(Feature(fp, DAM_CREST, swd.OFLG_DAM, elev=crest))

    for g, p in tlm.read(cfg, "TLM_VERKEHRSAREAL", bbox):
        if p["OBJEKTART"] in PARKING_AREAS:
            areas.append(Feature(shapely.force_2d(g), PARKING))
    for g, _ in tlm.read(cfg, "TLM_SPORTBAUTE_PLY", bbox):
        areas.append(Feature(shapely.force_2d(g), PITCH))
    for g, p in tlm.read(cfg, "TLM_VERKEHRSBAUTE_PLY", bbox):
        if p["OBJEKTART"] == PLATFORM_KIND:
            areas.append(Feature(shapely.force_2d(g), PLATFORM))
        elif p["OBJEKTART"] in HARD_RUNWAYS:
            areas.append(Feature(shapely.force_2d(g), RUNWAY))

    return tunnels + areas + roads + rails + dams + bridges


def rasterise(feats: list[Feature], e_km: int, n_km: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    pad = 1
    idx = tlm.burn([(f.geom, i + 1) for i, f in enumerate(feats)], e_km, n_km, pad=pad)
    ocls_lut = np.array([0] + [f.ocls for f in feats], dtype="u1")
    flag_lut = np.array([0] + [f.flags for f in feats], dtype="u1")
    ocls_p = ocls_lut[idx]
    oflg_p = flag_lut[idx]

    edge = np.isin(ocls_p, EDGE_CLASSES) & ((oflg_p & swd.OFLG_TUNNEL) == 0)
    bare = ocls_p == 0
    nb = np.zeros_like(bare)
    nb[1:, :] |= bare[:-1, :]
    nb[:-1, :] |= bare[1:, :]
    nb[:, 1:] |= bare[:, :-1]
    nb[:, :-1] |= bare[:, 1:]
    oflg_p[edge & nb] |= swd.OFLG_EDGE

    idx, ocls, oflg = (a[pad:-pad, pad:-pad] for a in (idx, ocls_p, oflg_p))
    oflg = oflg.copy()

    elev = np.full(idx.shape, np.nan)
    for i, f in enumerate(feats):
        if np.isfinite(f.elev):
            elev[idx == i + 1] = f.elev
    with_line = np.array([False] + [f.line is not None for f in feats])
    dz, dx = np.nonzero(with_line[idx])
    if len(dz):
        lines = np.array([None] + [f.line for f in feats], dtype=object)[idx[dz, dx]]
        elev[dz, dx] = tlm.value_along(lines, tlm.column_points(e_km, n_km, dz, dx))

    for i, f in enumerate(feats):
        if f.rail_line is None:
            continue
        for dz_, dx_ in grid_path(f.rail_line, e_km, n_km):
            if (idx[dz_, dx_] == i + 1 or ocls[dz_, dx_] == RAIL
                    or (f.flags == 0 and under_road_bridge(ocls[dz_, dx_], oflg[dz_, dx_]))):
                oflg[dz_, dx_] |= swd.OFLG_RAIL
    return ocls, swd.encode_height(elev), oflg


def under_road_bridge(ocls: int, oflg: int) -> bool:
    """A road deck (OCLS/OELV of the column) spans a ground-level rail: bit2 stays set for the rail below."""
    return bool(oflg & swd.OFLG_BRIDGE) and ocls not in (RAIL, RACK)


def grid_path(line, e_km: int, n_km: int) -> list[tuple[int, int]]:
    """Columns (dz, dx) under `line`, 4-connected (a diagonal step gets an extra corner column), clipped to the cell."""
    c = shapely.get_coordinates(shapely.segmentize(shapely.force_2d(line), 0.25))
    dx = np.floor(c[:, 0] - e_km * 1000).astype(int)
    dz = np.floor(n_km * 1000 + swd.CELL - c[:, 1]).astype(int)
    keep = np.ones(len(dx), dtype=bool)
    keep[1:] = (dx[1:] != dx[:-1]) | (dz[1:] != dz[:-1])
    dx, dz = dx[keep], dz[keep]
    out = [(int(dz[0]), int(dx[0]))]
    for k in range(1, len(dx)):
        pz, px = out[-1]
        z, x = int(dz[k]), int(dx[k])
        # Diagonal (or, at sample gaps, longer) step: go along x first, then along z.
        while px != x:
            px += 1 if x > px else -1
            out.append((pz, px))
        while pz != z:
            pz += 1 if z > pz else -1
            out.append((pz, px))
    return [(z, x) for z, x in out if 0 <= z < swd.CELL and 0 <= x < swd.CELL]
