# Acceptance sweep M1–M3

These are the formal acceptance checks for M1–M3 from PLAN.md §11, measured headless.

## Setup

| Item | Value |
|---|---|
| Commit | `9439758` (main), branch `acceptance` |
| Seed | 4242, fresh world `mod/run/acc` |
| Server | dev `runServer`, 8 GB heap, port 25710, rcon 25711 |
| Sidecar | port 8097 with a private copy of `store-next` |
| Machine load | 25–100 (other agents' builds and servers were running), so all timings are pessimistic |
| Dates | 2026-10-18/19 |

- **Evidence:** raw outputs are in `docs/acceptance-m1-m3/`.
- **Rerunning:** the scripts are in `tools/acceptance/`; see "Rerunning" at the end.

**Status values:**
- PASS: measured and meets the criterion.
- FAIL: measured and does not meet it.
- PARTIAL: some of it measured, or met only with a caveat.
- NEEDS-USER: needs a GPU client, two accounts or human judgement; recipes below.

## Summary

| | PASS | FAIL | PARTIAL | NEEDS-USER |
|---|---|---|---|---|
| M1 | 7 | 0 | 1 | 3 |
| M2 | 6 | 2 | 0 | 1 |
| M3 | 2 | 2 | 3 | 2 (+ KubeJS in 3.5c) |

There are four failures for other agents:
1. Brünig rail continuity (transport).
2. No fish in lakes, because of the `getSeaLevel()` value (mod-core).
3. Dark ground floors let hostiles spawn indoors (buildings).
4. 3 buildings without a stair core or door (buildings).

Sidecar-down handling is PARTIAL: it does not crash, but it stalls the server thread for 20 s and leaves placeholders (mod-core).

## M1: terrain, async generation, origin, sky

| # | Criterion | Method | Measured | Status | Evidence |
|---|---|---|---|---|---|
| 1.1 | Survey points within ±1: Harder Kulm, Interlaken Ost, Grimsel Pass | `terrain.py points`: the world top at swissNAMES3D points vs the swissALTI3D/TLM oracle | Harder Kulm Haupthügel 0 (y −679); Interlaken Ost +1/0; Grimselpass Pass −1; Strassenpass 0 (y 164); Höhematte 0; Brienz station 0; Meiringen −1 | PASS | see note 1 |
| 1.2 | No seams between cells | `terrain.py survey seam`: ±1 share and the step distribution across cell edges 2632/2633 | 99.8 % within ±1; no step peak at the cell edges | PASS | — |
| 1.3 | Pack client without `swissworld` joins | — | — | NEEDS-USER | recipe R2 |
| 1.4 | Dimension appears in a copy of the existing world | — | — | NEEDS-USER | recipe R3 |
| 1.5 | Determinism (two fresh worlds) | World A generated forward, world B fresh with the same seed and the same areas in reverse order; `det.py compare/detail` hashes every section | 1,667/1,667 chunks and 423,418 sections. 709 sections in 614 chunks differ, all underground (y −1472..−1441), and every difference is an ore↔ore swap (iron/coal/copper/…) where vanilla ore veins of neighbouring chunks overlap. Terrain, water, buildings, roads and rails are identical. | PASS (see note 2) | `determinism.txt` |
| 1.6 | Sidecar stopped: no crash and no placeholder; saved areas, logins and respawns work; new areas wait and recover | `robust.py`: stop the sidecar; bot login, tp, kill and respawn; load a stored chunk; load a chunk whose cell file was removed from the store; restart the sidecar | (a) login, tp, kill and respawn OK. (b) A stored-cell chunk loads offline in 1–3 s. (c) A missing cell **stalls the server thread for 20.1 s** (StallGuard "gave up 1 pending cell(s) … empty placeholders", "Can't keep up! 20108 ms") and leaves **25 placeholder chunks**. After the restart, "Sidecar reachable again" is logged, then 9× "Filled in placeholder" and 0 "Could not fill"; the chunk loads in 40 s. No crash. | PARTIAL | `robust3.txt` |
| 1.7 | MSPT < 50 at elytra speed with prefetch | `flight.py fly`: a spectator bot flies 30 m/s in a straight line over fresh terrain; `/tick query` every 2 s | Thunersee terrain, 4.9 km in 163 s: avg MSPT mean 6.9, max 19.3, P99 max 335 ms, 9,601 chunks generated, 0 waiting at the end, 0 stall, 0 placeholder. Interlaken town, 4 km: mean 4.7, max 9.3. | PASS | `flight-*.txt` |
| 1.8 | No pending chunks within render distance (terrain only) | Same flights: `swiss admin status` "waiting" | 0 pending at the end of each flight and 0 aborted. Render-distance visual check: see R1. | PASS | `flight-*.txt` |
| 1.9 | World growth MB/km² (fill in §2) | `size.py` on the region files of each area | Town 19, mountain/forest 27–30, lake 16, dams 16, road corridor 17, rail corridor 19 MB/km², i.e. 4–7.5 KB/chunk. Whole world A: 504.7 MB in 231 .mca, ~61,000 chunks. PLAN §2 updated. | PASS (measured) | `generation-speed.txt` |
| 1.10 | Sky/fog at Interlaken and Grimsel, with and without Iris | — | — | NEEDS-USER | recipe R1 |
| 1.11 | Snow starts around the predicted altitude | `terrain.py snow`: lowest snow-layer/snow-block column in the harder/grimsel areas | Lowest snow at 2,358 m; predicted ~2,300 m | PASS | — |

Notes:
1. **Survey points.** Outliers that are not survey criteria:
   - Harder Kulm Aussichtspunkt −16: the point is on the viewing platform, a building, not terrain.
   - Aareschlucht Graben +7 at (26390, −3580): a 2–3 m wide gorge that the 1 m column cannot resolve.
   - Totensee Staumauer −7 at (36516, 13636): the dam crest versus the terrain DEM.
   - Harder Kulm station was not generated.

   Area surveys within ±1:

   | Area | Within ±1 |
   |---|---|
   | interlaken | 99.9 % |
   | harder | 98.3 % |
   | seam | 99.8 % |
   | rail corridor | 98.7 % (39,098/39,631) vs TERR, 97.7 % vs ALTI3D |

2. **Determinism.** Read strictly ("identical"), the ore swaps are a deviation. They come from vanilla ore features whose veins cross chunk borders, and the placement order follows the chunk load order. They are cosmetic, so this is not reported as a failure.

## M2: water, land cover, transport, biomes and spawns

| # | Criterion | Method | Measured | Status | Evidence |
|---|---|---|---|---|---|
| 2.1 | Brienzersee depth from bathymetry | `water.py lake`: deepest water column vs the oracle | Deepest 257 m vs oracle 256.3 m | PASS | — |
| 2.2 | Aare stepped and stable: TPS 20 after loading and breaking banks | Area `aare` (1,173 chunks, 93,006 water blocks): tick 60 s, then 20 bank breaches (air holes next to river pixels), tick 120 s; diff water per section | Without breaches: 0 changed sections, MSPT 2.7. After 20 breaches: 19 chunks changed, net +67 water blocks (only the holes filled), MSPT 1.4, TPS 20.0. Meiringen-to-Interlaken as a whole was not loaded: the area is the Interlaken stretch plus the rail corridor. | PASS | `aare-banks.txt` |
| 2.3 | Aareschlucht walkable | `ways.py check`: BFS walker (2 high, step ≤ 1, drop ≤ 3) along the TLM footpath | North part: start (26388, −1384, −3699), path index 574/575 reached. South part: 703 px, goal reached. | PASS | `ways-aareschlucht*.txt` |
| 2.4 | Giessbach and Handegg falls render as waterfalls | `water.py falls`: flowing-water columns along the fall line | Giessbach: water in 328/361 columns, 311-block drop. Handegg: 93/95. | PASS (headless) | — |
| 2.5 | Grimsel dams hold their reservoirs | `dams.py`: every 2nd TLM dam pixel; solid at the crest ±2; water within 4 blocks on the reservoir side; no leaking flow | 6,160 pixels sampled: 6,157 solid, 6,149 at the crest ±2. 2 holes: air at (35406, −88, 12335) and stone_bricks too low at (35387, −78, 12226) (crest 1,911.6 m). The reservoirs stay full after ticking. | PASS (2 minor holes, see F6) | `dams-check.txt` |
| 2.6 | Grimsel road walkable, tunnels passable | `ways.py check road`: walker from Innertkirchen to the Grimsel Pass along the OCLS class-3 road | Path index 10,439/10,439 reached; 862 tunnel columns, all reached | PASS | `ways-road.txt` |
| 2.7 | Brünig line continuous rails, minecart on a straight section | `rails.py check` (follows rail shapes like a minecart) and `rails.py segments` (whole-line coverage) | From Interlaken Ost (23, −1433, −561) only 345 rails are connected, with a dead end at (277, −1433, −605). Rail within 2 blocks along 92.9 % of the line, but in **231 separate pieces with 35 gaps**. Minecart ride not run, because the line is not continuous. | **FAIL** | `rails-check.txt`, `rails-segments.txt`; see F1 |
| 2.8 | Night census: no slimes/bats indoors, fish in lakes | `flight.py census` at (0, −1431, −100) for 3 min at night; `flight.py fauna` in town and at the lake (5345, −1434, −3563) | No slimes and no bats anywhere (town, 120 s). **No fish at all** in the Brienzersee after 150 s. | **FAIL** (fish) | `census.txt`, `fauna-*.txt`; see F2 |
| 2.9 | Tough as Nails OK | — | — | NEEDS-USER | recipe R5 |

## M3: buildings, furnishing, loot

| # | Criterion | Method | Measured | Status | Evidence |
|---|---|---|---|---|---|
| 3.1 | Building count within 1 %; eaves and ridges ±1 | `buildings.py` on world A: every BTBL building fully inside the generated area; roof surface vs source topH | Interlaken 139/139 present (4 eave-only skipped); seam area 29/29. Roof columns within ±1: 99.1 % (21,053/21,239). Eaves 5,867/5,921 (99.1 %). Store count vs swissBUILDINGS3D was not compared (only presence in the generated area). | PARTIAL | `buildings-A-interlaken.txt` |
| 3.2 | Recognisable: Höheweg hotels, Brienz chalets, Grimsel Hospiz, KWO buildings | — | — | NEEDS-USER | recipe R6 |
| 3.3 | Every building has a door and a stair core | `buildings.py`, plus `BuildingSweepTest.watertightStore` over 4 town cells | World: Interlaken 139/139 with a door, 106/108 multi-storey with a stair core; seam area 28/29 with a door, 25/26 with a stair core. Sweep test (2,291 buildings in cells 2632_1170, 2633_1170, 2644_1178, 2657_1175): 0 leaking, 0 without a door, 0 bad doors, 5 hemmed/thin (within the test's tolerance), test green. | **FAIL** (3 buildings) | see F4; `sweep-summary.txt` |
| 3.4 | Every room has block light ≥ 1; no hostile spawns inside furnished houses at night | `buildings.py` room flood fill with a light check; `flight.py census` | **103/324** rooms dark in Interlaken and **20/72** in the seam area. Census: up to **73 hostiles within 128 blocks, 66 inside building volumes** (zombie, skeleton, creeper, spider, witch, zombie_villager). | **FAIL** | `census.txt`; see F3 |
| 3.5a | Chests have loot tables | `buildings.py` container scan | 1,158 containers (959 chests, 199 barrels) in Interlaken; all have a `swissworld:chests/*` LootTable | PASS | `buildings-A-interlaken.txt` |
| 3.5b | Two players get their own Lootr roll | — | — | NEEDS-USER | recipe R4 |
| 3.5c | Loot tables overridable from KubeJS | Datapack override `tools/acceptance/datapack-loot-override` (replaces `swissworld:chests/church`), `/reload`, `swiss admin loot preview church 3` | With the pack: "3x minecraft:dragon_egg". After removing it and reloading: normal loot (books, paper, …). This proves the table is resolved by id through the data-pack layer, which KubeJS also writes to. KubeJS itself is not tested. | PARTIAL (mechanism PASS; KubeJS NEEDS-USER, R7) | — |
| 3.6 | Buildings and furniture across chunk borders intact regardless of load order | Same as 1.5 (forward vs reverse generation order) | 0 differing blocks outside ore swaps, including every building and its furniture in Interlaken, Harder and the seam area | PASS | `determinism.txt` |
| 3.7 | Generation speed within 50 % of M1 | Generator log: fill() ms/chunk | M3 (with buildings): 4.8–22.5 ms/chunk at load 25–100; reverse run 12.4 at load 25; flights ~6.5. M1 baseline (NOTES): 3.3–11.7 ms. Under comparable load (~25), 12.4 vs ~8 is within +55 %. The load was not controlled. | PARTIAL | `generation-speed.txt` |

## Failures (for the owning agents; nothing here was fixed)

**F1: Brünig rail is not continuous (transport).** `rails.py segments` along the 25,337 px oracle path: 231 separate rail pieces and 35 gaps. Some gaps may be rail tunnels deeper than 8 blocks below the surface: `tunnels.json` only lists road tunnels, so the check cannot tell. Longest gaps:

| Path index | Length | LV95 from | LV95 to | Note |
|---|---|---|---|---|
| 13062..13683 | 621 px | E 2644936 N 1178640 | E 2645572 N 1178320 | Brienz. Includes a 305 m stretch with no rail pixels in the source data, E 2645294 N 1178466 → E 2645564 N 1178326, so partly an oracle/data gap. |
| 10707..11019 | 312 px | E 2642576 N 1178342 | E 2642888 N 1178424 | |
| 1527..1806 | 279 px | E 2634392 N 1171716 | E 2634678 N 1171882 | |
| 7155..7305 | 150 px | E 2639518 N 1175958 | E 2639578 N 1176110 | |
| 9522..9624 | 102 px | E 2641414 N 1177664 | E 2641518 N 1177700 | |

- **First break:** the minecart walk from Interlaken Ost (23, −1433, −561) dead-ends at (277, −1433, −605). That is a `north_south` rail with no connecting rail at z −606; parallel rows at z −604, −606 and −613 are not joined (E 2633178 N 1171204).
- **Guess:** each chunk places the rail segments of its own pixels without joining shapes across chunk borders or parallel tracks, so the double track near the station turns into unconnected rows. Gaps also appear where the line runs in a cut or tunnel.

**F2: No fish in lakes (mod-core).**
- `fauna` at the Brienzersee (5345, −1434, −3563) after 150 s at night found no cod, salmon or squid. On land there are chickens and rabbits.
- **Guess:** `SwissChunkGenerator.getSeaLevel()` returns −1807 (193 m). Vanilla `WaterAnimal.checkSurfaceWaterAnimalSpawnRules` requires sea − 13 ≤ y ≤ sea, so lakes at y ≈ −1436 never qualify.
- NOTES-mod-core says "fish spawns at y −1807"; PLAN §4 suggested the lowest lake level as sea level. A custom spawn rule or per-lake check is needed.

**F3: Dark rooms and hostiles indoors (buildings; handed to the buildings agent).**
- 103/324 rooms are dark in Interlaken and 20/72 in the seam area.
- Example: (12, −1436, −296), where ground floors are only 2 blocks high, with no lamp or window.
- Census examples of zombies standing on oak_planks under a planks ceiling with 2 air blocks above: (30, −1434, −118), (15, −1433, −64) and (41, −1435, −71).

**F4: Missing stair core or door (buildings; handed to the buildings agent).**

| gid | Position | Problem |
|---|---|---|
| 12982887384485750296 | (90, −1437, −520) | no door and no stair core (seam area) |
| 12176293745493962998 | (−343, −1434, 127) | no stair core (multi-storey) |
| 13101631549772798174 | (−457, −1435, 15) | no stair core (multi-storey) |

The sweep test also lists 5 doors hemmed in by neighbours or thin shells. The test tolerates these:
- 2632_1170: −8420630179866543726 MODERN and −5737306888120133237 INDUSTRIAL;
- 2657_1175: see `watertight-2657_1175.txt`.

**F5: Sidecar-down stall and placeholders (mod-core).**
- A chunk whose cell is not in the store stalls the server thread for 20 s ("Server thread waited 20.1 s"), then StallGuard writes 25 empty placeholders. The placeholders are filled in later.
- The spec asks for "new areas wait" without a stall or placeholder.
- **Guess:** a synchronous `getChunk` (forceload or player tick) joins the cell future on the server thread until StallGuard's timeout. An offline sidecar should fail fast, or keep the chunk pending, not block.

**F6 (minor): Two dam holes (transport/water).**
- Air at (35406, −88, 12335).
- Crest too low at (35387, −78, 12226), crest 1,911.6 m.
- No visible leak after ticking.

**Data notes (not generator bugs):**
- The Aareschlucht footpath has a gap in the TLM at E 2659472 N 1173745.
- The Grimsel road is OCLS class 3.
- The rail gap at Brienz is above.

## NEEDS-USER: manual test recipes

These need a real client with a GPU, two accounts or a person reading the screen.

**Pack versions** (from `~/minecraft-modpack/pack/mods/*.pw.toml`):

| Mod | Version |
|---|---|
| Iris | 1.8.14-beta.1 |
| Sodium | 0.8.13 |
| Lootr | 1.11.38.127 |
| Tough As Nails | 10.1.0.13 |
| KubeJS | 2101.7.2 |
| The Hordes | 1.6.3f |

Shader packs: Complementary Reimagined, MakeUp Ultra Fast and Spooklementary.

**Server setup:**
1. Use a dev server built from this commit: `tools/acceptance/server.sh setup && tools/acceptance/server.sh start`. The ports come from `tools/acceptance/env.sh`.
2. Copy the pack's server-side mods into `mod/run/mods`. Client-only mods like Iris and Sodium are not needed there.
3. Connect a pack client to `localhost:<ACC_SERVER_PORT>`.

### R1: Sky and fog, with and without Iris (M1 1.10)
1. Join with the full pack and shaders off: Video Settings → Shader Packs → off.
2. Go to Interlaken: `/execute in swissworld:switzerland run tp @s 0 -1430 0`.
3. Take screenshots looking north, east and up at each of these times:
   - noon: `/time set 6000`;
   - sunset: `/time set 12500`;
   - night: `/time set 18000`.
4. Go to Grimselpass, block x 36037, z 13512, y ≈ 165 (2,164 m), and repeat step 3.
5. Turn on Complementary Reimagined and repeat steps 2–4.
6. PASS if:
   - the sky has no dark band or void colour at the horizon;
   - the fog does not start at the old y = 63 sea level;
   - the sun and stars sit normally;
   - clouds are not drawn under the player at Grimsel;
   - with render distance 12+, no chunks are still missing after ~10 s (M1 1.8).

   Write down whether §10a (custom sky offset) is needed.

### R2: Pack client without `swissworld` joins (M1 1.3)
1. Make a Prism instance from `the modpack .mrpack` without adding the swissworld jar.
2. Join the server, which has swissworld, and get sent into the dimension: `/execute in swissworld:switzerland run tp <name> 0 -1430 0`.
3. PASS if:
   - you are not kicked for missing registry entries;
   - the terrain shows, and no blocks or biomes show as missing;
   - the sky and fog look sane;
   - the client `logs/latest.log` has no `Missing registry` or `Mismatched` errors.

### R3: Dimension in a copy of the existing world (M1 1.4)
1. Copy the live pack world, read-only from its source, into `mod/run/<copy>`.
2. Set `level-name=<copy>` and start the server.
3. PASS if:
   - `/swiss tp interlaken` works without resetting the world;
   - the overworld is unchanged: check a known build and the inventories.

### R4: Lootr per-player rolls (M3 3.5b)
1. Put the pack's `lootr-neoforge-1.21.1-1.11.38.127.jar` into `mod/run/mods` and start the server.
2. Go to a fresh, never-generated area with houses, e.g. Brienz (`/swiss tp` there).
3. Two players (two offline-mode clients, e.g. `Alice` and `Bob`) open the same chest in a furnished house. Its table is `swissworld:chests/residential_*`, so Lootr should turn it into a Lootr chest.
4. PASS if:
   - each player sees a different inventory;
   - taking items as Alice does not change Bob's view.
5. Check that `/swiss admin loot preview residential_living 5` still works with Lootr loaded.

### R5: Tough As Nails (M2 2.9)
1. Put `ToughAsNails-neoforge-1.21.1-10.1.0.13.jar` on both the server and the client.
2. Play a few minutes of survival in each place:
   - Interlaken at y −1432 (568 m);
   - Grimsel Pass (2,164 m);
   - swimming in the Brienzersee;
   - on the snow above 2,300 m.
3. PASS if:
   - the temperature reads plausibly: neutral in Interlaken, cold at Grimsel and on the snow, without instant freezing damage;
   - drinking from the lake or the Aare gives water, and purified water works;
   - there is no crash and no `swissworld` biome warning in the log.
4. Watch out: TaN may use vanilla height-based temperature on the raw y. With y = m − 2000, Interlaken at y −1432 may read as deep underground or very warm. Check the HUD.

### R6: Recognisable buildings (M3 3.2)
1. Fly over these places in spectator mode and compare with photos or the swisstopo 3D map:
   - Interlaken Höheweg: Victoria-Jungfrau hotel row along the north side of the Höhematte (start at x 0, z −100);
   - Brienz chalets along the lake front;
   - Grimsel Hospiz, on the rock above the Grimselsee dam (Spitallamm);
   - the KWO buildings at Handegg and Innertkirchen.
2. PASS if each is identifiable by footprint, roof shape and style.

### R7: KubeJS loot override (M3 3.5c)
1. With KubeJS 2101.7.2 installed, add a server script:
   ```js
   ServerEvents.generateData('after_mods', e => e.json('swissworld:loot_table/chests/church', {type:'minecraft:chest', pools:[{rolls:3, entries:[{type:'minecraft:item', name:'minecraft:dragon_egg'}]}]}))
   ```
   Or use LootJS, if it is in the pack: `LootJS.modifiers(e => e.addTableModifier('swissworld:chests/church').addLoot('minecraft:diamond'))`.
2. Run `/reload`.
3. PASS if `/swiss admin loot preview church 3` shows the overridden loot. The plain datapack version of this already passed headless.

### The Hordes (note)
The pack's `kubejs/server_scripts/waves.js` skips non-overworld dimensions, so there are no hordes in `swissworld:switzerland`. If hordes should come to Switzerland, that is a pack decision.

## Rerunning

All scripts are in `tools/acceptance/`; the docstring at the top of each script has the details.

**Setup:**
1. `export ACC_STORE=<private store copy>`. Never point it at `store` or `store-next`.
2. Run `. tools/acceptance/env.sh`.
3. Run `tools/acceptance/server.sh setup|start|stop|sidecar-start|sidecar-stop`.

**Generate the areas:** `gen.py AREA… [--reverse]` (areas are listed in `areas.py`).

**Checks:**

| Script | Checks |
|---|---|
| `terrain.py` | survey points, seams, snow |
| `water.py` | lake, falls, Aare tick/banks/diff |
| `dams.py` | dams |
| `ways.py` | road, Aareschlucht walker |
| `rails.py plan/check/segments` | rail continuity |
| `buildings.py` | buildings and containers |
| `flight.py fly/census/fauna` | flights, night census, fauna |
| `robust.py` | sidecar-down robustness |
| `det.py hash/compare/detail` | determinism |
| `size.py` | world growth |
| `validate_reader.py` | the region reader itself |

**BuildingSweepTest:** run it one cell at a time. With 7 cells, the store memo runs the test JVM out of heap (OutOfMemoryError in `SwdReader`). Command, from `mod/`:
```
SWISSWORLD_SWEEP_STORE=$ACC_STORE SWISSWORLD_SWEEP_LIST=2632_1170 WATERTIGHT_OUT=… ./gradlew --no-daemon test --rerun --tests '*BuildingSweepTest.watertightStore'
```
