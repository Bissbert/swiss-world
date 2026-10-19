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
  (tested across a synthetic cell border). Plans are recomputed per chunk (no cache yet; cheap for M1 sizes).
- **Storey height** from the BTBL height span and GASTW: `round(h / (GASTW + 0.7))`, clamped to 3..4 blocks;
  4 without GASTW. Halls (church, barn, industrial incl. greenhouse, plain = tank/large wall/covered bridge) get one floor.
- **Roof** from the column tops: a column whose top is above a lower neighbour of the same building gets a stair
  facing uphill; odd half-metre tops get a bottom slab; columns of part=1 (eave/overhang) become thin slabs.
  roofFlags bit0 marks the topmost span per building in a column; that span is the one rendered as roof.
- **Palettes in Java** (`Palette`): style (from OBJEKTART, GWR class/category, period, footprint, altitude) ×
  gid hash. Vanilla blocks only.
- **Door**: entranceSide if the sidecar sets it, else a gid-hashed side (the sidecar emits 0 for all real
  buildings so far); the door is placed on the first facade column found marching inward on lines near the centre.
- **Interior**: a straight stair core (ladder if no straight run fits), BSP rooms of at least 3×3, partitions with a
  door gap, room types by style and floor. Every room gets a light (glowstone in the ceiling for low rooms, lantern
  hanging otherwise) and a container; props never sit on the stair run/landing. No entities.
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
from already-cached sheets, no downloads) in a second private store `/tmp/swissworld-mod-content-real`:
959 buildings. Spot checks near the origin: a chalet (stone-brick base, spruce upper storey, dark-oak stair roof
with a ridge, glowstone, beds, chest with `swissworld:chests/workshop`, barrel with `residential_kitchen`); a
4-storey stepped block with 4 floors, rooms per floor and a ladder core. No exceptions in the log.

## Known limitations / follow-ups
- **Cross-cell buildings**: `CellView` only sees the cells `GenContext` loaded for the chunk. A building whose bbox
  crosses into a cell that was not loaded for that chunk plans with those columns unknown (treated as outside
  facade-wise but not drawn). Proposal for mod-core: let GenContext also load the neighbour cells a building bbox
  reaches (or pad the chunk lookup by ~64 blocks). No contract file change needed.
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
