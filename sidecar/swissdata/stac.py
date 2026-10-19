"""swisstopo STAC lookups (https://data.geo.admin.ch/api/stac/v1). Owner: sidecar-core.

Only resolves *which* item to use; the choice is pinned by the caller (manifest.Pins) and never re-resolved.
"""

from __future__ import annotations

import re
import threading
from collections.abc import Iterator

from rasterio.warp import transform

from .fetch import get_json

STAC = "https://data.geo.admin.ch/api/stac/v1"
ASSETS = "https://data.geo.admin.ch"
ALTI = "ch.swisstopo.swissalti3d"
BOUNDARIES = "ch.swisstopo.swissboundaries3d"

# Test bbox (WGS84) that dev runs must stay inside, plus the LV95 test range the plan names
# (PLAN.md §5: E 2628–2672, N 1154–1181 km, whose southern rows fall just outside the WGS84 box).
DEV_BBOX_WGS84 = (7.80, 46.56, 8.39, 46.80)
DEV_RANGE_LV95 = (2628, 1154, 2672, 1181)  # inclusive km

_ALTI_ID = re.compile(r"^swissalti3d_(\d{4})_(\d{4})-(\d{4})$")
_BLOCK = 8  # km; one STAC query resolves an aligned 8×8 km block of ALTI tiles


def lv95_to_wgs84_bbox(e0: float, n0: float, e1: float, n1: float) -> tuple[float, float, float, float]:
    """Bounding box (lon0, lat0, lon1, lat1) of an LV95 rectangle, a superset of its exact WGS84 footprint."""
    lon, lat = transform("EPSG:2056", "EPSG:4326", [e0, e1, e0, e1], [n0, n0, n1, n1])
    return min(lon), min(lat), max(lon), max(lat)


def in_dev_area(e_km: int, n_km: int) -> bool:
    """True if the 1 km tile intersects the dev test bbox or lies in the plan's LV95 test range."""
    e0, n0, e1, n1 = DEV_RANGE_LV95
    if e0 <= e_km <= e1 and n0 <= n_km <= n1:
        return True
    lon0, lat0, lon1, lat1 = lv95_to_wgs84_bbox(e_km * 1000, n_km * 1000, e_km * 1000 + 1000, n_km * 1000 + 1000)
    b = DEV_BBOX_WGS84
    return lon1 > b[0] and lon0 < b[2] and lat1 > b[1] and lat0 < b[3]


def items(collection: str, bbox: tuple[float, float, float, float] | None = None) -> Iterator[dict]:
    """All items of a collection (optionally within a WGS84 bbox), following `next` links to the end."""
    url = f"{STAC}/collections/{collection}/items"
    params: dict | None = {"limit": 100}
    if bbox is not None:
        params["bbox"] = ",".join(f"{v:.6f}" for v in bbox)
    while url:
        page = get_json(url, params)
        yield from page.get("features", [])
        url = next((ln["href"] for ln in page.get("links", []) if ln.get("rel") == "next"), None)
        params = None  # the next link carries the query


def latest_item_id(collection: str) -> str:
    """Newest item of a whole-country collection; ids embed the release (e.g. swissboundaries3d_2026-01)."""
    return max(f["id"] for f in items(collection))


def boundaries_href(item_id: str) -> str:
    return f"{ASSETS}/{BOUNDARIES}/{item_id}/{item_id}_2056_5728.gdb.zip"


def alti_href(item_id: str) -> str:
    """The 2 m COG of a swissALTI3D item; derived from the id so pinned rebuilds need no STAC query."""
    return f"{ASSETS}/{ALTI}/{item_id}/{item_id}_2_2056_5728.tif"


class AltiIndex:
    """Newest swissALTI3D item per 1 km tile. Tiles are resolved in aligned 8×8 km blocks, one paginated
    bbox query each, so a bulk build costs ~1 query per 64 tiles. Thread-safe; results are cached."""

    def __init__(self):
        self._newest: dict[tuple[int, int], str | None] = {}
        self._lock = threading.Lock()

    def newest(self, e_km: int, n_km: int) -> str | None:
        """Item id of the newest year for tile (e, n), or None if swisstopo has no tile there."""
        with self._lock:
            if (e_km, n_km) not in self._newest:
                self._resolve_block(e_km // _BLOCK * _BLOCK, n_km // _BLOCK * _BLOCK)
            return self._newest[(e_km, n_km)]

    def _resolve_block(self, be: int, bn: int) -> None:
        found: dict[tuple[int, int], str] = {}
        bbox = lv95_to_wgs84_bbox(be * 1000, bn * 1000, (be + _BLOCK) * 1000, (bn + _BLOCK) * 1000)
        for f in items(ALTI, bbox):
            m = _ALTI_ID.match(f["id"])
            if not m:
                continue
            tile = (int(m.group(2)), int(m.group(3)))
            if any(k.endswith("_2_2056_5728.tif") for k in f.get("assets", {})):
                # ids differ only in the year, so the lexicographic max is the newest
                found[tile] = max(found.get(tile, ""), f["id"])
        for e in range(be, be + _BLOCK):
            for n in range(bn, bn + _BLOCK):
                self._newest[(e, n)] = found.get((e, n))
