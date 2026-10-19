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

- `$SWISSDATA_RAW/extracts/swisstlm3d_2026-02_tlm-extract.gpkg` (210 MB, built in ~25 s with `python -I -m swissdata.tlm`).
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
- **River levels** are computed network-wide in-process from all extract watercourses (~12.7k lines, ~1 s, cached):
  1. Lines are digitised downstream (verified: every sloped line runs downhill).
  2. Lines are topologically sorted at shared endpoints, so a confluence caps the downstream line; cycles are broken arbitrarily.
  3. Levels take a running minimum, then a floor to whole metres, which gives stepped levels.
  - Levels come only from TLM, so neighbouring cells agree at seams.
  - **For the whole country this must become a precomputed, tiled table.** Note for later.
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
  - Under a road bridge, the rail-centre bit breaks for the bridge's width.
- **Road classes and widths** are in the `ROADS` table in `overlay.py`. 4 m/3 m roads with a natural surface become tracks (5).
- **OELV** comes from the line Z at the nearest point for bridges, tunnels and roads/rail on dams. Dams use their crest (max Z).
- **Rail:**
  - Rack, funicular or out-of-service lines → class 9 with no rail bit (Giessbachbahn, Gelmerbahn, Rothorn, Brünig rack sections).
  - Everything else is class 8, with a 4-connected centre line carrying OFLG bit2.
- The **edge bit** marks road classes 1–5 next to a non-overlay column. It is computed on a grid padded by 1 column, so it agrees across seams.

### Terrain for tests and previews

- **sidecar-core's `AltiTerrain` is not on main yet**, so tests and previews use `tlm_preview.StandInTerrain`: the cell's own swissALTI3D 2 m tile, with no neighbour edges.
- `tlm_preview.terrain_builder()` switches to `swissdata.alti.AltiTerrain` automatically once it is importable.

## Files outside the owned list

- `sidecar/swissdata/build.py`: registration in `BUILDERS` only (allowed). **Merge note:** sidecar-core's branch changes the same line to `[AltiTerrain(), BorderMask(), DefaultSurface()]`. The combined list should be `[AltiTerrain(), WaterBuilder(), LandcoverBuilder(), OverlayBuilder(), BorderMask(), DefaultSurface()]`; check where BorderMask belongs relative to SURF/WKND.
- `sidecar/swissdata/tlm_preview.py`: new dev tool for the previews and stand-in terrain.
- `sidecar/pyproject.toml`, `uv.lock`: matplotlib in the dev group.

## Contract proposals

None needed for Milestone 1.

## Previews

`.data-cache/previews/<cell>_<LAYER>.png` (TERR, SURF, WKND, WLVL, WDEPTH, OCLS, OELV, OFLG) for:

| Cell | Place |
|---|---|
| 2646_1178 | Brienz |
| 2668_1157 | Grimsel south |
| 2668_1158 | Grimsel dams Spitallamm/Seeuferegg; the brief's 2668_1157 has no dam |
| 2644_1176 | Giessbach |
| 2660_1173 | Aareschlucht |

Regenerate with `uv run --group dev python -I -m swissdata.tlm_preview 2646 1178 2668 1157 …`.

## Left for later

- A tiled river-level table for areas beyond the dev bbox.
- Farmland.
- Rail continuity under road bridges.
- Lake-shore beaches are only the gravel fallback.
- Seam consistency across neighbouring cells is checked by design, not by a test with two real cells.
