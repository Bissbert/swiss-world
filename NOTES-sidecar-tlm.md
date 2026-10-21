# NOTES: sidecar-tlm

## swissTLM3D asset (recorded before download)

- STAC collection `ch.swisstopo.swisstlm3d`, newest item **`swisstlm3d_2026-02`** (datetime 2026-02-24).
- Assets (HTTP HEAD, 2026-10-19):
  - `swisstlm3d_2026-02_2056_5728.gdb.zip`: **2,882,823,552 B (2.88 GB)**, last-modified 2026-08-20. **Chosen** (smallest, fiona reads FGDB via `zip://`).
  - `.gpkg.zip`: 4,795,840,706 B; `.shp.zip`: 3,591,345,815 B; `.xtf.zip` (INTERLIS, not readable by fiona).
- Below the 6 GB stop limit, so downloading once via `fetch()`.

## Downloads (all via `fetch()`, into `$SWISSDATA_RAW`)

| Item | Size | Why |
|---|---|---|
| `swisstlm3d_2026-02` gdb.zip | 2.88 GB | once, allowed by the brief |
| `swissbathy3d_brienzersee` esriasciigrid.zip | 129 MB | 56 tiles |
| `swissbathy3d_thunersee` | 163 MB | inside the bbox |
| `swissbathy3d_lungernsee` | 6.9 MB | inside the bbox |
| swissALTI3D 2 m tiles for the preview cells | a few MB each | stand-in terrain, see below |

Total is about 3.2 GB, over the ~2 GB guideline only because of the TLM file the brief allows. Sarnersee bathymetry exists but lies outside the bbox, so it was not fetched.

## Extract

- `$SWISSDATA_RAW/extracts/swisstlm3d_2026-02_tlm-extract-v3.gpkg`: **the whole country** (6.3 GB, built in 406 s, peak RSS 263 MB, with `python -I -m swissdata.tlm`). v1/v2 held only the test box `tlm.LEGACY_BBOX` (2626–2674 × 1152–1183 km, 210 MB), so store cells outside it had no roads, water or land cover. See "National extract (v3)" below.
- It holds 14 layers with only the attributes used.
- Process-safe: flock, then `.part.gpkg`, then `os.replace`.
- **Select, don't clip:** features that intersect the bbox are copied whole. Z stays exact, lines stay continuous (needed for river levels across the bbox), and lake polygons keep their full shoreline for distance-to-shore.
- Codes come from the FGDB's own GDB_Items domains (TLM 2026). They are quoted in comments next to each mapping.

## Decisions

### Data gaps in TLM

- **No urban or farmland class in TLM_BODENBEDECKUNG**, which covers only rock/forest/water/glacier/wetland.
  - Urban green (11) comes from settlement-name areas (SIEDLUNGSNAME polygons) and parks/cemeteries/allotments.
  - Urban sealed (10) comes from building footprints (+1.5 m, not underground) and parking/traffic areas.
  - Farmland (16) is never emitted. There is no source in TLM; swissTLMRegio or land-use statistics could fill it later.
- **Forest type is not in TLM:** below 900 m broadleaf, 900–1,400 m mixed, above 1,400 m conifer.
- Loose rock below 1,000 m becomes gravel (15), higher up scree.
- Open land above 1,800 m becomes alpine pasture (17).

### Water and lake beds

- **Lake levels** are the median Z of the TLM lake polygon, **rounded to whole metres**: Brienzersee 563.74 → 564, Thunersee 557.67 → 558, Grimsel 1908, Räterichsboden 1762. This matches the brief's numbers and whole-block water surfaces.
- **Lake kinds:** reservoir if a Staumauer/Staudamm lies within 30 m (Wehr excluded, otherwise Brienzersee would count), pond if under 0.5 ha, otherwise lake.
- **River levels** are computed network-wide from all extract watercourses (now a side file, see "National extract (v3)"):
  1. Lines are digitised downstream (verified: every sloped line runs downhill).
  2. Lines are topologically sorted at shared endpoints, so a confluence caps the downstream line; cycles are broken arbitrarily.
  3. Levels take a running minimum, then a floor to whole metres, which gives stepped levels.
  - Levels come only from TLM, so neighbouring cells agree at seams.
- **Footprints:**
  - Wide rivers use the BODENBEDECKUNG "Fliessgewaesser" polygons (WKND 2).
  - Other lines are buffered to 2 m (named) or ~1.4 m (unnamed; 0.71 m radius keeps them 8-connected) as WKND 3.
  - Waterfalls are VERLAUF=Wasserfall parts, plus EINZELOBJEKT Wasserfall points within 4 m (Giessbach, Handegg, Rychenbach all present).
- **Carving:** TERR nodes under rivers are lowered to level − 2 m and under streams to level − 1 m (+1.5 m margin), so water is visible and gorges stay as narrow as the TLM polygon (Aareschlucht).
  - Any water column whose bilinear terrain is still ≥ its level is dropped. These are mostly lake-shore columns; landcover turns them into gravel shore.
- **Bathymetry runs inside the water builder**, which knows the lake polygons and levels, not as its own builder. Steps:
  1. swissBATHY3D 1 m tiles (cell plus neighbours) are averaged onto the nodes.
  2. `TERR = min(TERR, bed)` where bed < level.
  3. Lake nodes without bathymetry get synthetic depth `level − min(1.5 + 0.12·d, 60)`, where d is the distance to shore.
- Water mutates `partial.layers["TERR"]` in place; TERR is not declared as a tag, since that belongs to core. If TERR is absent, carving and bathymetry are skipped.

### Sources and builder order

- **Sources:**
  - Every builder returns `{"tlm": "swisstlm3d_2026-02"}`.
  - Water also returns `"bathy": [items used]`.
  - A rebuild with a pinned `bathy` list reuses it.
  - A pinned `tlm` other than the extracted release raises `ValueError`; there is only one extract.
- **Builder order:** `[WaterBuilder, LandcoverBuilder, OverlayBuilder, DefaultSurface]`. Landcover reads WKND, so SURF and WKND agree.
  - The builders import `LayerResult` lazily, the same pattern as sidecar-core's `alti.py`, because build.py imports them.

### Overlay

- **Burn order** (later wins): tunnels < areas < roads (minor → major) < rail < dams < bridges.
  - Under a surface road, a tunnel is hidden.
  - Under a road bridge, the column keeps the road deck and also gets the rail-centre bit (contract proposal 1).
  - Buffers use round caps; flat caps left notches at the joints between TLM segments (10 rail-centre gaps in Interlaken).
- **Road classes and widths** are in the `ROADS` table in `overlay.py`. 4 m/3 m roads with a natural surface become tracks (5).
- **OELV** comes from the line Z at the nearest point for bridges, tunnels and roads/rail on dams. Dams use their crest (max Z).
- **Rail:**
  - Rack, funicular or out-of-service lines → class 9 with no rail bit (Giessbachbahn, Gelmerbahn, Rothorn, Brünig rack sections).
  - Everything else is class 8, with a 4-connected centre line carrying OFLG bit2.
- The **edge bit** marks road classes 1–5 next to a non-overlay column. It is computed on a grid padded by 1 column, so it agrees across seams.

### Terrain for tests and previews

- **sidecar-core's `AltiTerrain` is not on main yet**, so tests and previews use `tlm_preview.StandInTerrain`: the cell's own swissALTI3D 2 m tile, with no neighbour edges.
- `tlm_preview.terrain_builder()` switches to `swissdata.alti.AltiTerrain` automatically once it is importable. `builders()` adds a terrain builder only if `BUILDERS` has none.
- The acceptance tests and the preview CLI use a **private temporary store** (shared raw cache), so a post-merge `AltiTerrain` never pins into the shared manifest.
- The acceptance tests need `SWISSDATA_RAW` exported as in docs/dev.md; without it they skip.

## Files outside the owned list

- `sidecar/swissdata/build.py`: registration in `BUILDERS` only (allowed). **Merge note:** sidecar-core's branch changes the same line to `[AltiTerrain(), BorderMask(), DefaultSurface()]`. The combined list should be `[AltiTerrain(), WaterBuilder(), LandcoverBuilder(), OverlayBuilder(), BorderMask(), DefaultSurface()]`; check where BorderMask belongs relative to SURF/WKND.
- `sidecar/swissdata/tlm_preview.py`: new dev tool for the previews and stand-in terrain.
- `sidecar/pyproject.toml`, `uv.lock`: matplotlib in the dev group.

## Contract proposals

### 1. Rail-centre bit under road bridges (needed for the rail fix; not applied to docs/swd-format.md)

The sidecar now emits it. It is backward compatible: today's mod ignores bit2 on columns that are not class 8.

Proposed replacement for the sentence under §5:

> Rail blocks are placed where `OFLG.bit2` is set. On a class-8 column the rail sits on the overlay surface (`OELV`, or the terrain when `OELV` is `0xFFFF`). On a column with `bit0` (bridge) whose `OCLS` is a road class (1–7), the road deck is at `OELV` and the rail lies on the **terrain** under it, on a rail bed. The mod derives the rail shape from the neighbouring rail-centre columns.

- **Why not a separate rail layer:** one bit plus a rule covers every real case in the bbox (13 road-bridge/rail crossings).
  - Under the deck the ground is otherwise plain terrain, and the deck is 6–8 m above it.
  - A new layer would cost a contract bump in SwdReader/SwdCell for no extra information.
- **Not covered:** a rail tunnel under a surface road. The road wins OCLS, and bit1 on that column would describe the road. Inside the bbox the rail is simply absent for those few columns. That would need either a rule "bit1+bit2 on a road column = rail in tunnel at the TLM tunnel floor", which has no height channel, or a separate layer. I'm leaving it until a real case matters.

## Previews

`.data-cache/previews/<cell>_<LAYER>.png` (TERR, SURF, WKND, WLVL, WDEPTH, OCLS, OELV, OFLG) for:

| Cell | Place |
|---|---|
| 2646_1178 | Brienz |
| 2668_1157 | Grimsel south |
| 2668_1158 | Grimsel dams Spitallamm/Seeuferegg; the brief's 2668_1157 has no dam |
| 2644_1176 | Giessbach |
| 2660_1173 | Aareschlucht |
| 2667_1160 | Räterichsboden dam, crest 1770.3 |

Regenerate with `uv run --group dev python -I -m swissdata.tlm_preview 2646 1178 2668 1157 …`.

## Left for later

- A tiled river-level table for areas beyond the dev bbox.
- Farmland.
- Lake-shore beaches are only the gravel fallback.
- Seam consistency across neighbouring cells is checked by design, not by a test with two real cells.

## National extract (v3)

- `EXTRACT_VERSION = 3` copies every feature of the 14 layers: CH and FL (the FGDB includes Liechtenstein; Vaduz 2757–2760 × 1221–1224 has 1,003 roads, 2,858 footprints). Counts: 848k land cover, 2.1M roads, 671k watercourses, 4.1M building footprints. The GeoPackage R-tree is kept.
- **No whole-extract reads per process any more.** `water.read_all`/`overlay._trunk_wkb` loaded every line of the extract; nationally that is ~50x more. Both now come from side files next to the extract (`<extract stem>.rivers-v1.npz`, `.trunk-v1.npy`), built once under flock with atomic rename (`tlm.ensure_side`), then read per cell:
  - rivers: inflow cap per watercourse part (key fid·65536+part), 5.6 MB; the per-cell network reads lines from the R-tree (`tlm.read_envelope`, envelope test as the old STRtree) and applies `levels(z, cap)`.
  - trunk: blake2b-128 digests of the 2D WKB of every trunk part, 61 KB.
- **Legacy rule (byte-identical inside the old box):** the features v2 held (`items(bbox=LEGACY_BBOX)` of v3, verified identical to v2 in content and fid order for all 14 layers) are propagated alone first and keep those river caps / trunk parts; the rest of the country is propagated with them held fixed. So a line flowing into the old area from outside does not lower its levels there.
- **`tlm.read` returns fid (source) order.** OGR's spatial filter yields R-tree traversal order, which changes with everything else in the extract; landcover burns overlapping polygons last-wins, so SURF of 2646_1178 and 2660_1173 differed between v2 and v3. With the sort v2 and v3 give identical TLM layers for interior cells (tests/test_national.py pins five). Stored cells built before keep their R-tree order SURF until rebuilt.
- **`python -m swissdata.rebuild --only-missing-tlm`** rebuilds the cells to which v3 gives features v2 lacked (`tlm.missing_in_legacy`: R-tree envelope test of each layer around the cell, at the builders' read reach, 250 m, dams 3 km, against the legacy fids). Run with `SWISSDATA_DEV_BBOX=0`, otherwise the dev guard drops the cells outside the dev area.
- **Numbers (2026-10-21, 10 cores):**
  - Side files: rivers 28 s (346,932 parts, 10,997 legacy), trunk 1 s (3,786 parts), 315 MB RSS, once per extract. 29 legacy parts have a national inflow that would lower their cap without the legacy rule; none of their end levels changes.
  - Full pipeline (alti … overlay), first cell 2646_1178: 12.1 s → 7.9 s, peak RSS 863 → 677 MB (v2 code built the whole-extract network in each process: 1.2 s, +210 MB at v2 size).
  - Private store clone: `--only-missing-tlm` selects 121 of 1,353 cells (55 with the dev guard on), rebuilt in 608 s with 2 workers. In game, 2685_1158 has andesite (minor road) blocks at 4 of 4 probed road columns and 0 of 8 off-road probes.
  - Order fix: rebuilding the 1,287 in-box cells now gives 638 a different SURF (median 5,230 px) and 14 different OELV; stored cells stay as they are until rebuilt (`--bbox`/`--all`), which is optional.
- **Still test-box only:** GWR is `gwr.CANTONS = ("be",)` (buildings outside BE have no GWR attributes), bathymetry is `bathy.ITEMS` (3 lakes; others get the synthetic bed), and the dev guard (`stac.DEV_RANGES`) still refuses alti outside the dev area unless `SWISSDATA_DEV_BBOX=0`.

