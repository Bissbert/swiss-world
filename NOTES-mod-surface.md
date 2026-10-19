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
- **Drop rule.** A column whose cardinal neighbour has a higher source level U gets falling water (level 8) up to U − 1 and one flowing block (level 1) at U. For a straight step this is vanilla's own steady state, so an update leaves it alone. Only the unit tests cover this; the fixture has no river steps. See "What's left" for diagonal steps.
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
  - Railings: a stone brick wall on bridge columns that have a neighbour with no overlay. The wall sides are set explicitly, so they look right without block updates. The bridge ends stay open.
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
- **Cross-chunk trees:** I chose clipping over decoration. Every chunk lists all trees within 3 columns of itself, in a fixed order, and places only the blocks inside itself: all logs first, then leaves, and only into air. Neighbouring chunks therefore complete each other's trees from the same data. A unit test checks that two neighbouring chunks agree on the trees they share.
- **Leaves:** distance = Manhattan distance to the nearest log of the same tree, at most 6, not persistent. Bushes have no log and use persistent leaves. Leaves only random-tick (and decay) at distance 7, so as placed they never decay.
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
- **mod-core:** `SwissChunkGenerator` does not override `applyBiomeDecoration`, and the dimension uses `minecraft:plains`. Plains features therefore run on top of our blocks: oak trees, flowers, and `spring_water`, which schedules fluid ticks. I did not check for them in-game. They break the "no flowing water" and "deterministic vegetation" guarantees. Proposal: a featureless biome, or an empty `applyBiomeDecoration` override.
- **sidecar-tlm:**
  - Rail centre lines (OFLG bit2) must be 4-connected, with no diagonal-only steps, or the derived shapes break.
  - Ramps up to a bridge (OELV) should rise ≤ 1 m per column; the fixture's bridge ends have a 5–7-block step, which I left as it is (it is data).
  - Roads crossing water need bit0 or OELV; otherwise they are skipped.

## What's left
- **Diagonal or cornered river steps.** Vanilla turns flowing water with ≥ 2 source neighbours, on solid ground or a source, into a source (`FlowingFluid.getNewLiquid`). On a 1-block step whose lower column touches two upper sources (a step running diagonally in plan view, or turning a corner), the level-1 block becomes a source on its first update and spreads over the lower river and its banks. Possible fix: raise such lower columns to the upper level, or bank them. This needs real river data to tune.
- **Untested in-game, because the fixture has none of them:** river steps, waterfalls, tunnels, kerbs (bit3), dams, rack beds, platforms, steps, orchards, vineyards, glacier, bushes.
- **Real cells,** once sidecar-tlm data exist.
- **Vanilla decoration** (mod-core, see above).

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
