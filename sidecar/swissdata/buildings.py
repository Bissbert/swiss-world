"""swissBUILDINGS3D 3.0 -> BTBL/BSPN layers.

Per-sheet items (`swissbuildings3d_3_0_<year>_<sheet>`) are found by STAC bbox search, newest version per sheet.
Country-wide items (`swissbuildings3d_3_0_<year>`, 14 GB) are never selected. Each sheet is parsed and voxelised
once into a cached .npz (scratch), so all cells on a sheet reuse it.

- Body spans (part 0) come from `Building_solid`.
- `Roof_solid` holds ~20 cm roof skins linked by GEBAEUDEEINHEIT.
  Where a skin lies outside every body column of its building unit it becomes an eave/overhang span (part 1).
  A skin resting on the body raises the body span to the roof surface; one clearly above it is its own span.
  Roof skins of a unit without any body (carports, shelters) form their own building row, all overhang spans.
"""

from __future__ import annotations

import os
import re
import struct
import sys
import threading
import time
import zlib
from dataclasses import dataclass
from pathlib import Path

import fiona
import httpx
import numpy as np

from . import gwr, swd, voxel
from .config import Config
from .fetch import USER_AGENT, fetch

COLLECTION = "ch.swisstopo.swissbuildings3d_3_0"
STAC_ITEMS = f"https://data.geo.admin.ch/api/stac/v1/collections/{COLLECTION}/items"
ASSET_URL = "https://data.geo.admin.ch/" + COLLECTION + "/{item}/{item}_2056_5728.gdb.zip"
# per-sheet items only; the anchored sheet suffix excludes the whole-country swissbuildings3d_3_0_<year> items
SHEET_ITEM = re.compile(r"^swissbuildings3d_3_0_(\d{4})_(\d{4}-\d{2})$")
# buildings are assigned to one sheet but may stick out of its nominal extent (seen: 43 m)
SEARCH_PAD_M = 150.0
# a roof skin whose underside is within this of the body top rests on the body (Roof_solid is ~0.2 m thick)
SKIN_GAP_M = 0.5

PART_BODY = 0
PART_OVERHANG = 1
ROOF_TOP = 1
ROOF_HALF = 2

# Stable u16 codes for the OBJEKTART text values (the FGDB stores text, not codes). 0 = unknown/other.
# Append only: codes are written into cells.
OBJEKTART = {
    "Gebaeude Einzelhaus": 1,
    "Hochhaus": 2,
    "Sakrales Gebaeude": 3,
    "Im Bau": 4,
    "Gebaeude unsichtbar": 5,
    "Unterirdisches Gebaeude": 6,
    "Lagertank": 7,
    "Treibhaus": 8,
    "Mauer gross": 9,
    "Bruecke gedeckt": 10,
    "Offenes Gebaeude": 11,
    "Flugdach": 12,
}  # values seen in the test-area sheets; new ones get the next free code

_CACHE_VERSION = 3


def sheet_of(item: str) -> str:
    m = SHEET_ITEM.match(item)
    if not m:
        raise ValueError(f"not a per-sheet swissBUILDINGS3D item: {item}")
    return m.group(2)


def newest_per_sheet(items: list[str]) -> list[str]:
    """Picks the newest version of every sheet, ignoring country-wide and unknown ids. Sorted by sheet."""
    best: dict[str, tuple[str, str]] = {}
    for item in items:
        m = SHEET_ITEM.match(item)
        if m and (m.group(2) not in best or m.group(1) > best[m.group(2)][0]):
            best[m.group(2)] = (m.group(1), item)
    return [best[s][1] for s in sorted(best)]


def cell_bbox_wgs84(e_km: int, n_km: int, pad_m: float = SEARCH_PAD_M) -> tuple[float, float, float, float]:
    from rasterio.warp import transform_bounds

    e, n = e_km * 1000, n_km * 1000
    return transform_bounds("EPSG:2056", "EPSG:4326", e - pad_m, n - pad_m, e + 1000 + pad_m, n + 1000 + pad_m)


def find_sheets(e_km: int, n_km: int) -> list[str]:
    """STAC bbox search -> newest per-sheet item ids touching the (padded) cell."""
    bbox = ",".join(f"{v:.7f}" for v in cell_bbox_wgs84(e_km, n_km))
    url, params, ids = STAC_ITEMS, {"bbox": bbox, "limit": 100}, []
    with httpx.Client(headers={"User-Agent": USER_AGENT}, timeout=60, follow_redirects=True) as client:
        while url:
            r = client.get(url, params=params)
            r.raise_for_status()
            page = r.json()
            ids += [f["id"] for f in page.get("features", [])]
            url = next((link["href"] for link in page.get("links", []) if link.get("rel") == "next"), None)
            params = None  # the next link carries the query
    return newest_per_sheet(ids)


# --- per-sheet parse + voxelise (cached) --------------------------------------------------------------------

@dataclass
class Sheet:
    item: str
    # one row per Building_solid
    uuid: np.ndarray  # str
    egid: np.ndarray  # i8
    objektart: np.ndarray  # u2
    base_m: np.ndarray  # f8, lowest point of the body
    top_m: np.ndarray  # f8, highest point of body and its roof skins
    volume: np.ndarray  # f8, volume of the body and its roof skins (m³)
    # one row per span, sorted by (building, e, n, bottom)
    building: np.ndarray  # i4
    e: np.ndarray  # i8 column Ei
    n: np.ndarray  # i8 column Ni
    bottom: np.ndarray  # u2 encoded
    top: np.ndarray  # u2 encoded
    part: np.ndarray  # u1
    roof: np.ndarray  # u1 roofFlags
    odd_columns: int = 0
    orphan_roofs: int = 0  # roof skins without a body, emitted as free-standing roof rows

    _ARRAYS = ("uuid", "egid", "objektart", "base_m", "top_m", "volume",
               "building", "e", "n", "bottom", "top", "part", "roof")


def gdb_path(zip_path: Path, item: str) -> str:
    return f"zip://{zip_path}!swissBUILDINGS3D_3-0_{sheet_of(item)}.gdb"


def _read_layer(path: str, layer: str):
    with fiona.open(path, layer=layer) as src:
        for f in src:
            yield f.properties, voxel.triangles(f.geometry.coordinates)


def roof_flags(building: np.ndarray, e: np.ndarray, n: np.ndarray, top: np.ndarray) -> np.ndarray:
    """bit0 on the topmost span of each (building, column); bit1 when the top ends near x.5 m.
    Expects spans sorted by (building, e, n, bottom)."""
    last = np.ones(len(building), dtype=bool)
    last[:-1] = (building[1:] != building[:-1]) | (e[1:] != e[:-1]) | (n[1:] != n[:-1])
    frac = top.astype(np.int64) % 10
    return (last * ROOF_TOP | ((frac >= 3) & (frac <= 7)) * ROOF_HALF).astype("u1")


def parse_sheet(path: str, item: str) -> Sheet:
    return sheet_from_solids(item, list(_read_layer(path, "Building_solid")), list(_read_layer(path, "Roof_solid")))


def sheet_from_solids(item: str, bodies: list[tuple[dict, np.ndarray]],
                      roofs: list[tuple[dict, np.ndarray]]) -> Sheet:
    """bodies/roofs: (properties, triangles) of Building_solid/Roof_solid features."""
    body_tris = [t for _, t in bodies]
    volume = np.array([voxel.solid_volume(t) for t in body_tris])

    # building unit (GEBAEUDEEINHEIT) -> bodies; each roof skin belongs to the unit's body nearest to it.
    # Sheets of the 2020 schema have no GEBAEUDEEINHEIT; there a roof skin carries its body's UUID.
    unit_ids: dict[str, int] = {}
    body_unit = [unit_ids.setdefault(p.get("GEBAEUDEEINHEIT", p["UUID"]) or p["UUID"], len(unit_ids))
                 for p, _ in bodies]
    centres = np.array([t.reshape(-1, 3)[:, :2].mean(axis=0) if len(t) else (np.nan, np.nan) for t in body_tris])
    roof_body = np.full(len(roofs), -1, dtype=np.int64)
    free: dict[str, list[int]] = {}  # unit -> roof skins of a unit without any body
    for r, (p, t) in enumerate(roofs):
        if len(t) == 0:
            continue
        unit_key = p.get("GEBAEUDEEINHEIT", p["UUID"]) or p["UUID"]
        unit = unit_ids.get(unit_key)
        if unit is None:
            free.setdefault(unit_key, []).append(r)
            continue
        cand = np.flatnonzero(np.asarray(body_unit) == unit)
        c = t.reshape(-1, 3)[:, :2].mean(axis=0)
        b = cand[np.argmin(((centres[cand] - c) ** 2).sum(axis=1))]
        roof_body[r] = b
        volume[b] += voxel.solid_volume(t)

    # free-standing roofs (carports, shelters; no Building_solid) become their own rows, one per unit; their
    # skin is emitted as overhang (roof without walls). Row id = smallest roof UUID of the unit; roofs without UUID
    # sort last (then by sheet order), and a unit whose roofs all lack one is identified by its unit key.
    props = [p for p, _ in bodies]
    tris = list(body_tris)
    for unit_key, rr in free.items():
        b = len(props)
        roof_body[rr] = b
        body_unit.append(unit_ids.setdefault(unit_key, len(unit_ids)))
        first = min(rr, key=lambda r: (roofs[r][0]["UUID"] is None, roofs[r][0]["UUID"] or "", r))
        props.append({**roofs[first][0], "UUID": roofs[first][0]["UUID"] or unit_key,
                      "EGID": max(roofs[r][0]["EGID"] or 0 for r in rr)})
        tris.append(np.concatenate([roofs[r][1] for r in rr]))
        volume = np.append(volume, sum(voxel.solid_volume(roofs[r][1]) for r in rr))
    body_unit = np.asarray(body_unit, dtype=np.int64)
    orphan_roofs = sum(len(rr) for rr in free.values())

    uuid = np.array([p["UUID"] for p in props], dtype=str)
    egid = np.array([p["EGID"] or 0 for p in props], dtype=np.int64)
    objektart = np.array([OBJEKTART.get(p["OBJEKTART"], 0) for p in props], dtype="u2")
    base_m = np.array([t[:, :, 2].min() if len(t) else np.nan for t in tris])
    top_m = np.array([t[:, :, 2].max() if len(t) else np.nan for t in tris])
    for r in np.flatnonzero(roof_body >= 0):
        top_m[roof_body[r]] = max(top_m[roof_body[r]], roofs[r][1][:, :, 2].max())

    bs = voxel.voxelise(body_tris)
    rs = voxel.voxelise([t for _, t in roofs])
    rb = roof_body[rs.solid]
    rs_keep = rb >= 0
    # overhang = roof skin columns where no body of the same unit has a span
    all_e, all_n = np.concatenate([bs.e, rs.e]), np.concatenate([bs.n, rs.n])
    e_min = int(all_e.min()) if len(all_e) else 0
    n_min = int(all_n.min()) if len(all_n) else 0

    def key(unit, e, n):
        return (unit << 40) | ((e - e_min) << 20) | (n - n_min)

    # topmost body span per (unit, column)
    body_key = key(body_unit[bs.solid], bs.e, bs.n)
    order = np.lexsort((bs.top, body_key))
    last = np.ones(len(order), dtype=bool)
    last[:-1] = body_key[order][1:] != body_key[order][:-1]
    top_span = order[last]
    top_keys = body_key[top_span]
    roof_keys = key(body_unit[np.maximum(rb, 0)], rs.e, rs.n)
    pos = np.clip(np.searchsorted(top_keys, roof_keys), 0, max(len(top_keys) - 1, 0))
    has_body = (top_keys[pos] == roof_keys) if len(top_keys) else np.zeros(len(rs), dtype=bool)
    over = rs_keep & ~has_body
    # a skin resting on the body (within SKIN_GAP_M of its top) raises that body span to the roof surface;
    # a skin clearly above it (e.g. a canopy over a lower annex) is its own body span; skins below are dropped
    span = top_span[pos] if len(top_keys) else np.zeros(len(rs), dtype=np.int64)  # sheet without bodies
    span_top = bs.top[span] if len(top_keys) else np.zeros(len(rs))
    on = rs_keep & has_body
    rests = on & (rs.bottom <= span_top + SKIN_GAP_M) & (rs.top > span_top)
    above = on & (rs.bottom > span_top + SKIN_GAP_M)
    body_top = bs.top.copy()
    np.maximum.at(body_top, span[rests], rs.top[rests])

    extra = over | above
    building = np.concatenate([bs.solid, rb[extra]]).astype(np.int32)
    e = np.concatenate([bs.e, rs.e[extra]])
    n = np.concatenate([bs.n, rs.n[extra]])
    bottom = swd.encode_height(np.concatenate([bs.bottom, rs.bottom[extra]]))
    top = swd.encode_height(np.concatenate([body_top, rs.top[extra]]))
    part = np.concatenate([np.full(len(bs), PART_BODY), np.where(over[extra], PART_OVERHANG, PART_BODY)]).astype("u1")
    ok = top > bottom  # thinner than 1 dm after encoding
    building, e, n, bottom, top, part = building[ok], e[ok], n[ok], bottom[ok], top[ok], part[ok]
    order = np.lexsort((part, bottom, n, e, building))
    building, e, n, bottom, top, part = (a[order] for a in (building, e, n, bottom, top, part))
    return Sheet(item, uuid, egid, objektart, base_m, top_m, volume, building, e, n, bottom, top, part,
                 roof_flags(building, e, n, top), bs.odd_columns + rs.odd_columns, orphan_roofs)


def _cache_path(cfg: Config, item: str) -> Path:
    return cfg.scratch / "buildings" / f"{item}.v{_CACHE_VERSION}.npz"


def load_sheet(cfg: Config, item: str) -> Sheet:
    """Parsed + voxelised sheet, from the scratch cache or (once) from the downloaded gdb.zip."""
    cache = _cache_path(cfg, item)
    if cache.exists():
        z = np.load(cache)
        return Sheet(item, *(z[a] for a in Sheet._ARRAYS), int(z["odd_columns"]), int(z["orphan_roofs"]))
    zip_path = fetch(cfg, ASSET_URL.format(item=item))
    sheet = parse_sheet(gdb_path(zip_path, item), item)
    cache.parent.mkdir(parents=True, exist_ok=True)
    tmp = cache.with_name(f"{cache.name}.{os.getpid()}-{threading.get_ident()}.tmp.npz")  # workers may race
    np.savez(tmp, **{a: getattr(sheet, a) for a in Sheet._ARRAYS},
             odd_columns=sheet.odd_columns, orphan_roofs=sheet.orphan_roofs)
    os.replace(tmp, cache)
    return sheet


# --- cell assembly ------------------------------------------------------------------------------------------

def cell_buildings(e_km: int, n_km: int, sheets: list[Sheet], table: gwr.GwrTable | None
                   ) -> tuple[list[swd.Building], dict[tuple[int, int], list[swd.Span]]]:
    """BTBL + BSPN for one cell. A building is in the cell when at least one of its column centres is."""
    e0, n0 = e_km * swd.CELL, n_km * swd.CELL
    seen: set[str] = set()
    picked = []  # (gid, sheet, building index, span mask over that building)
    for sh in sheets:
        in_cell = (sh.e >= e0) & (sh.e < e0 + swd.CELL) & (sh.n >= n0) & (sh.n < n0 + swd.CELL)
        for b in np.unique(sh.building[in_cell]):
            uuid = str(sh.uuid[b])
            if uuid in seen:  # same solid in two sheets: keep the first
                continue
            seen.add(uuid)
            picked.append((swd.gid_from_uuid(uuid), sh, int(b)))
    picked.sort(key=lambda p: p[0])

    egids = np.array([sh.egid[b] for _, sh, b in picked], dtype=np.int64)
    if table is not None:
        _, gkat, gklas, gbaup, gastw = table.lookup(egids)
    else:
        gkat = gklas = gbaup = gastw = np.zeros(len(picked), dtype=np.int64)

    buildings: list[swd.Building] = []
    spans: dict[tuple[int, int], list[swd.Span]] = {}
    for idx, (gid, sh, b) in enumerate(picked):
        lo, hi = np.searchsorted(sh.building, [b, b + 1])
        e, n = sh.e[lo:hi], sh.n[lo:hi]
        buildings.append(swd.Building(
            gid=gid, egid=int(egids[idx]), objektart=int(sh.objektart[b]),
            gwr_category=int(gkat[idx]), gwr_class=int(gklas[idx]), gwr_period=int(gbaup[idx]),
            floors=int(gastw[idx]), entrance_side=0,
            base_h=int(swd.encode_height(sh.base_m[b])), top_h=int(swd.encode_height(sh.top_m[b])),
            min_x=int(e.min() - e0), min_z=int(n0 + swd.CELL - 1 - n.max()),
            max_x=int(e.max() - e0), max_z=int(n0 + swd.CELL - 1 - n.min()),
        ))
        inside = (e >= e0) & (e < e0 + swd.CELL) & (n >= n0) & (n < n0 + swd.CELL)
        for k in np.flatnonzero(inside) + lo:
            dx, dz = int(sh.e[k] - e0), int(n0 + swd.CELL - 1 - sh.n[k])
            spans.setdefault((dx, dz), []).append(
                swd.Span(int(sh.bottom[k]), int(sh.top[k]), idx, int(sh.part[k]), int(sh.roof[k])))
    for key, col in spans.items():
        col.sort(key=lambda s: (s.bottom_h, s.building_idx, s.part))
        spans[key] = disjoint(col)
    return buildings, spans


def disjoint(col: list[swd.Span]) -> list[swd.Span]:
    """Spans sorted by bottom -> non-overlapping spans. Overlapping solids (touching or nested buildings, eaves over
    a neighbour) are resolved bottom-up: a span starting inside the one below starts at its top instead, and is
    dropped when nothing of it is left."""
    out: list[swd.Span] = []
    for s in col:
        if out and s.bottom_h < out[-1].top_h:
            if s.top_h <= out[-1].top_h:
                continue
            s = swd.Span(out[-1].top_h, s.top_h, s.building_idx, s.part, s.roof_flags)
        out.append(s)
    return out


class BuildingsBuilder:
    """Registered in build.BUILDERS. Emits BTBL/BSPN via LayerResult.buildings/spans."""

    name = "buildings"
    tags = ("BTBL", "BSPN")

    def build(self, e_km, n_km, cfg, partial, pinned):
        from .build import LayerResult  # build.py imports this module to register the builder

        if pinned and "buildings" in pinned:
            items = list(pinned["buildings"])
        else:
            items = find_sheets(e_km, n_km)
        sheets = [load_sheet(cfg, item) for item in items]
        table, gwr_id = gwr.load_pinned(cfg, (pinned or {}).get("gwr"))
        blds, spans = cell_buildings(e_km, n_km, sheets, table)
        # sources are recorded even for a cell without buildings, so a rebuild never picks newer sheets
        return LayerResult(buildings=blds, spans=spans, sources={"buildings": items, "gwr": gwr_id})


# --- preview + CLI ------------------------------------------------------------------------------------------

def _png(path: Path, rgb: np.ndarray) -> None:
    h, w, _ = rgb.shape
    raw = b"".join(b"\x00" + rgb[y].tobytes() for y in range(h))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def preview(spans: dict[tuple[int, int], list[swd.Span]], path: Path) -> None:
    """Top-down PNG: brightness = highest span top; overhang-only columns tinted orange."""
    top = np.full((swd.CELL, swd.CELL), np.nan)
    over = np.zeros((swd.CELL, swd.CELL), dtype=bool)
    for (dx, dz), col in spans.items():
        top[dz, dx] = swd.decode_height(max(s.top_h for s in col))
        over[dz, dx] = all(s.part == PART_OVERHANG for s in col)
    rgb = np.zeros((swd.CELL, swd.CELL, 3), dtype=np.uint8)
    if np.isfinite(top).any():
        lo, hi = np.nanmin(top), np.nanmax(top)
        g = np.where(np.isfinite(top), 60 + 195 * (top - lo) / max(hi - lo, 1e-6), 0).astype(np.uint8)
        rgb[...] = g[..., None]
        rgb[over] = np.stack([g[over], (g[over] * 0.6).astype(np.uint8), np.zeros(over.sum(), np.uint8)], -1)
    _png(path, rgb)


def main(argv: list[str]) -> None:
    e_km, n_km = int(argv[0]), int(argv[1])
    cfg = Config.from_env()
    t0 = time.perf_counter()
    items = find_sheets(e_km, n_km) if len(argv) < 3 else argv[2].split(",")
    t1 = time.perf_counter()
    sheets = []
    for item in items:
        ts = time.perf_counter()
        sh = load_sheet(cfg, item)
        sheets.append(sh)
        print(f"{item}: {len(sh.uuid)} buildings, {len(sh.e)} spans, odd columns {sh.odd_columns}, "
              f"orphan roofs {sh.orphan_roofs}, {time.perf_counter() - ts:.1f} s")
    t2 = time.perf_counter()
    blds, spans = cell_buildings(e_km, n_km, sheets, gwr.load_pinned(cfg)[0])
    t3 = time.perf_counter()
    out = cfg.raw.parent / "previews" / f"buildings_{e_km}_{n_km}.png"
    preview(spans, out)
    print(f"cell {e_km}_{n_km}: {len(blds)} buildings, {len(spans)} columns, "
          f"{sum(len(c) for c in spans.values())} spans; stac {t1 - t0:.1f} s, sheets {t2 - t1:.1f} s, "
          f"cell {t3 - t2:.1f} s; preview {out}")


if __name__ == "__main__":
    main(sys.argv[1:])
