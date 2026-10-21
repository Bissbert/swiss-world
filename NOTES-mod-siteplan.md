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
branch. Other agents ran on the machine at the same time, so treat single numbers as ±20 %. Forest Rugen's flights
covered 1266 (before) and 1520 (after) chunks.

"chunk" is the chunk's own stage time and leaves out the tile plan, which runs once per 128×128 tile (64 chunks) on the
same worldgen executor. "+plan/flight" adds the flight's plan total spread over its chunks (a flight touches ~50 chunks
per tile); "+plan/tile" adds 215 ms / 64 = 3.4 ms, the steady state once every chunk of a tile is generated.

| site | Overlay before → after | chunk before → after | plan/flight | after +plan/flight | after +plan/tile |
|---|---|---|---|---|---|
| interlaken-ost | 5.23 / 11.6 → 1.55 / 2.9 | 8.21 / 19.5 → 4.71 / 9.7 | 4.0 | 8.7 | 8.1 |
| innertkirchen | 3.17 / 5.8 → 2.02 / 2.9 | 6.09 / 9.7 → 4.28 / 6.9 | 4.2 | 8.4 | 7.7 |
| forest-rugen | 2.90 / 4.9 → 1.82 / 2.9 | 5.71 / 9.7 → 4.69 / 8.2 | 3.2 | 7.9 | 8.1 |
| mountain-faulhorn | 2.73 / 4.1 → 2.15 / 2.9 | 4.58 / 6.9 → 4.31 / 5.8 | 5.0 | 9.3 | 7.7 |

So the work Overlay (and Water, Vegetation) no longer repeat per chunk moved into the plan: per-chunk p95 and the
yard's worst chunks drop a lot, but total CPU per chunk is about even at Interlaken and 1.3–1.7× before elsewhere,
because the plan (205–225 ms per tile) is paid also where little is built. `plan-wait` (a chunk waiting on its tile's
plan, not blocking a thread) is ~20 ms mean, p95 ~220–260 ms: latency for the first chunks of each tile. Building
~unchanged (0.94 → 1.11 at Interlaken), GroundTexture 0.09–0.26, Snow 0.001. Guard violations 0 and plan cell misses 0
in all four flights. Next lever: make the plan cheaper (skip claim passes in tiles with no ways or buildings, cheaper
column grid), it now dominates.

## mod-site2: plan cost, door landings, river steps

**Plan cost.** The tile plan got cheap where little is built. `ColumnGrid` samples row by row (one cell lookup per km
crossed) and keeps prefix sums of overlay, so `TransportPlan` skips every box without overlay (no way, bore, dam or
bridge can be there). `TransportPlan` memoizes `wayKind`/`open` per column, and earthwork reach fields are computed per
block as Chebyshev dilations instead of a window scan per column. `SitePlans.prefetch` (called from
`net/PlayerPrefetch`, mod-core) starts the plans of the tiles a moving player's view reaches in 6 s, when their cells are
already local (≤ 6 per player and second).

**Door landings (LANDING).** `SitePlan` plans the way out of every door of a building with rooms whose bbox lies within
PAD + `LANDING_REACH` (= `BuildingStage.MAX_RUN` + 2) of the tile (`BuildingStage.landing`), in gid order, and claims its
columns LANDING with G = the path's walking level (only FREE, EARTHWORK and BANK columns; the first landing on a column
wins). Overlay writes on LANDING only below G, GroundTexture and Vegetation leave it alone (no tree in front of a door).
A way column the path ends on is a doorstep: Overlay puts no half slab there (a slab under the door is a step up
against the lintel). `BuildingStage` builds the steps for doors up to `STEPS_REACH` outside the chunk.

Rules of the way out (`BuildingStage.trace`): it never runs into water (`CellView.wet`), and it ends on a way level with
it (no step up right outside the door, at most one later). `findDoor` falls back to a door whose outside is not a
neighbour; a building whose every side faces its own body or a neighbour (a courtyard wing mapped on its own) gets
**no door**: it is reached through the others (watertight reports it as "hemmed enclosed"). When the best door has no
traced way out, or only one through a facade column (level 3), `plan` raises the ground floor up to `RAISE` = 6 blocks
(roof permitting) and takes the first height with a proper door.

Checks: `EntranceSweepTest` (real terrain, whole pipeline, store env): every door with generated surroundings opens
onto air with a walk of ≤ 1-block steps to 3 blocks out. 1895 chunks, 454 doors, 0 bad (playtest spots ± 2 chunks
plus `SWISSWORLD_ENTRANCE_AREAS` round Interlaken). Watertight origin cell: 0 bad door, 1 pit; store cells
2631/2633_1170, 2632_1171/1169: 0 bad door, 1 pit (base 0), 1 new thin inside-blocked barn.

**River steps.** `WaterPlan.Fill` carries `floor` and `pour`: a column below a neighbour's water gets falling water from
the floor to the upper level (a rapids column), no stone on the border. `SitePlan.resolve` claims a building footprint
before water: a building mapped into the river stands on its walls and the river flows round it.
`RiverStepSweepTest` (22 Aare/Lütschine cells, 229 step chunks, 31591 water columns): no solid block inside a WATER
claim.

Timings (headless, fresh world, warm-up flight first, same 4 flights, load 6–12; total = chunk mean + the flight's
plan total spread over its chunks; plan-wait p95 = first chunks of a tile):

| site | main: total / plan-wait p95 | mod-site2: total / plan-wait p95 | target |
|---|---|---|---|
| interlaken-ost | 22.3 / 623 | 6.8 / 46 | 8.2 |
| innertkirchen | 17.0 / 524 | 4.5 / 2 | 6.1 |
| forest-rugen | 18.3 / 441 | 6.4 / 3 | 5.7 |
| mountain-faulhorn | 17.6 / 441 | 3.5 / 16 | 4.6 |

Rugen sits ~10 % over its target in this run (5.4 in a cold run before it): its plan is the dearest (114 ms/tile,
forest with many ways). In-game probes: 60 door outside cells air with ground under, 80 river step columns water from
floor to top.
