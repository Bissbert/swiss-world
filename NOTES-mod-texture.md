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
  `cliffAbove`, and for the texture `smoothSlope`, `convexity`, `tpi`, `cliffDist`, `drop`, `gmat`.
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
| SURF 6, slope 25–45° on a convex break (curvature over 4 m < −0.15 m) | CREVASSED |
| SURF 6, snow < 4/8 and ≤ 4 columns from open ground | DEBRIS_GLACIER |
| SURF 6 otherwise | GLACIER |
| building or SURF 10 | URBAN_SEALED |
| HABT (if the cell has it; never on SURF 11/12/13/16): forest in a river/delta Aue < 1,300 m, < 15° | RIPARIAN (other forest keeps its rules) |
| HABT raised bog, < 12° / bog margin or fen, < 15° | RAISED_BOG / WET_MEADOW |
| HABT Aue: glacier forefield < 25° / alpine plain < 10° near water / river or delta < 8° / lake shore < 8° | FOREFIELD or MORAINE / RIVERBED_GRAVEL / FLOODPLAIN (RIVERBED_GRAVEL on gravel banks) / ALPINE_LAKE_SHORE (≥ 1,500 m), REED_SHORE (≤ 6 columns from water), WET_MEADOW |
| SURF 4: slope < 25° under a cliff | SCREE_CONE |
| SURF 4: slope < 18° | KARST (limestone), else FOREFIELD (near a glacier or within 500 m below the snow line), else BOULDER_FIELD |
| SURF 4 otherwise | cliff by lithology: LIMESTONE_CLIFF / CRYSTALLINE_CLIFF / SCHIST_SLOPE (schist, molasse); unknown lithology by region |
| SURF 5 near a glacier | MORAINE (TPI > 2 m) / FOREFIELD |
| SURF 5, slope ≥ 20° | DEBRIS_CHANNEL (TOPO flow gully/channel, else a hollow without TOPO; < 40°, not nival) / SCREE_CONE |
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
| open, TOPO wet hollow, below the alpine belt, < 4° | WET_MEADOW |
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

## Ground texture (`GroundTexture`, `Landform`)

Order per column: fringes (rail ballast, path edge, shore) → **material class** → block. The class is one of soil,
dry grass, bare soil, rock, scree:

1. **GMAT** (orthophoto, optional `u8` layer, see `docs/swd-format.md`) decides for OPEN, ROCK and LOOSE zones:
   2 green grass → soil, 3 dry grass → DRY_GRASSLAND palette, 4 bare soil → coarse dirt / dirt / rooted dirt by tone,
   5 light rock / 6 dark rock → rock family at tone 0.55–1 / 0–0.45, 7 scree → scree family. Tone = high nibble / 15.
   Classes 0, 1, 8, 9, 10 and forest, snow zones fall through to the landform.
2. **Landform** (no randomness; boundary noise only frays edges, `Landform.over`, ±0.2 on a 0..1 field, 12 m lobes
   with 5 m fray):
   - Fields per column in `ZoneMap` (all inside REACH 8 except the cliff probe, which reads terrain by world position):
     `smoothSlope` (3×3 m blur, central difference over 6 m), `convexity` (smoothed height minus a 6 m ring mean),
     `tpi` (the 50 m TPI the zones use, with TOPO), `cliffDist` (metres up the fall line, 2 m steps up to 16 m, to a
     step > 52° rising ≥ 3 m above the column; 127 = none), `drop` (blocks above the lowest 4-neighbour), `gmat`.
   - convex = clamp((convexity + 0.12·tpi) / 1.2, −1, 1).
   - Soft zones: rockiness = smoothstep((slope − from) / 20), from = 32 (37 in forest), −3 alpine/nival, −9·convex on
     ribs, +6·|convex| in hollows, never below 26 → **no rock below 26°**, meadows stay grass to ~38–40°.
   - Cliff zones: from = 30 − 10·convex⁺ + 8·convex⁻ (≥ 16), width 18, at least 0.62·smoothstep((slope − 26 +
     10·convex⁺) / 10) — a mapped cliff on gentle smoothed ground (bench, mislabelled step) keeps turf. KARST: max(that, 0.75 + 0.5·convex). SCHIST_SLOPE: 6° later.
   - Scree: cone below a real cliff (1 − dist/20, weaker on the flat), steep gullies (convex < −0.3 and > 24°; 0.85 at
     subalpine and above, 0.45 below), thin alpine/nival soil (0.35 / 0.6 from 22°), zone priors (SCREE_CONE 0.9 under
     a real cliff else 0.3, BOULDER_FIELD 0.8, FOREFIELD 0.8, DEBRIS_CHANNEL 0.75, MORAINE 0.45 + 0.25·convex, FIRN
     0.6, SCHIST_SLOPE 0.35). Forest × 0.4.
   - Rock zones whose class is soil get turf by belt (alpine turf at alpine/nival, else alpine pasture).
3. Tended zones (garden, orchard, vineyard, arable, sports) turn rock/scree into an embankment: coarse dirt 50,
   cobble 25, gravel 25.

**Families** (ordered dark → light; `tone` picks along the list: 0.6·value(56 m) + 0.3·value(17 m) + 0.1·value(5 m),
stretched ×1.8, plus ±0.15 patch grain, so a face shades between neighbouring greys in large smooth patches):

| Lithology | Rock | Scree |
|---|---|---|
| limestone | stone 15, andesite 30, smooth stone 22, diorite 28, calcite 5 | cobble 25, gravel 50, diorite 25 |
| crystalline | tuff 15, stone 45, andesite 40 | cobble 35, gravel 40, andesite 25 |
| schist/flysch | cobbled deepslate 20, deepslate 30, tuff 35, stone 15 | cobbled deepslate 30, tuff 30, gravel 40 |
| molasse | stone 30, dripstone 25, andesite 33, sandstone 12 | coarse dirt 25, cobble 30, gravel 45 |
| quaternary | coarse dirt 30, cobble 35, gravel 35 | coarse dirt 35, cobble 15, gravel 50 |
| unknown | tuff 20, stone 50, andesite 30 | cobble 40, gravel 60 |

No granite anywhere (it read pink). `SnowStage` treats every rock-family block as rock.

**Soil palettes** (`Noise.patch`, patches of a few metres; no rock or gravel in any soil palette any more):

| Zone | Top |
|---|---|
| HAY_MEADOW | grass 97, coarse dirt 3 |
| PASTURE | grass 95, coarse dirt 5 |
| ALPINE_PASTURE | grass 93, coarse dirt 7 |
| ALPINE_TURF | moss 8, grass 84, coarse dirt 8 |
| DRY_GRASSLAND | grass 82, coarse dirt 18 |
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
| LARCH_PINE | grass 45, moss 15, podzol 28, coarse dirt 12 |
| DRY_PINE | grass 40, podzol 18, coarse dirt 42 |
| RIPARIAN | grass 40, rooted dirt 10, moss 20, mud 20, clay 10 |
| CHESTNUT | grass 50, rooted dirt 25, coarse dirt 25 |
| KRUMMHOLZ | grass 50, moss 25, podzol 25 |
| FOREST_EDGE | grass 80, rooted dirt 10, coarse dirt 10 |
| CLEARING | grass 70, rooted dirt 10, coarse dirt 20 |
| AVALANCHE_TRACK | grass 72, coarse dirt 28 |
| REED_SHORE | grass 40, mud 40, clay 20 |
| SNOW_PATCH (under the snow) | grass 90, coarse dirt 10 |
| RIVERBED_GRAVEL | sand 10, gravel 75, cobble 15 (own bed, no landform) |
| ALPINE_LAKE_SHORE | grass 20, gravel 55, cobble 25 (own bed, no landform) |
| URBAN_SEALED, WATER, glacier zones | not re-textured |

**Fringes on soft zones** (unchanged): rail ballast (1 column 60 %, 2 columns 25 %; gravel 65, andesite 15, coarse
dirt 20); path edge (45 % / 15 %; dirt path 35, coarse dirt 65); shore (70 % / 35 %; lake < 1,500 m sand 35, gravel 40,
clay 10, mud 15; lake ≥ 1,500 m gravel 70, cobble 30; river sand 20, gravel 50, coarse dirt 15, mud 15; stream mud 35,
gravel 35, coarse dirt 30).

**Risers:** under a soil top `GroundTextureStage` puts earth down to `drop + 1` blocks (plus any rise of the real top
over TERR, max 16): the 3 under-blocks as before, then dirt with bands of coarse and rooted dirt (`GroundTexture.riser`).
Only plain blocks (TerrainStage stone/dirt) are replaced. Rock and scree columns keep rock below.

**Why the old look was wrong** (screenshot 16.57.31, E 2648.3 N 1160.8, crystalline): the steep rule used the raw 2 m
slope with per-column patch noise (25 % of ground there turned rock column by column on every terrace step), the scree
fringe keyed on `cliffAbove` from the same rough 2 m slope (19 % gravel/cobble far from any real cliff), soft palettes
carried rock and gravel as 8–24 % speckle, and the crystalline palette had 15 % granite (the pink blotches).

### Look-ground pass (Grimsel, 1950 m)

- Contour stripes: `riser()` used 2D noise on `e + n` keyed by y (patches stretched along the contours) and the first
  `UNDER` blocks were always dirt. Now `Noise.value3` (isotropic 3D noise), and `GroundTexture.face()` gives exposed
  faces of turf columns (`d < drop`) moss/dirt blobs. The real source of most stripes is the vanilla grass_block side
  (brown) on every step: on turf steps (`drop >= 1`, open/loose/rock zones, not tended or wet) 70% of the tops are
  moss_block (green on all sides), in 4 m patches. Lowland slopes show more moss now (Interlaken about 26% moss tops).
- Steep photo-soil: `Landform.faceRock(slope, belt)` turns GMAT soil/dry-grass/bare columns to the lithology's rock from
  46 degrees (44 alpine), all rock by 56-58. Exposed faces of soil columns with smoothed slope >= `FACE_ROCK` (45) are
  rock (ledges stay turf). GMAT DRY_GRASS above montane is ALPINE_PASTURE, not DRY_GRASSLAND.
- Bare earth: alpine palette coarse dirt 7 -> 3 (pasture), 8 -> 3 (turf), avalanche track 28 -> 18. Tended zones (garden,
  orchard...) use the rock family where the photo says rock/scree (EMBANKMENT only for landform-only rock). The
  PATH_EDGE fringe is skipped over photo rock/scree.
- Open: gmat.py reads shadowed dark vegetation as dark rock at g1 (sidecar, not touched; needs cell rebuild if fixed).
- No sidecar change: only newly generated chunks change, no cell rebuild or version bump.

## Snow (`Snow`, `ZoneMap`, `SnowStage`)

- **Local line**: `regional − 200·northness·min(1, slope/20°) − min(160, 30·hollow) + jitter`.
  - North faces lie up to 200 m lower, south faces up to 200 m higher.
  - Hollows and lee (curvature over 4 m from TERR, +3 m with TOPO wind lee) lie up to 160 m lower; TOPO exposed: +80 m, depth × 0.6.
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
- **sidecar-zones** (REGN/LITH/TOPO/HABT, on main since 0d6077a): wired in `CellGround` → `Ground` → `ZoneMap`.
  Each layer is optional; 0 / absent falls back to the earlier rules, so old cells keep working.
  - `region` (REGN bits 0–2) = `Region` id 1:1; 0 → the E/N lookup.
  - `lithology` (LITH class) = `Lith` id 1:1; 0 → the positional guess. Detail bits and the glacier/lake flags are unused.
  - `topo` (raw TOPO byte, −1 without the layer):
    - TPI-50 ridge/valley pins the local TPI to ≥ +5 / ≤ −5 m; flat/mid clamp it to ±2 m.
    - wind lee: +3 m hollow in the snow line and depth (up to 90 m lower, deeper);
      exposed: line +80 m, depth × 0.6.
    - flow gully/channel → DEBRIS_CHANNEL on steep loose ground; a mapped "none" overrides the local hollow; wet hollow → WET_MEADOW.
  - `habitat` (raw HABT byte, −1 without the layer): see the HABT rows in the zone rules.
  - Tests: `GroundZonesTest.habitatAndFlowLayers`, `ZoneMapTest.layersOverrideFallbacks` (all-zero layers = no layers;
    REGN/LITH win; lee adds, exposed removes snow), `ZoneMapTest.chunkBordersAgreeWithLayers`.
  - Test cells: for 48 cells of the private clone only (sweep areas and neighbours), a one-off script decoded each cell
    (`swd.decode`), ran `ZonesBuilder().build(e, n, Config.from_env(), LayerResult(layers=cell.layers), manifest sources)`,
    added the four layers and wrote it back (`swd.encode`, `swd.write_atomic`), as sidecar-zones did.

## Measured

- Synthetic mountain bench (`ZoneMapTest.cheap`, 1,444 chunks): zones 0.12 + texture+snow 0.08 = **0.20 ms/chunk**.
  The cliff search is now a separable 13×13 max filter (was a disc scan per column).
- Real cells with all four layers (`GroundSweepTest`, warm pass after an untimed one, 3 runs, zones + texture/snow):
  0.17–0.41 ms/chunk per area (forest 0.27–0.31, Grimsel 0.29–0.41, Aare 0.25–0.37, Interlaken 0.16–0.25,
  Oberaar 0.28–0.39, glacier 0.21–0.24, fen 0.27–0.30). One earlier, busier run measured 0.54 for the fen and 0.74 for Grimsel
  before the cliff search became a max filter.
- Zone shares (% of ground columns):
  - forest-slope: fir_spruce 95, avalanche_track 2.5, limestone_cliff 2.2.
  - alpine-grimsel: crystalline_cliff 44, alpine_pasture 25, alpine_turf 22, scree_cone 8.5.
  - river-bank-aare: garden 37, water 30, hay_meadow 16, urban_sealed 7.5, beech 3.8, forest_edge 2.3.
  - interlaken-meadow (the Höhematte, SURF urban green): garden 75, urban_sealed 22.
  - oberaar-snowline: crystalline_cliff 33, forefield 30, scree_cone 28, moraine 3.6, glacier 3, firn 2.4; snow on 78.5 %.
  - oberaar-glacier: crystalline_cliff 42, glacier 34, scree 8, debris_glacier 7, forefield 4, crevassed 3.7
    (crevassed 8 % of the ice, was 96 % with the plain slope rule).
  - grimsel-fen (HABT fen): wet_meadow 36, alpine_pasture 32, scree 12, boulder 9, krummholz 6.
- Weather check: 98 % (Oberaar) / 94 % (glacier) of snowy ground lies in snowfield quarts; 3 % of snowfield ground is bare.
- Steep spots in gardens, orchards, vineyards and fields take an embankment palette (coarse dirt, cobble, stone,
  gravel) instead of bedrock: no white calcite on the Aare bank.
- Zone shares and block mixes per area: `.data-cache/previews/texture-summary.txt`. Previews:
  `.data-cache/previews/texture-{forest-slope,alpine-grimsel,river-bank-aare,interlaken-meadow,oberaar-snowline,oberaar-glacier,grimsel-fen}.png`.
  - Left half: blocks in map colours with hillshade.
  - Right half: zones by group, snow hatched white, the regional snow line in red.
- Run the sweep with `SWISSWORLD_STORE=<private store copy> SWISSWORLD_PREVIEWS=<dir> ./gradlew test --tests '*GroundSweepTest'`.
- In-game (headless, private store, 64 columns per area):
  - Oberaar: 36 snow tops; biome snowfield 40 / scree 24.
  - Grimsel: crystalline rock (stone/andesite/cobble/granite) 54, grass 7; biome rock 50 / alpine meadow 14.
  - Forest (Harder): grass/podzol/moss/coarse dirt under leaves.
  - Interlaken: grass + dirt paths.
  - With the layers (second run): Oberaar 64/64 snow tops, biome snowfield 64; glacier ice (24196, 15047) 64 `snow_block`
    tops over `blue_ice` 4 blocks down (47; 17 rock), biome snowfield 64; Grimsel fen grass 40, moss 11, mud 9;
    Aare water 25, grass, gravel 5. No exceptions in `latest.log`.

### mod-texture2 (landform texture)

- ms/chunk, zones + texture/snow, 256×256 m, best of 5 warm rounds (`TexturePerfTest`, `SWISSWORLD_PERF=1`),
  before → after (different sessions, run-to-run noise about ±0.15): Interlaken 0.73 → 0.42, Innertkirchen
  0.89 → 0.77, Faulhorn 0.76 → 0.62, Grimsel 0.53 → 0.78. Well inside the +1 ms budget.
- `TexturePreviewTest` (`SWISSWORLD_PREVIEWS=<dir> SWISSWORLD_PREVIEW_TAG=before|after`): rock tops % of ground /
  of soft ground, before → after: screenshot spot 64.6/51.6 → 56.8/40.0 (granite 9.1 → 0), Harder Kulm 14.5/11.2 →
  8.1/4.6, Innertkirchen flank 7.1/4.1 → 5.6/2.4, Faulhorn 48.2/25.6 → 32.1/10.6, Grimsel 54.1/22.1 → 30.4/9.7,
  Oberaar 19.6 → 18.2. Lone rock tops in soil below 25°: ≤ 27 per 65,000 columns.
- At the screenshot spot the mapped cliff zones really are steep: of 45,900 cliff columns 92 % sit at 40–70° smoothed
  slope, so the remaining grey there is legitimate face; 71 % of SCREE_CONE columns had no real cliff above (prior
  now 0.3). Stone in soil step faces: 0.
- Previews: `.data-cache/previews/texture2/<area>-{before,after}-{top,oblique}.png`.
