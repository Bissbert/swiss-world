# NOTES — mod-surface

Milestone 1: WaterStage, OverlayStage, VegetationStage in `mod/src/main/java/ch/bissbert/swissworld/gen/surface/`.

## Structure

- `ColumnGrid` samples the cell data for the chunk plus 8 columns around it, in block y. Every decision that involves a neighbour column is made from this data, never from blocks in the chunk, so two chunks that share a border make the same choice. It has no Minecraft dependencies and is unit-tested against the golden fixture.
- `WaterPlan`, `OverlayPlan` and `TreePlan` are pure decision helpers. The `*Stage` classes only turn their decisions into blocks.
- `ColumnHash` is a SplitMix64 hash of (LV95 E, LV95 N, salt). All randomness comes from world position, never from RNG state.
- Height convention: height h occupies block `floor(toY(h))`. Rails, plants, kerbs and railings go one block above it.

## Decisions

### Water
- Sources fill from ground + 1 up to `floor(toY(WLVL))`. Lakes are flat. Rivers step wherever the sidecar steps WLVL.
- **Drop rule.** A column whose cardinal neighbour has a higher source level U gets falling water (level 8) up to U − 1 and one flowing block (level 1) at U. For a straight step this is vanilla's own steady state, so an update leaves it alone.
- **Diagonal or cornered steps (rock rule).** Vanilla turns flowing water with ≥ 2 source neighbours at its level, standing on a source or solid, into a source (`FlowingFluid.getNewLiquid`). The real cells have many such corners in steep 1–2-wide streams, and each one flooded the lower river on its first update. Where a drop would put such a block, the lower column is stone from its source level up to the upper level instead (`WaterPlan.Fill.rock`). Raising the column instead would cascade, because the raised column becomes a new upper neighbour.
- **WKND 6 (waterfall).** My reading: a waterfall column is a 1-block source pool on its ground, with falling water above it only up to a higher neighbour's source level. Its own WLVL never raises it, because falling water with no source beside its top drains on the first update.
- **Banks.** A dry column next to a higher source is filled from its ground up to that source level: stone, with gravel on top. This includes ocean columns, which is how border lakes keep their level behind a solid rim (PLAN §13).
- No fluid ticks are scheduled. Blocks go in with `ProtoChunk.setBlockState(.., false)`, which (checked in the 1.21.1 source) only updates light and heightmaps, and nothing is marked for post-processing.
- In-game, after forcing updates at the rim and lake edge and waiting 10 s, nothing had flowed.

### Overlay
- The deck is at OELV, or at the terrain top (after banks) when OELV is unset.
  - Below a raised deck that isn't a bridge: a stone embankment.
  - Above a sunken deck that isn't a tunnel: a cutting.
  - A cutting never goes below a neighbour's water level.
- Water columns: an overlay with no bridge flag, no dam and no OELV is skipped rather than drowned.
- Surface materials by class:

  | Class | Surface |
  |---|---|
  | 1, 2 | gray concrete |
  | 3 | polished andesite |
  | 4 | andesite |
  | 5 | coarse dirt or gravel (by hash) |
  | 6 | dirt path |
  | 7 | stone brick stairs facing the highest neighbouring step (no higher neighbour: full stone bricks) |
  | 8, 9 | gravel, andesite where bit3 is set |
  | 10 | smooth stone with a slab on top |
  | 11, 12, 14 | smooth stone |
  | 13 | grass |

- Kerbs: a smooth stone slab on road classes 1–4 where bit3 is set.
- Dams: bit4 fills the body with light grey concrete from the bed up to the deck.
- **Bridges (bit0):**
  - A stone brick beam under the deck.
  - Railings: a stone brick wall on a bridge column that has a neighbour with no overlay, but only where the deck goes on behind it (2 columns, or 1 on the east/south side) and alongside it. So decks 3+ wide get railings on both sides, 2-wide decks on the east/south side only, 1-wide decks and the stepped edges of diagonal decks none: in the real cells, narrow and diagonal footbridges were otherwise walled shut. The wall sides are set explicitly, so they look right without block updates. The bridge ends stay open.
  - Piers: stone bricks every 12 m of LV95 along the bridge, across its full width. The bridge's direction is taken from the axis with more bridge columns within 4.
- **Tunnels (bit1):**
  - 6 blocks of clear height above the floor.
  - Under water the lake bed stays closed.
  - Glowstone set into the roof where `(3x + 5z) mod 37 == 0` and there is rock above.
- **Rails:** only on class 8 + bit2. The shape comes from the four neighbouring rail centres: a straight pair first, then a curve, then pointing at the single neighbour; an isolated rail runs N–S. A straight rail ascends where the next deck is exactly one block higher. Vanilla rails only reshape when a redstone source changes next to them, so the shapes stay as placed. Class 9 (rack) gets a bed only.
- **Rail under a road bridge** (swd-format §5 on main acccce9: bridge bit + road class 1–7 + bit2): the road deck stays at OELV. The rail goes on gravel ballast at the terrain top under it, provided there is at least one free block below the beam. Rail shapes and slopes use the rail's bed height, so the track runs straight on under the bridge. No pier within 2 columns of such a track.

### Vegetation
- **Tunnels and vegetation:** a tunnel column whose deck is more than 10 blocks below the ground counts as open ground, so mountains above tunnels keep their trees and ground cover.
- **Tree positions:** one candidate per 4 m LV95 grid square, jittered by hash and kept with a probability that depends on SURF:

  | SURF | Keep probability |
  |---|---|
  | forest | 0.9 |
  | sparse (14) | 0.125 |
  | urban green (11) | 0.05 |
  | alpine pasture (17) | 0.003 |
  | meadow | 0.0025 |

  Orchards (12) are a 7 m lattice of small fruit trees instead.
- **Whether a tree may stand** is decided from data only:
  - the cell is present;
  - the column is below 2,300 m;
  - it isn't a raised bank;
  - no overlay or OFLG within 3 columns;
  - no building within 2;
  - no water within 1.
- **Species:**
  - broadleaf (oak 70 % / birch) below 1,000 m;
  - mixed up to 1,500 m;
  - spruce up to 2,000 m;
  - "larch" (spruce log, sparse spruce leaves) from 2,000 to 2,300 m;
  - nothing above 2,300 m.
  - SURF overrides: conifer forest (2) is always spruce; sparse (14) and urban green (11) are 40 % bushes.
- **Real trees (TTOP, swd-format §6a):** a cell with a `TTOP` table gets exactly its trees and no statistical placement. The kind maps to a species: conifer → spruce (larch from 2,000 m), larch → larch, broadleaf → oak or birch by hash, unknown → by SURF and altitude (never dropped; under 4 m → bush). Height is `height_m`; the crown radius is `crown_dm` / 10, or derived from the height when 0. A real tree is skipped only where its own column is not dry open land (water, bank, overlay, building).
- **TTOP/CNPY interface:** `TreeData` (`tops(eKm, nKm)`, `canopy(eKm, nKm)`) with parsers in `TreeTops`. The game passes `TreeData.NONE` until mod-core adds `SwdCell.trees()` / `canopy()`; hooking it up is a one-line `VegetationStage(TreeData)` change. CNPY is parsed but not yet used for placement.
- **Statistical heights:** forest SURF 1–3 below 2,000 m: 18–35 m; everything else (sparse, urban, meadow, larch band): 8–20 m; fruit trees 4–7 m.
- **Shapes scale with height and crown radius:** spruce is a tiered cone from 0.3 h; larch a sparse cone from 0.35 h; oak an ellipsoid centred at 0.62 h with a spreading trunk; birch a narrow ellipsoid. Default radius: spruce 1 + 0.12 h, larch/birch 1 + 0.1 h, oak 0.22 h. Wide crowns get horizontal branch logs (axis x/z), so no leaf is more than 6 from a log. Example: a 35 m fixture spruce (radius 5) has 46 logs and 745 leaves.
- **Cross-chunk trees:** I chose clipping over decoration. Every chunk lists all trees whose crown can reach it, in a fixed order, and places only the blocks inside itself: all logs first, then leaves, and only into air. Crowns skip building columns, and over a road or rail they keep 5 blocks above the deck. Neighbouring chunks therefore complete each other's trees from the same data. Unit tests check that two neighbouring chunks agree, including with the widest crowns.
- **REACH (CELL_MARGIN unchanged at 8):** real-tree crowns are clamped to `REACH = MARGIN − 1 = 7`, because a trunk's `top()` (bank check) looks one column further. Statistical trees check 3 columns around their trunk, so their crowns are capped at `STAT_REACH = MARGIN − 3 = 5`. Wider crowns would need a larger CELL_MARGIN.
- **Leaves:** distance = vanilla distance, found by a breadth-first walk from the tree's logs through its own leaves (6-connected). Leaves the walk does not reach within 6 are left out, so as placed no leaf decays and an update changes no distance (unit test for all species, heights 2–40 and radii 1–7). Bushes have no log and use persistent leaves. Where two trees overlap, an update may raise a leaf's distance, but never above 6 next to its own logs.
- **Ground cover:**
  - short grass and flowers on meadow, urban green and alpine pasture (alpine flower set above 1,500 m, nothing above 2,600 m);
  - ferns in forests;
  - grass and sweet berry bushes in sparse areas;
  - grass in orchards.
  - Plants are only placed on grass_block with air above.
- **Vineyards (13):** east–west rows every 3 m N, an oak fence post every 5 m, persistent oak leaves between the posts.
- **Glacier (6):** the top 3–8 blocks (by hash) become snow_block on top, then 2 packed_ice, then blue_ice.

## Touched outside owned paths
- `mod/src/main/java/ch/bissbert/swissworld/gen/SwissChunkGenerator.java` (mod-core): added `CELL_MARGIN = 8`, and `fillFromNoise` now loads the cells under the chunk's corners widened by that margin. Without this, a chunk next to an aligned cell edge (z = −400 + 2000k) cannot see its neighbour columns, and banks and trees across the edge would depend on load order. `ColumnGrid.MARGIN` refers to this constant.

## For other workstreams
- **mod-core:** `SwissChunkGenerator` does not override `applyBiomeDecoration`, and the dimension uses `minecraft:plains`. Plains features therefore run on top of our blocks: oak trees, flowers, `spring_water` (which schedules fluid ticks), and freeze_top_layer (ice and snow at altitude, seen in-game on real cells). They break the "no flowing water" and "deterministic vegetation" guarantees. Proposal: a featureless biome, or an empty `applyBiomeDecoration` override.
- **sidecar-tlm:**
  - Rail centre lines (OFLG bit2) must be 4-connected, with no diagonal-only steps, or the derived shapes break.
  - Ramps up to a bridge (OELV) should rise ≤ 1 m per column; the fixture's bridge ends have a 5–7-block step, which I left as it is (it is data).
  - Roads crossing water need bit0 or OELV; otherwise they are skipped.

## What's left
- **Still untested in-game:** kerbs (bit3), rack beds, platforms, orchards, vineyards, bushes (none sampled in the real-cell run).
- **Shallow tunnels** (deck only a few blocks below the ground, e.g. in 2647_1177) come out as open trenches, because the 6-block clearance reaches the surface. A real cut-and-cover would need a roof; left as it is.
- **Bridge ramps:** near the end of the diagonal footbridge at (14567, −6918), where the deck changes height, a pair of railing columns still blocks the path.
- **Vanilla decoration** (mod-core, see above). Confirmed in the real-cell run: at high altitude (e.g. Gauli, y ≈ 670–745) the plains biome's freeze_top_layer turns river source tops into ice and adds snow layers. Level-1 blocks fed by a frozen source then drain (12 of 54 and 8 of 24 water blocks at two glacier steps). This is not a WaterStage bug.

## Contract proposals
None. No contract file was changed.

## Verification (fixture cell in a private store, ports 25660/25661)
- **Store:** the private store is in the session scratchpad at `<scratch>`, with `cells/2632_1170.swd` copied from `fixtures/synthetic_2632_1170.swd`.
- **Lake:** water at (−890, −1436, 500) with air above; water down to −1449 over the gravel bed at −1450. Rim at x −901: gravel at −1436, stone below, air outside at x −902.
- **Bridge:** polished andesite deck at (−350, −1360, −100) on a stone brick beam at −1361; stone brick walls at (−350, −1359, −102) and (−350, −1359, −98); no leaves above the deck.
- **Rail:** rail at (−200, −1364, −390) on gravel at −1365.
- **Forest:** 430 spruce logs and 3,452 leaves in x −380…−360, z 200…240; no oak or birch logs there.
- **Stability:** setblocks next to the rim and lake edge, then a 10 s wait: the rim stayed dry and the lake source unchanged.
- **Unit tests:** `WaterPlanTest`, `OverlayPlanTest`, `TreePlanTest` (`./gradlew build` passes). The waterfall and tunnel-cover changes came after the in-game run. They are covered by unit tests, and the fixture has neither feature.

## Verification (tree shapes, fixture in a private store `…/scratchpad/fixstore`, ports 25660/25661)
- **Forest chunk x −352…−337, z 192…207:** 571 spruce logs (101 along x, 55 along z, 415 along y) and leaves at distances 1–5 only (1302/1474/766/192/11). There are none at 6–7, so nothing decays.
- **Spruce at (−347, 193):** 35 m tall, radius 5, trunk y −1357…−1324 with its top leaf at −1323. Of its 791 planned blocks, 46 logs and 718 leaves are in place. The other 27 leaf spots hold neighbouring trees' logs, which are placed first.
- **Stability:** after 30 s at randomTickSpeed 1000, all 791 positions were unchanged.

## Verification (real cells, private store, ports 25660/25661)
- **Cells** were built from main (acccce9) with `build.assemble` + `Store.write`, using the cached raw data and TLM extract (no downloads), into the private store `<scratch>`:
  - 2656_1173: Reichenbach waterfall, weir, steps
  - 2647_1177: rail under a road bridge, tunnel
  - 2668_1158: Grimsel dam, reservoir, tunnels
  - 2652_1170: conifer forest
  - 2660_1163: rock and scree
  - 2656_1163: Gauli glacier
- **Rail under a road bridge** (14584, −6471): polished andesite deck at −1423 on a stone brick beam at −1424; rail at −1430 on gravel at −1431; railings on both sides of the 4-wide deck.
- **Rail** (14108, −6727): rail on gravel.
- **Deep tunnel, Grimsel** (35533, 12364): andesite floor at −89, clear up to −83, rock roof.
- **Grimsel dam** (36091, 12175): smooth stone crest at −86 over a light grey concrete body down to the ground at −109. Weir at (23771, −3106): 1 block of concrete.
- **Waterfalls and steps:** source / falling / level-1 layout as planned; bridge piers reach the riverbed; steps are stone bricks.
- **Water stability:** at 14 sample points (river steps, waterfalls, reservoirs) I updated every column top within 3 blocks, waited 20 s, and counted water by level in a 25×40×25 box before and after.
  - 12 of 14 unchanged, including reservoirs of 730 and 663 blocks.
  - 2 glacier points lost a few level-1 blocks to vanilla ice (see "What's left").
  - Before the rock rule, the same points grew by 2–4×.
  - Measuring note: counting with `fill … replace water` and back restores *sources* and floods the world, so a count must swap each `water[level=N]` to barrier and back to the same N.
- **Bridges:** the 2-wide deck at (23971, −3227) has a railing on its south side only; the diagonal footbridge at (14567, −6918) is open along its stepped edge.
- **Conifer forest:** 178 spruce logs and no oak or birch in a 29×36×29 box at (20080–20108, 580–608).
- **Glacier:** snow_block over packed_ice over blue_ice (153 / 306 / 344 in a sample box).
