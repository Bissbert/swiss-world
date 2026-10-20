# NOTES mod-siteplan

Owner of `mod/src/main/java/ch/bissbert/swissworld/gen/site/`: `SitePlan`, `Claim`, `GenStats`, `WriteTrace`,
`SiteCommands`, `SitePlans` (cache), `SiteWriter` (write guard).

## Read API for other stages (mod-texture: GroundTextureStage)

```java
SitePlan site = ctx.site();          // never null in the game; the tile holding ctx.chunk
Claim c   = site.claim(x, z);        // who owns the column
int   g   = site.ground(x, z);       // G: final top solid block y of the column
int   d   = site.distanceTo(Claim.ROAD, x, z); // Chebyshev columns, 0..16
```

- **Validity.** `claim` and `ground`: every column of the tile plus `SitePlan.PAD` (16) around it, so any chunk column
  and its 16 neighbours. `distanceTo`: the tile's own columns (all columns of every chunk in it). Outside: IOOBE.
- **Claim** (`gen/site/Claim.java`), one per column, ordinal = resolution priority, ordinals stable (new ones appended):
  `WATER, BANK, RAIL, ROAD, PATH, STRUCTURE, BUILDING, LANDING, EARTHWORK, FREE`.
  - WATER: any column with water (lake/river/stream/reservoir/pond/fall), and columns outside every cell (ocean).
  - BANK: dry ground raised to adjacent water (`WaterPlan.bankTop`).
  - RAIL / ROAD / PATH: the way's deck (on ground, embankment, bridge, over a tunnel portal). PATH = OCLS 5, 6, 7, 13;
    RAIL = rail bed, rack bed, rail under a road bridge; everything else ROAD.
  - STRUCTURE: bridge/tunnel/dam parts that are not the walking deck (tunnel lining near the surface, walls, dam
    crest/body, piers). A bore deeper than `TreePlan.TUNNEL_COVER` (10) under the terrain leaves the surface FREE.
  - BUILDING: building footprint. LANDING: door landing, way out, steps (lands with the building step).
  - EARTHWORK: ground cut or filled to the way next to it (`TransportPlan.earthwork`).
  - FREE: natural ground.
- **G** (`ground`) = top solid block after the site is built: FREE = terrain; BANK = raised bank top; EARTHWORK = graded
  top; RAIL/ROAD/PATH = deck block; WATER = bed (top of the ground under the water); STRUCTURE = deck/crest/lining top;
  BUILDING = ground under it; LANDING = walking level.
- **distanceTo** = Chebyshev distance (max(|dx|,|dz|)) to the nearest column with that claim, `0` on it, capped at
  `SitePlan.FAR` = 16 meaning "none within 15". Computed lazily per claim per tile (one separable transform, ~0.1 ms).
- **Mapping for mod-texture's `Ground`**: `present`/`metres`/`surf`/... stay on `ctx.site().grid()` (a `ColumnGrid` over
  the tile + `SitePlan.MARGIN`); re-texture only where `claim` is FREE or EARTHWORK, at y = `ground(x, z)`.
- Every value is a pure function of the cell data near the column, keyed by world position: neighbouring tiles agree
  (`SitePlanTest.neighbouringTilesAgreeOnTheirOverlap`).

## Tile

128 x 128 columns, chunk aligned (8 x 8 chunks), `SitePlan.tile(x) = floorDiv(x, 128)`. Margin of data around it:
`SitePlan.MARGIN` = PAD 16 + `TransportPlan.MARGIN` 36 = 52. Per chunk the same margin would sample (16+104)²/16² = 56
columns per column; per tile (128+104)²/128² = 3.3. A 1 km cell is not chunk aligned and holds ~3,900 chunks.

## Cache (`SitePlans`)

`SitePlans.forColumn(cache, coords, x, z, exec)` requests the cells of tile ± MARGIN from `CellCache` (async, never a
synchronous `.swd` read on the gen thread), then the cells the buildings near the tile reach (`BUILDING_REACH` 48),
then computes the plan once on the worldgen executor. LRU of `-Dswissworld.siteplan.cache` tiles (default 24), keyed
by tile and which cells exist; a failed/aborted plan is dropped and asked again. `CELL_MARGIN` in the generator is now
`SitePlan.MARGIN` (tile ± 52 covers chunk ± 36). Cell reads outside the requested set count as `SitePlans.misses()`
(should stay 0). `/swiss admin stats` prints cached plans, misses and guard violations.

## Write guard (`SiteWriter`)

Water, Overlay, Building/Furnish/Loot (via `Put`) and Vegetation write through `SiteWriter.of(ctx, stage)`. Rules by
claim and y against G: water only under the deck of ways/structures/buildings; overlay not above G in BUILDING/LANDING;
buildings not in a way/structure column below G + 3 (they yield: `Put` skips silently, the stage checks first); trees
and ground cover only on FREE/EARTHWORK/BANK, crowns over ways/water only above G + 5. A dropped write is counted and,
with `-Dswissworld.trace=true` or `-Dswissworld.guard.log=true`, logged (first 200). New stages that write should use it.
Sweep: `SWISSWORLD_SWEEP_STORE=<store> [SWISSWORLD_SWEEP_CHUNKS=4] ./gradlew test --tests '*SiteGuardSweepTest*'`
(asserts 0 violations; 789 chunks / 27 tiles at grow 4). `PipelineGoldenTest` (GOLDEN_OUT/GOLDEN_IN) hashes chunks for
refactoring checks.

## Rail over road bores

`TransportPlan.seals`: a road/path bore under an open rail yard whose cover is thinner than the bore loses to the rail:
the rail column is built as open rail, small road-tunnel stubs and orphan lining/walls between the tracks are filled
to the yard ballast (`yardDeck`, claim RAIL), absorbed platforms are built as platforms again. Fixes the stone-brick /
grass bumps and pits between the tracks at Interlaken Ost.

## Ground texture reads the claims

`GroundTextureStage.apply(zm, b, site)` (mod-texture's stage, minimal seam): with a site plan only FREE, EARTHWORK and
BANK columns are dressed (never way, structure, building, landing); FREE/EARTHWORK ground within 2 of a RAIL claim and
12 of a BUILDING claim (a station yard) gets the INDUSTRIAL palette.

## Measured (headless, fresh world, bot flight, `/swiss admin stats`, mean / p95 ms per chunk)

Both on main af4d13a's pipeline (with GroundTexture and Snow); "before" is 19e95a9 (timers only), "after" is this
branch. Other agents ran on the machine at the same time, so treat single numbers as ±20 %.

| site | Overlay before → after | Vegetation | Water | chunk |
|---|---|---|---|---|
| interlaken-ost | 5.23 / 11.6 → 1.55 / 2.9 | 0.29 → 0.12 | 0.13 → 0.07 | 8.21 / 19.5 → 4.71 / 9.7 |
| innertkirchen | 3.17 / 5.8 → 2.02 / 2.9 | 0.46 → 0.25 | 0.10 → 0.03 | 6.09 / 9.7 → 4.28 / 6.9 |
| forest-rugen | 2.90 / 4.9 → 1.82 / 2.9 | 0.57 → 0.38 | 0.09 → 0.03 | 5.71 / 9.7 → 4.69 / 8.2 |
| mountain-faulhorn | 2.73 / 4.1 → 2.15 / 2.9 | 0.11 → 0.03 | 0.08 → 0.03 | 4.58 / 6.9 → 4.31 / 5.8 |

Building ~unchanged (0.94 → 1.11 at Interlaken), GroundTexture 0.09–0.26 and Snow 0.001 both before and after.
A plan costs ~205–225 ms once per tile (20–25 tiles per flight) on the worldgen executor; `plan-wait` (a chunk waiting
on its tile's plan, off the chunk time, not blocking a thread) is ~20 ms mean, p95 ~220–260 ms: the first chunks of a
tile. Guard violations 0 and plan cell misses 0 in all four flights.
