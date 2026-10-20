# NOTES: mod-texture

Ground zones, ground texture and zone-driven snow. Package `ch.bissbert.swissworld.gen.ground`.

## Pipeline

Terrain → Water → Overlay → Building → Furnish → Loot → **GroundTexture** → Vegetation → **Snow**.

- `ZoneMap` (no Minecraft classes) classifies every column of a chunk. It reads the read-only interface `Ground`.
  Today `CellGround` implements `Ground` over the cells (`ColumnGrid.Cells`).
  The `ZoneMap` is memoised on the `GenContext` (`GenContext.memo`), so the biome resolver, `GroundTextureStage`
  and `SnowStage` share one computation per chunk.
- Determinism: a column's values depend only on cell data within `ZoneMap.REACH` = 8 columns, plus TPI rings of 24
  columns sampled on a 4-column world lattice. Everything is keyed by world position, so neighbouring chunks agree
  (`ZoneMapTest.chunkBordersAgree`).
- Per column, `ZoneMap` exposes: the packed id, metres, slope (the zones use the slope over 8 m), northness, hollow,
  downhill direction, snow depth in eighths, water distance and kind, path distance, rail distance, `natural`,
  `cliffAbove`.
- `GroundTextureStage` only re-textures *natural* columns: no OCLS/OFLG overlay, no building, no water, and a top block
  that is TerrainStage ground or earthwork soil (grass, dirt, coarse dirt, gravel, stone, mud, podzol, sand).
  It never touches decks, bridges, tunnels, buildings or water. The 3 blocks under the top follow it
  (`GroundTexture.under`). Falling blocks are only placed on solid support.
- `SnowStage` runs last. It puts the snow and builds the glacier body.
- VegetationStage edits (minimal): trees/plants only on `#minecraft:dirt` ground, podzol only replaces grass, and its
  glacier code is removed (SnowStage owns SURF 6).

## Packed id (16 bit)

`region(3) << 13 | belt(3) << 10 | zone(6) << 4 | lith(3) << 1 | spare(1)`, see `ZoneId`.
Enum ordinals are the ids. They are append-only.

- **Region** (`Region`): 0 unknown, 1 Jura, 2 Mittelland, 3 Alpennordflanke, 4 westliche Zentralalpen,
  5 östliche Zentralalpen, 6 Alpensüdflanke. Fallback `Region.of(E, N)` uses a few straight lines.
- **Belt** (`Belt.of`): the bounds hang off the regional treeline T, and a south slope lifts every bound by up to 100 m.
  - colline < T − 1000
  - montane < T − 450
  - subalpine < T
  - alpine < snow line
  - nival above
- **Lithology** (`Lith`): 0 unknown, 1 limestone/dolomite, 2 crystalline, 3 schist/flysch, 4 molasse, 5 quaternary.
  These codes are the same as sidecar-zones `LITH_*`. Fallback `Lith.guess`:
  - the Aar/Gotthard massif band (axis N = 1150 + (E − 2630)·0.45 km, ±11 km, E 2618–2715, h > 1,200 m) is crystalline;
  - Valais and Grisons above 1,500 m are crystalline, the south flank above 600 m too;
  - the Jura is limestone and the Mittelland molasse;
  - anything still unknown takes its lithology from the cliff zone.

## Climate lines (`ClimateLines`, smooth in E/N, no steps at region borders)

- Snow line, in metres: `2700 + 300·s((1,195,000 − N)/80,000) + 80·s((E − 2,700,000)/100,000) − 60·s((N − 1,200,000)/60,000)`.
  - Säntis ~2,650 m, Interlaken ~2,780 m, Oberaar ~2,850 m, Zermatt ~3,000 m.
- Treeline, in metres: `1800 + 450·s((1,180,000 − N)/50,000) + 60·s((E − 2,740,000)/60,000)`.
  - About 1,800 m on the north flank and 2,300 m in Valais/Engadin.

## Zone rules (`GroundZones.zone`, first match)

| Condition | Zone |
|---|---|
| no cell | NONE |
| water source on the column | WATER |
| SURF 6, slope > 28° | CREVASSED |
| SURF 6, snow < 4/8 and ≤ 4 columns from open ground | DEBRIS_GLACIER |
| SURF 6 otherwise | GLACIER |
| building or SURF 10 | URBAN_SEALED |
| SURF 4: slope < 25° under a cliff | SCREE_CONE |
| SURF 4: slope < 18° | KARST (limestone), else FOREFIELD (near a glacier or within 500 m below the snow line), else BOULDER_FIELD |
| SURF 4 otherwise | cliff by lithology: LIMESTONE_CLIFF / CRYSTALLINE_CLIFF / SCHIST_SLOPE (schist, molasse); unknown lithology by region |
| SURF 5 near a glacier | MORAINE (TPI > 2 m) / FOREFIELD |
| SURF 5, slope ≥ 20° | DEBRIS_CHANNEL (hollow, < 35°, not nival) / SCREE_CONE |
| SURF 5 otherwise | MORAINE / FOREFIELD near the snow line, else BOULDER_FIELD |
| SURF 7/8/15 | ALPINE_LAKE_SHORE (lake-type water, ≥ 1,500 m) / RIVERBED_GRAVEL |
| SURF 9 | RAISED_BOG (> 900 m, flat, away from water) / WET_MEADOW |
| SURF 11 / 12 / 13 / 16 | GARDEN / ORCHARD / VINEYARD / ARABLE |
| slope > 50° (> 42° alpine/nival) | cliff by lithology |
| SURF 1/2/3/14, alpine belt or SURF 14 subalpine | KRUMMHOLZ |
| forest ≤ 4 columns from river/stream, < 1,300 m, < 15° | RIPARIAN |
| forest, canopy < 2 m | AVALANCHE_TRACK (> 30°, not colline) / CLEARING |
| forest ≤ 2 columns from open ground, or SURF 14 | FOREST_EDGE |
| colline forest | CHESTNUT (south flank); DRY_PINE (conifer, south slope > 20°); SPRUCE (conifer); BEECH (SURF 1); MIXED_BROADLEAF |
| montane forest | DRY_PINE (inner Alps, south slope > 20°); BEECH / CHESTNUT (south flank < 1,000 m); SPRUCE (inner Alps); FIR_SPRUCE |
| subalpine forest | LARCH_PINE (inner Alps or within 200 m of the treeline) / SPRUCE |
| open ≤ 2 columns from lake/reservoir/pond | ALPINE_LAKE_SHORE (≥ 1,500 m) / REED_SHORE (< 8°) |
| open ≤ 6 columns from a river, < 5°, < 1,000 m | FLOODPLAIN |
| open under a cliff, 22–40°, not colline | SCREE_CONE |
| open alpine/nival | ALPINE_TURF (> 30°, > treeline + 450 m, or ridge TPI > 4 m) / ALPINE_PASTURE |
| open subalpine/montane > 32° near forest | AVALANCHE_TRACK |
| SURF 17 | ALPINE_PASTURE |
| open south slope > 22° (not subalpine) | DRY_GRASSLAND |
| open otherwise | PASTURE (subalpine or ≥ 8°) / HAY_MEADOW |
| afterwards: snow ≥ 12/8 (not rock) | FIRN |
| afterwards: 1–7/8 snow in a hollow below the snow line | SNOW_PATCH |

Not derived yet: INDUSTRIAL (rail yards: they come from overlays, which are never re-textured) and SPORTS
(OCLS 13 is a PATH claim). Both have palettes, ready for HAB/siteplan data.

## Palettes (`GroundTexture`, top block, weights in %; R = the lithology's rock palette)

Blocks are chosen with `Noise.patch`, CDF-equalised value noise keyed by E/N. It makes patches of a few metres, never
a per-block dither. Neighbouring list entries are neighbouring noise values, so materials grade into each other.

| Zone | Top |
|---|---|
| HAY_MEADOW | grass 97, coarse dirt 3 |
| PASTURE | grass 90, coarse dirt 7, gravel 3 |
| ALPINE_PASTURE | grass 78, coarse dirt 8, gravel 6, R 8 |
| ALPINE_TURF | moss 8, grass 44, coarse dirt 8, gravel 16, R 24 |
| DRY_GRASSLAND | grass 72, coarse dirt 18, R 10 |
| WET_MEADOW | grass 66, moss 14, mud 20 |
| RAISED_BOG | grass 22, moss 48, mud 30 |
| FLOODPLAIN | grass 72, mud 8, coarse dirt 10, gravel 10 |
| ARABLE | E–W rows: 2 coarse dirt, 1 grass |
| ORCHARD | grass 94, coarse dirt 6 |
| VINEYARD | grass 45, coarse dirt 55 |
| GARDEN | grass, thin dirt-path lines (noise contour) |
| SPORTS | grass |
| INDUSTRIAL | coarse dirt 25, gravel 55, andesite 20 |
| BEECH | grass 50, rooted dirt 20, coarse dirt 15, moss 15 |
| MIXED_BROADLEAF | grass 50, rooted dirt 15, podzol 15, moss 10, coarse dirt 10 |
| SPRUCE | grass 20, moss 20, podzol 45, coarse dirt 15 |
| FIR_SPRUCE | grass 25, moss 25, podzol 40, coarse dirt 10 |
| LARCH_PINE | grass 40, moss 15, podzol 25, coarse dirt 10, R 10 |
| DRY_PINE | grass 35, podzol 15, coarse dirt 35, R 15 |
| RIPARIAN | grass 40, rooted dirt 10, moss 20, mud 20, clay 10 |
| CHESTNUT | grass 50, rooted dirt 25, coarse dirt 25 |
| KRUMMHOLZ | grass 40, moss 20, podzol 20, R 20 |
| FOREST_EDGE | grass 80, rooted dirt 10, coarse dirt 10 |
| CLEARING | grass 70, rooted dirt 10, coarse dirt 20 |
| AVALANCHE_TRACK | grass 55, coarse dirt 15, gravel 15, R 15 |
| LIMESTONE / CRYSTALLINE_CLIFF | R 100 |
| SCHIST_SLOPE | R 85, gravel 15 |
| KARST | grass 12, R 88 |
| BOULDER_FIELD | moss 5, gravel 20, cobblestone 15, R 60 |
| SCREE_CONE | gravel 60, cobblestone 15, R 25 |
| MORAINE | grass 10, coarse dirt 25, gravel 45, R 20 |
| FOREFIELD | coarse dirt 15, gravel 50, cobblestone 10, R 25 |
| DEBRIS_CHANNEL | coarse dirt 30, gravel 50, R 20 |
| RIVERBED_GRAVEL | sand 10, gravel 75, R 15 |
| REED_SHORE | grass 40, mud 40, clay 20 |
| ALPINE_LAKE_SHORE | grass 20, gravel 50, R 30 |
| SNOW_PATCH (ground under the snow) | as ALPINE_PASTURE |
| FIRN (ground under the snow) | gravel 40, R 60 |
| URBAN_SEALED, WATER, glacier zones | not re-textured (SnowStage builds the glacier body) |

**Lithology rock palettes (R):**

| Lithology | Blocks |
|---|---|
| unknown | stone 50, andesite 25, cobblestone 13, tuff 12 |
| limestone | calcite 40, diorite 30, smooth stone 15, stone 15 |
| crystalline | stone 45, andesite 30, granite 15, cobblestone 10 |
| schist/flysch | tuff 40, cobbled deepslate 25, deepslate 15, stone 20 |
| molasse | sandstone 20, granite 30, stone 25, cobblestone 25 |
| quaternary | gravel 50, coarse dirt 30, cobblestone 20 |

**Local modifiers on soft (open/forest) zones**, checked before the zone palette:

- Rail ballast spill:
  - 1 column from rail/rack bed: 60 % of columns;
  - 2 columns: 25 %;
  - blocks gravel 65, andesite 15, coarse dirt 20.
- Path fringe:
  - 1 column from a track or footpath: 45 %;
  - 2 columns: 15 %;
  - blocks dirt path 35, coarse dirt 65.
- Shore fringe:
  - 1 column from water: 70 %;
  - 2 columns: 35 %;
  - lake/reservoir/pond below 1,500 m: sand 35, gravel 40, clay 10, mud 15;
  - lake/reservoir/pond at 1,500 m or more: gravel 70, cobble 30;
  - river: sand 20, gravel 50, coarse dirt 15, mud 15;
  - stream: mud 35, gravel 35, coarse dirt 30.
- Steep ground sheds its soil:
  - open land turns to rock from 33°, all rock by 45°;
  - forest starts at 38° and is all rock by 50°.
- Under a cliff (< 40°): scree, gravel 70, cobble 30.

## Snow (`Snow`, `ZoneMap`, `SnowStage`)

- **Local line**: `regional − 200·northness·min(1, slope/20°) − min(160, 30·hollow) + jitter`.
  - North faces lie up to 200 m lower, south faces up to 200 m higher.
  - Hollows and lee (curvature over 4 m from TERR; later TOPO `wind`) lie up to 160 m lower.
  - Jitter is 48 m value noise, ±60 m.
- **Depth** (eighths, `Snow.depth`):
  - it starts half a 160 m band below the local line;
  - 2/8 at the line, a full block ~190 m (1.2 bands) above it;
  - +6/8 per further band, plus up to 4/8 in hollows;
  - at most 24/8 (3 blocks);
  - shedding scales it from 1 at 38° to 0 at 50°;
  - forest canopy holds 0.6 of it;
  - dithered to whole eighths, so the band frays instead of stepping.
- Monotonic: higher ground, a more northern aspect and a deeper hollow never get less snow
  (`GroundZonesTest.snowGrowsWithHeightAndOnNorthSlopes`, `ZoneMapTest.northFacesHoldMoreSnow`).
- **SnowStage**:
  - Full eighths become `snow_block`, the rest one `snow` layer.
  - Plants under the snow are cleared. Grass and podzol get `snowy=true`.
  - Only blocks a snow layer can survive on get one: not ice, mud, dirt path or farmland
    (`ZoneMapTest.stagesEmitValidTops` checks the vanilla rules).
- **Glacier body (SURF 6)**, 3–8 blocks down:
  - a firn cap of `snow_block` where snow lies (snow layers cannot rest on ice);
  - `packed_ice` below it, `blue_ice` deeper;
  - bare packed ice in the ablation zone;
  - gravel/cobble rubble on DEBRIS_GLACIER;
  - CREVASSED: open 1-wide transverse slots across the smoothed flow direction, every 6 m (9 m diagonal), broken up by noise.

## Weather and biomes (choice)

- The problem: vanilla snows where `temperature − (y − 80)·0.05/40 < 0.15`. The old biomes had 0.45 everywhere, so the
  weather snow line was a flat y ≈ 320 (~2,320 m), and the `snow_top_layer` feature painted that flat line.
- The fix: the biome temperature is the only weather channel the client reads, so the biomes follow the zones.
  - Every non-ocean biome gets temperature 2.0, so it rains at every Swiss height. Explicit `grass_color`/`foliage_color`
    keep the old 0.45 colours (glacier keeps its own), so nothing changes visually.
  - New biome `swissworld:snowfield` (temperature −0.5, snows; goats and rabbits).
    `SwissBiomeSource` resolves a 4×4 quart to it when ≥ 8 of its 16 columns hold snow and none holds water.
    Without a chunk it uses the regional line + band; the fallback above 3,200 m is SNOWFIELD.
  - Result: precipitation is snow exactly where snow lies (±2 columns) and rain everywhere else. Snow never settles far
    below the line, and no rain falls on snowfields.
  - `snow_top_layer` is removed from all 5 biomes. `SnowTopLayerFeature` stays registered but unused.
  - The `glacier` biome keeps its name and colours but is warm. Its snow comes from SnowStage.

## For SitePlan (mod-siteplan) and sidecar-zones

- **SitePlan** (`ctx.site()`, branch mod-siteplan): a `Ground` adapter can take `present`/`metres`/`surf` from
  `site.grid()`.
  - `natural(x, z)` becomes `claim ∈ {FREE, EARTHWORK}` with the top at `site.ground(x, z)`.
  - `pathDist`/`railDist` become `distanceTo(Claim.PATH / Claim.RAIL)`.
  - Not adopted yet because SitePlan is not on main. `ZoneMap` can later move into the SitePlan tile unchanged
    (no Minecraft classes, read-only).
- **sidecar-zones** (REGN/LITH/TOPO/HABT, branch sidecar-zones): `Ground.region`/`lithology` take the codes 1:1.
  - TOPO `tpi50`/`tpi300` replace the local TPI ring.
  - `wind` LEE/EXPOSED add to or replace `hollow` in the snow line.
  - `flow` CHANNEL gives DEBRIS_CHANNEL.
  - HABT raised bog/fen/aue give RAISED_BOG / WET_MEADOW / FLOODPLAIN / FOREFIELD.
  - All are defaults in `Ground` today ("unknown"), so old cells keep working. Wire them up in `CellGround` once
    `SwdCell` has the accessors on main.

## Measured

- Synthetic mountain bench (`ZoneMapTest.cheap`, 1,444 chunks): zones 0.19 + texture+snow 0.11 = **0.30 ms/chunk**,
  within the 0.5 ms budget.
- On real cells (`GroundSweepTest`): 0.5–1.3 ms/chunk. This includes the test world's per-column hash maps and the JIT
  on a busy machine.
- Zone shares and block mixes per area: `.data-cache/previews/texture-summary.txt`. Previews:
  `.data-cache/previews/texture-{forest-slope,alpine-grimsel,river-bank-aare,interlaken-meadow,oberaar-snowline}.png`.
  - Left half: blocks in map colours with hillshade.
  - Right half: zones by group, snow hatched white, the regional snow line in red.
- Run the sweep with `SWISSWORLD_STORE=<private store copy> SWISSWORLD_PREVIEWS=<dir> ./gradlew test --tests '*GroundSweepTest'`.
- In-game (headless, private store, 64 columns per area):
  - Oberaar: 36 snow tops; biome snowfield 40 / scree 24.
  - Grimsel: crystalline rock (stone/andesite/cobble/granite) 54, grass 7; biome rock 50 / alpine meadow 14.
  - Forest (Harder): grass/podzol/moss/coarse dirt under leaves.
  - Interlaken: grass + dirt paths.
