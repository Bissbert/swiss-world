# NOTES — mod-core (Milestone 1)

## Status

M1 is merged (main `bb9e79f`). M1.1 (snow, CNPY/TTOP, tick re-measurement) is on `mod-core` on top of it. `./gradlew build` passes with 48 tests.
Dev evidence comes from `mod/run` on ports 25640/25641, with private stores only:

- `/tmp/swissworld-mod-core` holds the fake sidecar's synthetic cells.
- `/tmp/swissworld-mod-core-real` holds 70 real cells around Interlaken. They were *copied* from the shared store (read-only) together with its coverage.bin. Nothing was written to the shared store.

| Acceptance | Result |
|---|---|
| Real terrain at Interlaken | Ran on real cells with the sidecar down. `/swiss` → `LV95 E 2632901 N 1170600 · 570 m a.s.l. (ground 569.2 m)`. The reference decoder (`swd.py`, bilinear TERR) gives 569.23 m. |
| `/swiss where` within ±1 m | 569.2 vs 569.23 m at the origin; 568.0 vs 568.02 m 1 km east. |
| 1 km east, no ocean placeholders | Spectator bot at 20 m/s: 2175 chunks, 0 aborted, 0 waiting; real cells 2632 → 2633. Synthetic store: 1 km east through prefetch-built cells, no resets. |
| No tick stalls > 50 ms | **Not met on this machine** (see "Tick times"). P50 ~1 ms, P95 28–53 ms, P99 35–176 ms during the real-cell flight at load 14–20 on 10 cores. |
| Outside coverage = ocean | Fake coverage says e ≥ 2640 is outside. At x 7300/7110: water at y −1807 (193 m), air at −1806, biome `swissworld:ocean`. The border column (e 2639) is land. |
| Sidecar stopped → no crash, no placeholder, recovers | Players and normal chunk loading: chunks just stay pending. A *synchronous* load (`forceload add` on a missing cell) used to hang the server until the watchdog killed it; StallGuard fixes this (see below). Re-tests: (a) 4 chunks: no crash; they were saved as `minecraft:biomes` with no blocks; after restart with the sidecar up they generated real terrain (stone at y −1700). (b) 256 chunks over missing cells: the command returned after 21 s with one abort; no crash; 576 chunks saved as biomes without blocks, none full. |
| Determinism | Three fresh worlds, `level-seed=4242`, `forceload add -100 -100 100 100` (196 forced chunks, 324 saved full). Runs 2 and 3, block by block: terrain, water and biomes are identical; 10 ore blocks in 6 chunks differ where ore blobs of neighbouring chunks overlap. That depends on decoration order, the same as vanilla. Runs 1 and 2 differed in 12 chunk hashes, not analysed block by block. |
| Snow line ~2,300 m | Creative bot at 2,336 m with rain for 120 s: 12,014 snow layers, the lowest at y 314 = **2,314 m**. Vanilla: temperature 0.45 freezes above y 320 (2,320 m), plus noise. |
| ms/chunk, memory | 3.3–11.7 ms/chunk (pipeline only; the high end is under load). Heap 1.3–2.8 GB of 16 GB with 2–4 cells cached; one decoded cell is ~10 MB. |
| Sky/fog at y −1400 | Not checkable headless. Untested. |

## Decisions

- **Cell readiness.** The mod treats "file exists in `cells/`" as ready, because the sidecar renames files into place atomically. The coordinator's rule for the sidecar is "file exists AND a manifest entry without `evicted`". The mod doesn't read manifest.json: an evicted cell is deleted, so its file is gone, and a half-written file can't exist. If eviction ever leaves the file in place, CellCache must check the manifest. That is one call in `tryLoad`.
- **The mod relies on "eviction deletes the file"** (coordinator: fine as is).
- **Missing cells never become ocean.** Only coverage.bin says OUTSIDE. The sidecar's `outside` is trusted only while there is no coverage.bin. A disagreement is logged and the cell stays pending. An unreadable file stays pending and is retried.
- **Shutdown.** Vanilla waits for every generating chunk, and a failed step is fatal. So at `ServerStopping` the pending cells are aborted: those chunks complete empty and are marked. On save, `world/AbortedChunks` rewrites them to status `minecraft:biomes` with no blocks, light or heightmaps, so they regenerate on the next start.
- **StallGuard (new).** Some vanilla code loads a chunk synchronously on the server thread, for example `forceload add`. With the sidecar down, that wait never ended and the watchdog killed the server.
  - Once a second, `world/StallGuard` checks whether the current tick has run for at least `stallAbortSeconds` (default 20, below max-tick-time 60) while the server thread is in `ServerChunkCache.getChunk` and chunks are pending.
  - If so, it calls `CellCache.abortWaiting()`, and every uncached cell requested until that tick ends is ABORTED at once. Without this, a 256-chunk forceload needed 16 aborts and 36 s, close to the watchdog. `ServerTickEvent.Post` calls `resumeWaiting()`, which ends it; unlike `abortPending()` at shutdown, it does not latch for good.
  - The affected chunks are handled as at shutdown: empty while loaded, downgraded on save, regenerated once reloaded. `SwissRuntime.clearAborted` drops the mark when a chunk generates for real.
  - Trade-off: a force-loaded chunk can sit empty (air) until it is unloaded and loaded again. The guard logs a warning that says so.
- **Origin.** The origin lives in `world/SwissOrigin`, a SavedData in the Switzerland dimension. It comes from the config on first use and is locked once the first chunk generates. `/swiss admin origin E N` works only before the lock; after it, the command points to `/swiss admin reset confirm`, which deletes the dimension at the next start. Tested: set → lock → refused → persisted across a restart.
- **Entering.** `/swiss` takes you to your last position in Switzerland, else to the origin's ground. It waits for the target chunk with a ticket, without blocking, and gives up after a timeout with a message. `/swiss` from inside Switzerland, or `/swiss back`, returns you to where you came from (`world/ReturnPos`).
- **Reset.** `/swiss admin reset confirm` writes a marker. The dimension folder is deleted at the next start, before the level loads, and the origin unlocks. Tested.
- **Prefetch.**
  - At startup: `startupPrefetchRadiusM` around the origin.
  - Per player, every `prefetchIntervalSeconds`: a corridor of speed × `lookaheadSeconds`, with the heading sent as a compass bearing `atan2(vE, vN)` (0 = north = −z), as the API doc says.
  - `/swiss admin prefetch [radius]` replies asynchronously, so its result shows in player chat but not in RCON output.
- **Biomes and features.**
  - The dimension uses `swissworld:swiss`. The plains entry on main was the placeholder.
  - `SwissBiomeSource` maps SURF/OCLS to 12 `swissworld:*` biomes (11 + ocean) and fills them in `fillFromNoise`, from the same cells, so biomes are never baked from a guess.
  - Following the coordinator, the biomes carry **no surface features**, only `underground_ores` (step 6). After a scan of 441 full chunks: 0 plants, 0 logs, 0 scheduled ticks; coal, iron, copper, gold, redstone, lapis and diamond present.
- **Worldgen snow (M1.1).** Following the coordinator, `minecraft:freeze_top_layer` (top_layer_modification, index 10) is back on alpine_meadow, rock, scree, glacier and subalpine_forest. Subalpine is included because real treeline columns reach past 2,320 m, where vanilla freezes at temperature 0.45. Colline, montane, urban, lake and river stay ore-only. Fake-sidecar run (182 forced chunks, valley plus a strip up the 3,300 m peak): snow layers from **2,314 m** up to 3,315 m, no `minecraft:ice` anywhere, and the valley lakes (water 164–624 m) are neither frozen nor covered in snow. Glacier (temperature −0.3) freezes at any altitude by design.
- **Ore placement.** Vanilla ore heights (y −64..320) mean nothing at y −2032..2032, so `placed_feature/ore_*.json` place ores relative to the surface: heightmap `OCEAN_FLOOR_WG`, then chained `random_offset` steps, because each step is limited to ±16. Depth bands go from 4 to 64 m below ground; emeralds only in rock, scree, alpine_meadow and glacier. Surface-relative ores belong to the ore step, not a new GenStage.
- **CELL_MARGIN = 8.** This mirrors mod-surface: `fillFromNoise` loads the cells for the chunk plus 8 columns around it, with the same constant and javadoc, so the merge is trivial.
- **`getBaseHeight`/`getBaseColumn`** never block. They use the cell if it is cached; otherwise they answer ocean.
- **`/generated`** is not called in M1. Nothing needs it yet.

## Tick times

The Switzerland chunks have 254 sections, against 24 in vanilla. A save probe found no oversized palettes; every section packs to under 3000 B. Packing one Swiss chunk takes ~1.3–2.5 ms, against ~0.15 ms for vanilla. That is a real extra cost per saved or unloaded chunk.

Most of the spikes, though, track machine load. Other workstreams' servers and Gradle daemons ran at load 85–178 on 10 cores. In a same-session comparison at 20 m/s:

| Run | P95 | P99 |
|---|---|---|
| Switzerland | 50–70 ms | 97–268 ms |
| Overworld | 15–42 ms | 44–102 ms (one 634 ms) |
| Real-cell flight at load 14–20 | 28–53 ms | 35–176 ms (P50 ~1 ms) |

M2 options:

- A mixin fast path in `PalettedContainer.pack` for single-value containers (most Swiss sections are all air or all stone). It changes global behaviour and needs a mixin config, so it was not done in M1.
- Fewer stored sections, by not saving all-air sections above the terrain.
- Measuring on an idle machine first.

## Tick times, M1.1 re-measurement (2026-10-19)

The same bot and server session, spectator, 1 km at 20 m/s, `tick query` every 5 s. Real store, no sidecar. The machine was not idle: the user's Prism client ran at ~136 % CPU, load 6–15 on 10 cores.

| Flight | P50 | P95 | P99 |
|---|---|---|---|
| Overworld east (load 10–15) | 2–6 ms | 14–50 ms (mostly 15–28) | 18–55 ms |
| Overworld south (load 7–9) | 1–4 ms | 15–24 ms | 31–54 ms |
| Switzerland east (load 8–10) | 0.7 ms | **47–54 ms** | 54–98 ms |
| Switzerland south (load 6–7) | 0.7 ms | 27–45 ms | 30–107 ms |
| Switzerland west + JFR (load ~7) | — | 29–51 ms | 30–509 ms (first window) |

P95 is still over 50 ms. The cause is the chunk *saving* on unload, not generation. A low P50 with a steady P95 means a few ticks of ~30–50 ms each second. JFR on the server thread during the third flight (476 samples):

- 76 % `ChunkMap.processUnloads` → `ChunkMap.save` → `ChunkSerializer.write`;
- 62 % `PalettedContainer.pack`, almost all of it in `swapPalette` and `unpack`;
- light 4 %, chunk sending 3 %, our own code under 1 % (generation runs off-thread).

Of 1,104,138 saved sections (4,347 full chunks, 254 per chunk), **97.7 % of block containers and 86.7 % of biome containers are single-value**. Vanilla's `pack` still unpacks 4096 ints into a new array, runs `swapPalette` over them and builds a HashMapPalette for each of them. That fixed cost, ~6 µs × 254 sections, explains the ~1.5 ms per chunk measured in M1, against ~0.15 ms for vanilla's 24 sections.

Proposed M2 fixes, in order (none implemented):

1. **A `PalettedContainer.pack` fast path** (mixin, ~20 lines): if `data.palette` is a `SingleValuePalette`, return `new DiscData<>(List.of(value), Optional.empty())` at once. The output is byte-identical to vanilla's, because vanilla also writes a 1-entry palette with no data. It applies to all dimensions, which is harmless since the result is the same. Expected: pack cost per Swiss chunk ~1.5 → ~0.1 ms, roughly a 10× cut of the hot path. Verification: hash the region files before and after (`hash.py`) and run the same flight.
2. **Skip uniform air sections above the terrain** on save, and recreate them as air on load (ChunkSerializer mixin). Smaller files and less NBT building (`Codec`/`NbtOps` is about another 10 %). It is riskier: it touches load, light and heightmaps. Do it only if 1 is not enough.
3. **Spread unloads**: cap Switzerland chunk saves per tick, e.g. via `ChunkMap` unload batching, so a burst of unloads after a fast flight is not saved in one tick. This helps the P99 spikes (the 509 ms window), not the average.
4. Not proposed: a lower dimension height, which the real altitudes rule out, and async serialization, which vanilla's chunk-state threading makes unsafe.

## Contract proposals

None. The real cells copied from the shared store (layers TERR/SURF/WLVL/WKND/OCLS/OELV/OFLG) read fine with the current SwdReader.

**CNPY/TTOP (done, at the coordinator's request):**

- `SwdReader` decodes the optional `CNPY` (u8) and `TTOP` (§6a) layers. The size is checked against the u32 count; unknown tags are still ignored.
- New `SwdCell` API:
  - `canopy(dx,dz)`: 0 when there is no canopy or no CNPY;
  - `hasCanopy()`;
  - `trees()`: an immutable `List<SwdCell.Tree(dx, dz, heightM, crownDm, kind)>` in file order (dz, dx), empty without TTOP;
  - `hasTrees()`: true if TTOP is present, even when empty, so mod-surface can tell "no trees here" from "no data";
  - `Tree.CONIFER/BROADLEAF/LARCH`.
- The 12-argument constructor stays, so existing callers and tests compile unchanged.
- Tests use a synthetic in-memory .swd (both codecs, plus an unknown tag) until sidecar-core extends the golden fixture.

## Files outside owned paths

- `mod/build.gradle`: `testImplementation` slf4j, so CellCache tests can log.
- `tools/fake_sidecar.py`: a dev-only fake of the control plane. It writes synthetic cells and a synthetic coverage.bin, and refuses to run on the shared store.
- `tools/bot.py`: a headless 1.21.1 offline client for smoke tests (commands, waits, straight-line flights).

## Dev pitfalls

- Run `op Bot` while the bot is online. An offline `op Bot` resolves the name at Mojang and ops a different UUID.
- `/swiss` toggles, so a bot already in Switzerland leaves when it sends it.
- The sidecar URL for dev is in `mod/run/defaultconfigs/swissworld-server.toml` (port 25642 = fake sidecar).
- Spectators don't make chunks tick, so weather and snow tests need creative or survival.

## M2 notes

- Sea and ocean fish spawns at y −1807.
- Sky/fog colours at real altitudes, which need a client check.
- The pack fast path and the other save fixes, see "Tick times, M1.1".
- Ores: ore veins (`ore_veins` noise) are not reproduced.
