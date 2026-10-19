# NOTES mod-content (Milestone 1)

## What is there
- `gen/building/`: pure planner (`Columns`, `BuildingPlan`, `Style`, `RoomType`, `PropKind`, `Dir`, `Hash`) plus three
  stages that only render the plan: `BuildingStage` (shell), `FurnishStage` (stairs, partitions, lights, props),
  `LootStage` (containers). `Buildings.near` finds buildings whose bbox touches a chunk; `CellView` addresses BSPN
  by absolute block column.
- `loot/`: `swissworld:auto_mix` loot pool entry type, `LootPools` (lazy auto-mixed pools), `LootEvents`
  (registration + invalidation on tag reload), `LootCommand` (`/swiss admin loot preview <room> [n]`).
- `data/swissworld/loot_table/chests/<room>.json` for the 11 room types; each is one `auto_mix` entry.

## Decisions
- **Plan per building, rendered per chunk.** A plan depends only on the building's own BTBL row and its BSPN
  columns, never on the chunk being generated, so every chunk that touches a building computes the same plan
  (tested across a synthetic cell border). Plans are cached (LRU 256) since every stage of every chunk a building
  touches asks again; without the cache large blocks stalled chunk generation. The key is gid plus which cells
  under the margin-grown bbox corners exist, so a plan made without a neighbour is not reused once it exists
  (gid is global per swd-format §6).
- **Neighbour cells**: `GenContext` only loads the cells under the chunk's corners, so `CellView` falls back to the
  store for any other cell a building reaches (memoised per chunk). It reads the file on the calling thread: stages
  can run on CellCache's own IO threads, and joining `CellCache.get` there deadlocked (watchdog kill in the dev
  run). Like `CellCache.load` it treats an existing file as the cell; the manifest-entry readiness rule from docs/dev.md
  is not applied by mod-core's loader either. `Buildings.near` scans the cells under the margin-grown box, not just the chunk corners.
- **Storey height** from the BTBL height span and GASTW: `round(h / (GASTW + 0.7))`, clamped to 3..4 blocks;
  4 without GASTW. Halls (church, barn, industrial, greenhouse, tank, large wall, covered bridge) get one floor.
- **Reachability**: every floor above the ground floor must be reached by the stair core or a ladder. Ladder
  fallback: against a facade wall, else against an interior cell that becomes a support pillar (partition block),
  with an exit cell that has floor on every storey. If neither fits, the top floors are dropped until one does
  (the top storey gets taller; windows repeat per storey above the last floor). `BuildingPlan.reachable()` checks
  landing/exit floors; asserted in `BuildingPlanTest` and the real-cell sweep.
- **Special OBJEKTART shells**: 7 Lagertank = ellipse inscribed in the bbox, concrete ring wall, flat smooth-stone
  top, hollow, no windows; 8 Treibhaus = glass walls and flat glass roof on a stone-brick base, coarse-dirt floor,
  one hall with storage loot; 9 Mauer gross = solid stone/cobble/stone-brick; 10 Bruecke gedeckt = spruce deck and
  walls, open portals at both ends of the long axis, fence windows, foundation only where the deck is ≤ 3 blocks above
  ground. 11 Offenes Gebaeude, 12 Flugdach (main be45cfa) and any building whose spans are all part 1 (free-standing
  roof) = `CANOPY`: only the roof (top blocks/eave slabs, spruce or smooth stone) plus log posts at the footprint
  corners (skipped if longer than 17 blocks), no walls. 7, 9, 10, 11, 12 (`Style.empty()`) have no door, stairs,
  rooms, props or loot. Overhang-only detection waits until no column is unknown, so a roof whose body lies in a
  missing neighbour cell is not misread as a canopy.
- **Roof** from the column tops: a column whose top is above a lower neighbour of the same building gets a stair
  facing uphill; odd half-metre tops get a bottom slab; columns of part=1 (eave/overhang) become thin slabs.
  roofFlags bit0 marks the topmost span per building in a column; that span is the one rendered as roof.
- **Palettes in Java** (`Palette`): style (from OBJEKTART, GWR class/category, period, footprint, altitude) ×
  gid hash. Vanilla blocks only.
- **Door**: entranceSide if the sidecar sets it, else a gid-hashed side (the sidecar emits 0 for all real
  buildings so far); the door is placed on the first facade column found marching inward on lines near the centre.
- **Interior**: a straight stair core (ladder if no straight run fits), BSP rooms of at least 3×3 with at least two
  usable cells, partitions with a
  door gap, room types by style and floor. Every room gets a light (glowstone in the ceiling for low rooms, lantern
  hanging otherwise; the first light is placed before props so it cannot be crowded out) and a container (on any
  unused cell if no free wall cell is left); props never sit on the stair run/landing. No entities.
- **Loot**: containers carry `LootTable = swissworld:chests/<room>` and a seed from hash(gid, x, y, z). Lootr's
  chest/barrel are used when `lootr` is loaded (looked up by registry id, no compile dependency).
- **Auto-mixed loot**: each room table holds a single `swissworld:auto_mix` entry, so a datapack that replaces
  `swissworld:chests/<room>` simply wins (verified in-game). The entry rolls from a pool built lazily from all
  loaded `*:chests/*` tables (75 %: items whose source table name matches the room's keywords; plus item tags
  per room) with a 5 % off-room share. Denied: `#swissworld:loot_denylist` (datapack-extendable) plus code
  defaults (spawn eggs, `structure_*`, command/debug blocks). Pools are rebuilt when tags or reloadable
  registries change.

## Evidence (dev server, port 25651)
Fixture store `SWISSWORLD_STORE=/tmp/swissworld-mod-content` (fixture copied to `cells/2632_1170.swd`):
- Slice z=-297: calcite walls at x -800/-791 from y -1389, stone-brick base at -1390, oak floors at -1390 and -1386
  with air between, partition at x -796, chests at (-797,-1389,-297) and (-797,-1385,-297).
- Slice x=-795: brick stairs at the eaves (-1383) rising to full bricks at the ridge z -297/-296 (-1382).
- Lights: glowstone at (-798,-1386,-296) and (-793,-1386,-296), lantern at (-798,-1383,-296).
- Door: oak door lower/upper at (-796,-1389/-1388,-293), stone-brick landing and stairs down outside; oak stair core
  from (-799,-1389,-299) rising east.
- `data get block -797 -1389 -297` -> `LootTable: "swissworld:chests/residential_living", LootTableSeed: ...`;
  barrel at (-792,-1389,-299) -> `residential_kitchen`.
- `swiss admin loot preview residential_kitchen 3` -> 20 stacks, 16 distinct items (bread, potatoes, carrots,
  coal, iron ingots, stone axe, ...). Log: `Auto-mixed loot pools from 56 chest tables: [alpine_hut=254, ...]`.
- Datapack override: a world datapack replacing `swissworld:chests/church` with one cake -> preview shows only cake;
  other rooms still mixed.

Real Interlaken cell (terrain from the shared store, read-only; BTBL/BSPN built with main's `BuildingsBuilder`
from the swissBUILDINGS3D zips already in `$SWISSDATA_RAW` (all older than the run; no downloads), re-parsed into
the worktree's gitignored `.data-cache/scratch` because `SWISSDATA_SCRATCH` defaults under the repo) in a second private store `/tmp/swissworld-mod-content-real`:
959 buildings. Spot checks near the origin: a chalet (stone-brick base, spruce upper storey, dark-oak stair roof
with a ridge, glowstone, beds, chest with `swissworld:chests/workshop`, barrel with `residential_kitchen`); a
4-storey stepped block with 4 floors, rooms per floor and a ladder core. No exceptions in the log.
After the neighbour/cache changes: 147 chunks force-loaded (chalet, the 4-storey block, and the cell's west border
x -912..-880 where the neighbour cell is missing) in 16 s without errors; fixture acceptance probes unchanged.

Planner sweep `BuildingSweepTest` (committed; skipped unless `SWISSWORLD_SWEEP_CELL=/tmp/swissworld-mod-content-real/cells/2632_1170.swd`)
over all 959 real buildings: 0 failures, every multi-floor building reachable, every room with a light and a
container; 700 multi-floor, 10248 rooms, 545 ladder cores (27 on pillars). Styles: TOWN_HOUSE 405, INDUSTRIAL 140,
OLD_TOWN 133, MODERN 84, CHALET 80, BARN 55, COMMERCIAL 32, HOTEL 20, CHURCH 7, WALL 3 (real cell has OBJEKTART
1/3/4/9 only). Before the fix 43 multi-floor buildings had neither stairs nor ladder.
In game (real store): pillar ladder at (-371,-1434..-1419,-132), partition pillar at z -133, exit floors at z -131
on every storey.

Special types in game: private store `/tmp/swissworld-mod-content-special` = fixture plus four copies of its
building with OBJEKTART 7/8/9/10/11/12 at dz +20..+120 (bridge lifted 60 m; the 12 copy has only part-1 spans), written with sidecar `swd.py`
(read-only use). Slices: tank hollow with light-gray concrete ring, smooth-stone top, rounded corners
((-800,-1386,-280) air); greenhouse glass walls/roof, stone-brick base, coarse dirt, chest `swissworld:chests/storage`
at (-798,-1389,-254); wall solid cobblestone with stone-brick top; bridge spruce deck/walls, stair roof, open
portals at both ends, fence window row, no foundation; open building (11) and overhang-only canopy (12): roof slabs
at -1383/-1382 on spruce-log posts at the four corners, nothing below the roof in between. No exceptions.

## Known limitations / follow-ups
- **Cross-cell buildings**: neighbours are now read from the store (see Decisions). A neighbour cell built
  *after* some of the building's chunks were generated yields a different plan for the later chunks (a seam at
  the chunks already generated; the cache key prevents the missing half from persisting).
  Suggestion for mod-core, not required: preload neighbour cells in GenContext so the fallback rarely reads.
- The plan cache is static; switching stores in one JVM (integrated client) could reuse a
  stale plan. Fine on a dedicated server.
- Buildings whose upper floors cannot be reached lose those floors (taller top storey) rather than getting a
  bigger stair; tanks/greenhouses/covered bridges/canopies are absent from the real test cell, so only synthetic copies were
  seen in game.
- Lootr path compiled but untested (Lootr not in the dev run).
- Real data has no entranceSide yet; door side is hashed. Stair cores in large real buildings often fall back to
  ladders; a U-shaped stair would fit better.
- Windows are a per-style grid; no shutters or balconies yet. A tank uses its bbox ellipse, not the real outline.

## Contract proposals
None. No contract file was changed.

## Files outside owned paths
- `mod/src/main/resources/data/swissworld/tags/item/loot_denylist.json`: the datapack-extendable denylist tag for
  auto-mixed loot (the brief asks for a denylist; a tag lives under `tags/`, not `loot_table/`).
- `mod/src/test/java/ch/bissbert/swissworld/gen/building/BuildingPlanTest.java`, `BuildingSweepTest.java`: tests.

## Building fixes (doors, roofs, windows)
- Roof leaks: FurnishStage.partition overwrote roof-side fill with air; now bounded by Columns.cover(). Roof stairs only where |gradient| >= 2 (Columns.uphill), no slab atop stairs.
- Doors: Planner.findDoor tries levels 0..3 and needs a landing that escapes to open ground (BFS) and avoids other buildings' columns (CellView.foreign). Floor y0 is lifted toward surrounding terrain (max 3) so shop windows are not sunk below paving.
- Windows: panes only on straight wall runs (BuildingStage.straight), else solid wall.
- Test: BuildingSweepTest.watertight* flood-fills outside air; reports leaking, doorless, bad door, open panes, "hemmed" (neighbours block landing) and "thin". Env: SWISSWORLD_SWEEP_STORE, SWISSWORLD_SWEEP_LIST (cells), WT_NEAR=x,z (inspect), SWISSWORLD_PROBE_OUT/PER_STYLE/GIDS (probeTargets export for in-game probes).
- Residual: 16 hemmed + 2 thin of 3556 buildings in a 7-cell sweep (buildings nested inside bigger neighbours).

## Stepped roofs (playtest 19.11.32)
- Every roof stair gets a full roof block directly below it (BuildingStage.column, Columns.stairRoof); Columns.cover() drops by one there so rooms, partitions and lantern chains stop under that block.
- Columns.fillPits raises a single column lower than its four body neighbours; Columns.uphill returns null on the ridge (no higher within two columns either way), so the ridge is full blocks.
- Sweep checks (BuildingRender.gaps): "gap-under" (air directly below a roof stair/slab of a body column) and "roof-hole" (topmost block of a body column is not roof/wall material). Eaves outside the walls are exempt. WT_SLICE=z,xa,xb,ya,yb adds an XY slice to WT_NEAR output.
- Probe: probeTargets also writes T lines (roof stairs); tools in scratch probe.py check stairs plus non-air below.

## Building round 2: dark rooms, stairs, doors, planning cost

- **Root cause of dark 2-high ground floors**: a ground floor lifted above the terrain (`lift`) left a hollow cavity
  under floor 0; `BuildingStage.block` now fills `y < floorY(0)` with the palette foundation (non-bridges).
- **Lights**: `BuildingPlan.lightGaps(k)` runs a 2D light-spread model (standing 14, glow/hanging 15 minus drop,
  blocked by partitions, raised walls, pillars, props) and puts standing lanterns on every usable cell with level < 9.
  Door cell gets glowstone in the ceiling where the ceiling is low. Skipped for footprints > 6000 columns
  (the light grid covers them; the model would dominate plan time). Nearest-free search is bounded (4 blocks;
  only the first light scans the room): see `BuildingPerfTest`.
- **Ladders**: single-floor, non-empty buildings 8+ tall get a ladder (`findLadder`); `wanted` (floors wanted before
  stair failure) is a new trailing `BuildingPlan` component.
- **Sweep** (`BuildingSweepTest`/`BuildingRender`): counts dark rooms (block-light BFS), multi-floor without stairs,
  8+ tall without stairs/ladder (footprint at least 5x5), closed dark air pockets (tolerated: a handful of roof voids),
  slowest plan time (< 1000 ms asserted). Results: 6 origin cells (3511 buildings) 0 dark of 27020 rooms, 0 without
  door, 0 without core; next store 0 dark of 101 rooms.
