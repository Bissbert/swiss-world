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
