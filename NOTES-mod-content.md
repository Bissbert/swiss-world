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
  4 without GASTW. Halls (church, barn, industrial incl. greenhouse, plain = tank/large wall/covered bridge) get one floor.
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

Planner sweep (ad-hoc test, not committed; re-run on the final code, 0.8 s) over all 959 real buildings: 959 planned,
0 failures, every room with a light and a container; 10354 rooms,
41351 eave columns in 926 buildings (part=1 is common), 5200 columns with several spans, 247 single-floor,
514 with a ladder core, 43 multi-floor buildings with neither stairs nor ladder. Styles: TOWN_HOUSE 405,
INDUSTRIAL 140, OLD_TOWN 133, MODERN 84, CHALET 80, BARN 55, COMMERCIAL 32, HOTEL 20, CHURCH 7, PLAIN 3.

## Known limitations / follow-ups
- **Cross-cell buildings**: neighbours are now read from the store (see Decisions). A neighbour cell built
  *after* some of the building's chunks were generated yields a different plan for the later chunks (a seam at
  the chunks already generated; the cache key prevents the missing half from persisting).
  Suggestion for mod-core, not required: preload neighbour cells in GenContext so the fallback rarely reads.
- The plan cache is static; switching stores in one JVM (integrated client) could reuse a
  stale plan. Fine on a dedicated server.
- 43 of 959 real multi-floor buildings get neither stairs nor ladder (no free 1-wide run); their upper floors are
  unreachable.
- Lootr path compiled but untested (Lootr not in the dev run).
- Real data has no entranceSide yet; door side is hashed. Stair cores in large real buildings often fall back to
  ladders; a U-shaped stair would fit better.
- Windows are a per-style grid; no shutters or balconies yet. Special OBJEKTART (7 tank, 9 wall, 10 covered bridge)
  are windowless one-floor PLAIN halls; a wall or bridge deserves its own shape.

## Contract proposals
None. No contract file was changed.

## Files outside owned paths
- `mod/src/main/resources/data/swissworld/tags/item/loot_denylist.json`: the datapack-extendable denylist tag for
  auto-mixed loot (the brief asks for a denylist; a tag lives under `tags/`, not `loot_table/`).
- `mod/src/test/java/ch/bissbert/swissworld/gen/building/BuildingPlanTest.java`: tests for the planner.
