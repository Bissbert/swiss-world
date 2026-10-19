# NOTES — mod-core (Milestone 1)

## Status

M1 is merged (main `bb9e79f`). M1.1 (snow, CNPY/TTOP, tick re-measurement) is on `mod-core` on top of it. `./gradlew build` passes with 53 tests (M2 fix 1 and the sky fix below).
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
| Sky/fog at y −1400 | Not checkable headless. The black horizon below y 63 is fixed by sending isFlat (see "Client sky"); needs a visual check. |

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
- **Entering.** `/swiss` takes you to your last position in Switzerland, else to the origin's ground. It waits for the target chunk with a ticket, without blocking, and gives up after 60 s without progress (see "Arrival" below). `/swiss` from inside Switzerland, or `/swiss back`, returns you to where you came from (`world/ReturnPos`). `/swiss goto <a> <b>` takes real coordinates (LV95, LV03 or WGS84 lat lon, told apart by magnitude) and uses the same wait-for-chunk path; outside Switzerland's LV95 box it refuses.
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
- `mod/build.gradle`: Minecraft on the unit-test classpath (`addModdingDependenciesTo(sourceSets.test)`) for the vanilla-pack comparison; the `swissworld.vanillaPack` run property.
- `mod/src/main/templates/META-INF/neoforge.mods.toml`: the `[[mixins]]` entry. `META-INF/accesstransformer.cfg`: `PalettedContainer.data` and `PalettedContainer$Data` public.
- `tools/bot.py`: a headless 1.21.1 offline client for smoke tests (commands, waits, straight-line flights; prints the isFlat flag of login/respawn packets).

## Dev pitfalls

- Run `op Bot` while the bot is online. An offline `op Bot` resolves the name at Mojang and ops a different UUID.
- `/swiss` toggles, so a bot already in Switzerland leaves when it sends it.
- The sidecar URL for dev is in `mod/run/defaultconfigs/swissworld-server.toml` (port 25642 = fake sidecar).
- Spectators don't make chunks tick, so weather and snow tests need creative or survival.

## M2 fix 1: single-value pack fast path (2026-10-19)

- `world/SectionPacking` + `mixin/PalettedContainerMixin` (HEAD of `PalettedContainer.pack`): a container whose palette is a `SingleValuePalette` is packed as `PackedData(List.of(value), Optional.empty())` without the vanilla HashMapPalette rebuild and the 4096-entry scan. A refilled container (single value but a larger palette) and mixed containers stay on vanilla code, so the output does not depend on the shortcut being taken.
- Byte identity: `SectionPackingTest` compares palette, storage and the codec's JsonOps output with vanilla `pack` (the test classpath has MC but no mixins). In game, two fresh worlds (`level-seed=4242`, same forceload + bot route; `SWISSWORLD_VANILLA_PACK=true` vs default) gave 3045 full chunks each. 2894 hash-identical; in the other 151 every differing section has different *blocks* (ore swaps, the known decoration-order noise); **0 sections with the same content but different encoding**, and every biome container identical.
- Server tick, same route (ow1 east 1 km, sw1 east 1 km, ow2 south, sw2 south at 20 m/s), loadavg 15–25 from other workstreams. P95 = median of 5 s windows; max over the leg:

| leg | before P95 med / max | after P95 med / max | before P99 med | after P99 med |
|---|---|---|---|---|
| sw1 | 27.7 / 42.7 ms | 9.5 / 15.0 ms | 50.8 ms | 24.2 ms |
| sw2 | 25.1 / 49.9 ms | 10.9 / 12.3 ms | 73.8 ms | 20.9 ms |
| ow1 | 23.4 / 141.2 ms | 27.9 / 52.6 ms | 70.1 ms | 56.8 ms |
| ow2 | 10.2 / 39.6 ms | 12.1 / 39.4 ms | 26.8 ms | 27.6 ms |

  Switzerland's P95 falls ~2.5×; the overworld is unchanged within noise (its sections are rarely single-value).
- Spreading chunk unloads: **not done, not needed.** `ChunkMap.processUnloads` already only saves while the tick has time left (`hasMoreTime`), forcing more only above a 2000-chunk backlog, plus at most 20 `saveChunkIfNeeded` per tick in spare time. The old ~50 ms P95 was spare-time saving filling the budget, not a stall; with the fast path that saving is cheap.
- `-Dswissworld.vanillaPack=true` (run config: `SWISSWORLD_VANILLA_PACK=true`) turns the fast path off for A/B checks.

## Client sky below y 63: Switzerland is sent as "flat" (2026-10-19)

- Bug (playtest screenshots 2026-10-19_16.53.43 / 16.54.38): a vanilla client draws the lower sky hemisphere black when the eye is below `ClientLevelData.getHorizonHeight()` = `isFlat ? minBuildHeight : 63`, and darkens fog while `(y − minBuildHeight) · getClearColorScale() < 1`, scale = `isFlat ? 1 : 1/32`. Our terrain is at y −2000..+1000, so the horizon was black.
- Fix, server-only: `mixin/ServerPlayerMixin` (RETURN of `ServerPlayer.createCommonSpawnInfo`) → `world/FlatHorizon.forClient` sets `isFlat=true` in the `CommonPlayerSpawnInfo` for `swissworld:switzerland` only. That record is what login, respawn and dimension change send (PlayerList:201, PlayerList:490, ServerPlayer:907). With it, the client horizon is y −2032 (never above the eye), and the fog factor is ≥ 1 above y −2031.
- Unchanged: `ServerLevel.isFlat()` (its only server-side reader is `createCommonSpawnInfo`; `WorldData.isFlatWorld` and the level.dat flag stay vanilla). Client-side, `isFlat` is read only by `ClientLevelData` for these two values. Other dimensions keep the vanilla flag.
- Evidence: `FlatHorizonTest` (Switzerland gets the flag with every other field kept; overworld/nether/end returned as is). Live check with the fake sidecar: `tools/bot.py` now prints `spawn: <dim> flat=<isFlat>` from the login/respawn packets. Overworld login `flat=False`; `/swiss` → `swissworld:switzerland flat=True`; reconnecting inside Switzerland → `flat=True`; `/swiss back` → `minecraft:overworld flat=False`. The death-respawn path was not exercised live (the bot can't respawn); it builds the same record.
- **To check visually (real client):** in Switzerland, in the valley (~y −1430) and on a peak, the sky below the horizon is sky-coloured, not black, and the fog matches the overworld at the same time of day; the same after `/swiss` → `/swiss back` → `/swiss` and after dying and respawning there. The overworld sky is unchanged. If the void fog now looks too bright near the bottom, that is the vanilla flat-world behaviour.

## Missing terrain: no void deaths, placeholders filled in (2026-10-19)

- Bug (playtest): a vanilla `/tp` into cells the sidecar refused or was slow on froze the server for 20 s (StallGuard), the chunks completed as empty placeholders, and the player fell below min y and died ("fell out of the world"). The same happens with the sidecar down: tp, ender pearl, elytra, login.
- **Shorter stall for players.** `StallGuard` now checks every 250 ms and classifies the stack. If the frame that called `ServerChunkCache.getChunk` is in `net.minecraft.world.entity.*`, `ServerPlayer`, `ServerGamePacketListenerImpl` or `PlayerList`, it gives up after `playerStallAbortSeconds` (default 2, 0 = use `stallAbortSeconds`). Otherwise it keeps waiting `stallAbortSeconds` (20). jstack showed the cross-dimension tp stall in `ServerPlayer.doTick → baseTick → updateFluidHeight → getChunk`, not only in `setPosRaw`, so this matches by caller class. `/swiss` and `/swiss goto` stay non-blocking (ticket wait).
  - It aborts only if cells are actually outstanding. A tick that is merely slow (generation CPU, e.g. at login) is left alone.
  - Trade-off: an abort gives up **all** pending cells of that tick, so other chunks generating then also become placeholders. They are filled in while loaded (below) or regenerate when reloaded. Elytra flight into pending terrain can still freeze up to 2 s per chunk.
- **`world/TerrainHold`.** Every 4 ticks, for survival and adventure players in Switzerland. If the player's chunk is not loaded, is a placeholder or is still being filled in, or has not reached the client yet, the player is held:
  - a transient gravity modifier ×0 (synced, so the client stops falling and the floating kick stays off);
  - fall distance and the floating counter are reset, and the position is pinned (absolute teleport if the player drifts > 0.5);
  - the action bar shows "Terrain still loading…".
  - A move of more than 8 blocks (a /tp) re-holds the player there.
  - On release, a player below the ground (`MOTION_BLOCKING_NO_LEAVES`) is put on it, then lifted out of blocks (max 48).
  - A player above the ground is left there and falls, with a **grace**: no fall damage and no floating kick until they land (on ground at the top of their column, or in water), at most 30 s.
    - Without it, an elytra flight stopped by the hold, or a /tp into the air over a placeholder, ends in a deadly fall.
    - Vanilla counts any fall as "floating": with allow-flight=false, a fall longer than 4 s kicks the player.
    - The damage check looks only at the mark, because vanilla sets onGround before it applies landing damage.
  - `EntityInvulnerabilityCheckEvent`: in Switzerland, no `fell_out_of_world` damage at all; no `in_wall` or `fall` damage while held.
  - Creative and spectator players are left alone.
  - Death, logout, dimension change and game-mode change drop the hold and the modifier.
- **`world/PlaceholderRepair`.** Each loaded placeholder with all 8 neighbours loaded asks for its cells again (scan every second, max 256 jobs, retry 2 s). Once they are there, it generates off-thread into a `ProtoChunk` with the same `fill` as `fillFromNoise`. On the server thread, max 2 per tick, it swaps in:
  - sections, block entities and post-processing;
  - heightmaps (the worldgen ones too, plus the neighbours' worldgen ones, so decoration does not log "Unprimed heightmap"), then biome decoration (ores, snow) and the POI check.
  - It then relights the chunk like a new one. First it queues empty sky and block layers for the whole column: propagation only raises light, so without them the placeholder's sky 15 stayed in the stone (found on disk: 204k lit stone nibbles). Once light is done, it resends `ClientboundLevelChunkWithLightPacket` to players that already have it. The hold releases only after that (`settled`).
  - Not saved while empty: `AbortedChunks` downgrades placeholders on save (checked on disk: status `minecraft:biomes`, no sections or heightmaps). Repaired chunks are saved full.
  - Simplifications:
    - Light that crossed into neighbours from the empty chunk is not recomputed there.
    - A placeholder at the edge of the loaded area waits until its neighbours load.
    - Compared on disk with a normally generated region (720 chunks):
      - All 4 heightmaps are identical.
      - Blocks differ only in ore scatter (neighbours decorate in a different order) and in grass/dirt from random ticks.
      - Sky light differs in ~8k nibbles across 68 chunks. They sit in air near the surface and in leaves, half of them at chunk borders. There is none in stone.
- Evidence: `StallGuardTest` (tp stack, player-tick stack, forceload, no stall, limits) and `TerrainHoldTest` (ground y). Headless, private sidecar on 8095 with the dev bbox:
  - Old code: two 20 s stalls, then "Bot fell out of the world".
  - New code, each tp stalled ~2.1 s and the bot was held with the action bar; no kick, no damage. Covered: login into a placeholder, overworld → Switzerland tp, same-dimension tp, out-of-bbox tp, tp below the world (held at −2031), allow-flight on and off.
  - Sidecar killed mid-session: held. Cell copied in, or sidecar restarted: chunks filled in within ~1 s, chunks resent (`chunk resent` in bot.py), bot put on the ground at −1148.
  - Released 148 blocks above land: no damage (the broken first grace version: "fell from a high place"). Control fall of 12 blocks right after: −10 HP. Released 448 blocks up with allow-flight=false: no kick, no damage.
  - Not exercised live: ender pearl and elytra (both go through the same hold), real client visuals.
- Bot harness (`tools/bot.py`): no collision (a server correction counts as landing, so fall damage applies), ignores the gravity attribute, doesn't swim. So it drifts a few blocks between pins, and it drowned after a release 325 blocks above Lake Geneva; a real client would swim.

## /swiss goto <place> (2026-10-19)

- `goto` has two children: `a b` (doubles) first, then `place` (greedy string). On a full parse of both,
  Brigadier keeps the first registered, so numbers stay coordinates (`GotoGrammarTest`). "46.68, 7.87" lands in
  `place`; `PlaceLookup.twoNumbers` hands it to `goTo`.
- The lookup is async (`SidecarClient.places`, `GET /place`, 5 s timeout); the answer is handled in
  `server.execute`, the player looked up again by UUID; then the normal `travel` (prefetch, wait, teleport).
  Messages: "Going to Zofingen (town), LV95 E … N …, 439 m", "also: …" (≤ 3, click runs `/swiss goto E N`), not
  found, "still being prepared" (sidecar 503), "sidecar not reachable".
- Tab completion: `PlaceLookup.suggest`, never blocks the server: 1.5 s HTTP timeout, the suggestion future gives up
  after 400 ms (empty), the late answer still fills a 256-entry, 5 min TTL cache keyed on the folded prefix.
  Skips one letter and numbers. The client sorts suggestions alphabetically (rank is lost), so only names that
  start with the input are offered; an alias is offered when it is what matched ("Berthoud", tooltip "Burgdorf").
- `/swiss admin origin place <name>`: checks "not locked" before the lookup and again (changeOrigin) after it.
- Headless test (private sidecar 8096, server 25700, `tools/bot.py` now has `complete:<cmd>`): Interlaken, Grimsel
  (→ Grimselpass), Zofingen, zurich, Zürich, Biel (→ Biel/Bienne), Genf and Ginevra (→ Genève) all arrived on the
  ground; xyzzyq → not found; sidecar stopped → "not reachable"; index missing → "still being prepared", then
  built from the raw cache in 8 s. Completion answers within the 400 ms. `origin place` set the origin on a fresh
  world and was refused once locked.
- Seen, not fixed: `stop` waits for chunks whose cells are still being built.

## Arrival: wait on progress, land on the street (2026-10-19)

- `/swiss`, `goto E N` and `goto <place>` share `travel`/`tick`. No fixed timeout any more (`Arrival`): the wait goes
  on while cells for the 3x3 target chunks arrive, chunks generate, or the sidecar's `/status` counters move or show a
  build in progress (polled every 5 s); it gives up after 60 s without any of that, after 10 min in all (own message; a cap because the signals are global, other players and prefetches move them too), on
  `/swiss back`, or when the player changes dimension. Action bar: "Building terrain… 1/4 cells", then "Generating
  terrain… 6/9 chunks".
- Landing: the target column is open ground only if no building span covers it, the top (MOTION_BLOCKING_NO_LEAVES)
  is not fluid and sits ≤ 2 blocks above the DEM. Otherwise the nearest open column within 32 blocks (loaded chunks
  only), else the roof.
- Alternatives (and the pick, if anything else matched) skip building, tower, monument, boulder, chapel and church
  unless the query is their exact name (`PlaceLookup.offered`); the lookup asks for 8 to still show 3.
- `origin place` from rcon/console waits for the lookup on the server thread (≤ 5.5 s) so the reply reaches rcon;
  players stay async. Every outcome is logged.
- Headless (8096/25700): first goto Bern (cell cold) waited ~96 s with the bar and landed in the street at 542 m,
  4 blocks from a 4-storey building at the target point; Zofingen landed on the ground (439 m, was a 454 m roof),
  no "Heitern 300/50/25m" in "also:"; numeric goto into Luzern waited on 4 cells and arrived; `/swiss back` and a
  tp to the Nether cancelled; sidecar stopped → gave up after 60 s with "not reachable". rcon `origin place`:
  set/not found/connection refused/locked replies all arrived.

- Action bar during the cell build (later the same day): the sidecar's `/status` has `builds` (per cell: builder
  stage `step/steps`, elapsed, current download with bytes; `swissdata/progress.py`, a thread-local set by
  `assemble` and `fetch`). The mod polls it every 2 s and shows "Building terrain… cell k of n: downloading F x/y MB"
  or "…: buildings (6/8)", else "r/t cells, queued", then "Generating terrain… x/9 chunks"; every line ends in the
  elapsed seconds, so it changes every second even while one stage takes 40 s. Tested: plain `/swiss` onto an
  unbuilt origin cell (35 s, stages + seconds) and a 4-cell goto (download line seen).
- Found while testing: `gwr.snapshots` listed `_write`'s temp file, so a racing worker read a half-written npz
  (BadZipFile on a fresh store); fixed. Open, not mine: with current main, worldgen workers spun for minutes in
  `BuildingPlan.Planner.nearestFree` (via `light`) around E 2720 N 1117, so no chunk generated and goto gave up.

## M2 notes

- Sea and ocean fish spawns at y −1807.
- Sky/fog colours at real altitudes: the black horizon is fixed server-side (see above); still needs a visual client check.
- The pack fast path is done (see above); the all-air-section idea was dropped.
- Ores: ore veins (`ore_veins` noise) are not reproduced.
