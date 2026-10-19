"""SURF from swissTLM3D land cover (docs/swd-format.md §4). Owner: sidecar-tlm.

TLM has no "urban" or "farmland" land-cover class: everything not covered by a BODENBEDECKUNG polygon is
open land. Urban comes from settlement-name areas (urban green) plus building footprints, parking and
traffic areas (urban sealed); open land above ALPINE_M is alpine pasture, below it meadow. Forest type
is not in TLM either, so it follows altitude. Water columns follow WKND from the water builder, so SURF
and WKND always agree.
"""

from __future__ import annotations

import numpy as np
import shapely

from . import swd, tlm

MEADOW, BROADLEAF, CONIFER, MIXED, ROCK, SCREE, GLACIER, LAKE, RIVER, WETLAND = range(10)
URBAN_SEALED, URBAN_GREEN, ORCHARD, VINEYARD, SPARSE, GRAVEL, FARMLAND, ALPINE = range(10, 18)

ALPINE_M = 1800.0
BROADLEAF_BELOW_M, CONIFER_ABOVE_M = 900.0, 1400.0
GRAVEL_BELOW_M = 1000.0  # loose rock in the valleys is gravel banks, higher up it is scree
BUILDING_MARGIN = 1.5
BUILDING_UNDERGROUND = 16  # TLM_GEBAEUDE: Unterirdisch

FOREST = -1  # placeholder resolved by altitude
# TLM_BODENBEDECKUNG OBJEKTART -> SURF
COVER = {
    1: ROCK, 2: ROCK,  # Fels, Fels locker
    3: SCREE, 4: SCREE, 7: SCREE, 8: SCREE,  # Felsbloecke (locker), Lockergestein (locker)
    5: RIVER, 10: LAKE,
    6: SPARSE, 13: SPARSE,  # Gebueschwald, Wald offen
    12: FOREST, 14: FOREST,  # Wald, Gehoelzflaeche
    9: GLACIER, 15: GLACIER,  # Gletscher, Schneefeld Toteis
    11: WETLAND,
}
LOOSE_ROCK = (7, 8)
# TLM_NUTZUNGSAREAL: Obstanlage, Reben, Friedhof, Oeffentliches Parkareal, Schrebergartenareal
USAGE = {15: ORCHARD, 17: VINEYARD, 6: URBAN_GREEN, 16: URBAN_GREEN, 18: URBAN_GREEN}
SEALED_TRAFFIC = (5, 8, 10)  # TLM_VERKEHRSAREAL: Oeffentl. Parkplatz, Verkehrsflaeche, Privater Parkplatz
WKND_TO_SURF = {1: LAKE, 4: LAKE, 5: LAKE, 2: RIVER, 3: RIVER, 6: RIVER}


class LandcoverBuilder:
    name = "tlm-landcover"
    tags = ("SURF",)

    def build(self, e_km, n_km, cfg, partial, pinned):
        from .build import LayerResult  # build.py imports this module to register the builder

        tlm.check_pinned(pinned)
        bbox = tlm.cell_bbox(e_km, n_km)
        alt = tlm.terr_columns(partial.layers["TERR"]) if "TERR" in partial.layers else None

        shapes = [(g, URBAN_GREEN) for g, _ in tlm.read(cfg, "TLM_SIEDLUNGSNAME", bbox)
                  if g.geom_type in ("Polygon", "MultiPolygon")]
        cover = [(g, p["OBJEKTART"]) for g, p in tlm.read(cfg, "TLM_BODENBEDECKUNG", bbox)
                 if p["OBJEKTART"] in COVER]
        # Burn the raw TLM code (+100) so altitude rules can be applied per column afterwards.
        shapes += [(g, 100 + code) for g, code in cover]
        shapes += [(g, USAGE[p["OBJEKTART"]]) for g, p in tlm.read(cfg, "TLM_NUTZUNGSAREAL", bbox)
                   if p["OBJEKTART"] in USAGE]
        shapes += [(g, URBAN_SEALED) for g, p in tlm.read(cfg, "TLM_VERKEHRSAREAL", bbox)
                   if p["OBJEKTART"] in SEALED_TRAFFIC]
        shapes += [(shapely.force_2d(g).buffer(BUILDING_MARGIN), URBAN_SEALED)
                   for g, p in tlm.read(cfg, "TLM_GEBAEUDE_FOOTPRINT", tlm.cell_bbox(e_km, n_km, BUILDING_MARGIN))
                   if p["OBJEKTART"] != BUILDING_UNDERGROUND]
        raw = tlm.burn([(shapely.force_2d(g), v) for g, v in shapes], e_km, n_km, fill=-1)
        surf = classify(raw, alt)

        if "WKND" in partial.layers:
            wknd = partial.layers["WKND"]
            for k, s in WKND_TO_SURF.items():
                surf[wknd == k] = s
            # TLM water polygons the water builder left dry (above the carved level) become gravel banks.
            stray = (wknd == 0) & np.isin(surf, (LAKE, RIVER))
            surf[stray] = GRAVEL
        return LayerResult(layers={"SURF": surf}, sources={"tlm": tlm.TLM_ITEM})


def classify(raw: np.ndarray, alt: np.ndarray | None) -> np.ndarray:
    """`raw`: -1 open land, 100 + TLM land-cover code, or a SURF value. `alt`: column height (m) or None."""
    a = np.full(raw.shape, 0.0) if alt is None else np.nan_to_num(alt, nan=0.0)
    surf = np.where(a > ALPINE_M, ALPINE, MEADOW).astype("u1")
    direct = (raw >= 0) & (raw < 100)
    surf[direct] = raw[direct]
    for code, s in COVER.items():
        m = raw == 100 + code
        if not m.any():
            continue
        if s == FOREST:
            surf[m] = np.where(a[m] < BROADLEAF_BELOW_M, BROADLEAF, np.where(a[m] > CONIFER_ABOVE_M, CONIFER, MIXED))
        elif code in LOOSE_ROCK:
            surf[m] = np.where(a[m] < GRAVEL_BELOW_M, GRAVEL, SCREE)
        else:
            surf[m] = s
    return surf
