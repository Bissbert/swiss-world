# NOTES — sidecar-core (Milestone 1, rebuild, canopy)

Decisions made autonomously, deviations from the brief, and contract proposals. Milestone 1 and the rebuild
changed no contract file; the canopy task changed `swd.py`, `build.py` (`LayerResult.trees`) and `fixtures/`
as the coordinator's task required (see *Canopy*).

## Contract proposals (need the coordinator)

1. **`docs/swd-format.md` §7: two extra manifest keys.** They are already written; readers that ignore
   unknown keys are unaffected.
   - `sources.altiNeighbours`: `{"E-N": item | null}` for the 8 neighbour tiles whose outermost
     pixels feed this cell's edge nodes (see *TERR node geometry*). `null` means no tile (abroad, or
     outside the dev bbox). A rebuild needs these to reproduce edges byte-identically, so they belong
     to the pinned sources.
   - `cells.<key>.evicted`: ISO timestamp. Set when the file is evicted. The entry and its `sources`
     stay; a rebuild removes the key.

   **Proposal:** document both in §7.
2. **`docs/sidecar-api.md` `/status`: extra upstream fields.** `upstream` also carries `downloads`
   and `downloadBytes`, which are cumulative per process. **Proposal:** document them as optional.

No change to `swd.py`, the Java readers, `fixtures/`, or the `LayerBuilder` protocol. `build.py` only got
its builder registrations (AltiTerrain, BorderMask) plus a two-line import, which is the intended
extension point. The builder modules import `LayerResult` lazily inside `build()`, so build.py can
import them without an import cycle.

## TERR node geometry (deviation from the brief's wording)

The swissALTI3D 2 m COG is 500×500 pixels with `AREA_OR_POINT=Area`, and its transform origin is the
tile's NW corner. So pixel (r, c) is the sample at its **centre**, E = e·1000 + 2c + 1. The xyz product
of the same tile confirms this: its first line is `2631001 1170999 603.98`. TERR nodes lie on the
**pixel corners** (E = e·1000 + 2i). There is no sample exactly on a node, so:

- node = mean of the 4 pixels around it (= bilinear interpolation at the corner);
- edge nodes need the outermost row/column of the N/S/E/W neighbours, and corner nodes also need one
  diagonal pixel. Hence all 8 neighbours, not just "the neighbour's first row/col";
- a missing neighbour contributes nothing: the node is the mean of the pixels that exist. Every cell
  sharing that node sees the same pixel set, so shared edges stay byte-identical (tested);
- neighbour edges are cached in `$SWISSDATA_SCRATCH/alti-edges/<item>.npz` (~8 KB), so each tile is
  read once per role;
- nodata is filled per tile **before** edges are taken, by iterated 4-neighbour means. Holes are
  therefore filled identically on both sides of a seam.

The acceptance test checks 20 nodes (16 random, plus corners and edges) against an **independent**
read: the 4 surrounding pixel centres, each read from whichever COG holds it via `ds.index`. The
tolerance is ±0.1 m (`tests/test_network.py`).

## Item pinning

- `pins.json` (store root, sidecar-internal) maps each **tile** to the item chosen the first time any
  cell needed it, keyed `alti/E-N` and `boundaries/ch`.
- Pinning per tile, not per cell, guarantees a neighbour is later built from the same item whose
  edge pixels were already used.
- A missing tile is pinned as `null`.
- Tiles outside the dev bbox are **not** pinned, so they say nothing about upstream.
- STAC: listings are sorted by id (2019 first), so `AltiIndex` paginates to the end. It resolves
  aligned 8×8 km blocks per query and filters by id, because the bbox query also returns touching
  tiles. The newest year wins, and the asset is `{id}_2_2056_5728.tif`.

## Readiness, eviction, lru.json

- **Ready** means a manifest entry exists, is not `evicted`, and the file exists. That follows the
  coordinator correction; a stray file is built over.
- **Eviction** runs after every write while store bytes > `store_cap_bytes`
  (`SWISSDATA_STORE_CAP_BYTES`, default 12 GB). Cells reported via `/generated` go first, then the
  rest; least recently requested/built first within each group. The just-written cell is protected.
- **Access times** live in `lru.json` (sidecar-internal), not in file atimes, which macOS doesn't
  keep reliably.

## Queue / server

- One heap, `(priority, seq)`, with `max_downloads` worker threads: `now` comes before `prefetch`,
  FIFO within a priority.
- Re-requesting a queued cell with a better priority pushes a new heap entry, and the stale one is
  skipped. Re-requesting with the same priority is a no-op (dedup).
- `position` = the number of live entries ahead of the cell.
- A failure gives `error` with `retryAfterS` 30. A request after that re-queues the cell; requests
  before then return the same error.
- `/wait` on a cell nobody requested queues it at `now` first. The contract doesn't say; long-polling
  a cell that never builds seemed useless.
- `/prefetch`:
  - `headingDeg` is a **compass bearing**: 0 = north (+N), 90 = east (+E). The API doc doesn't say;
    the mod must send it this way.
  - Corridor: `max(500, 60·speed)` m long and ±500 m wide, only when `speedMps > 0`.
  - Cells are queued nearest-first; `queued` counts newly queued cells only.
- `/place` returns 501 (gazetteer not in M1).
- `/status.versions.tlm` stays null until sidecar-tlm pins it.

## Dev bbox guard

`SWISSDATA_DEV_BBOX=1` (default in dev; the Dockerfile sets 0) limits the sidecar to tiles that
intersect the WGS84 test bbox **or** lie in one of two LV95 ranges:

- the plan's test range, E 2628–2672 × N 1154–1181;
- mod-core's origin area, 2626–2640 × 1164–1176.

**Downloads outside the WGS84 box: 103 ALTI tiles**, ~115 MB:

- 90 in rows N1154–1155 (the brief's LV95 range reaches south of the WGS84 box);
- 13 in column E2626 (the origin area).

Tiles beyond the guard (E2673, N1153, and E2626 outside N1164–1176) are treated as missing, so edge
nodes there use only the pixels inside.

*Caveat:* if those cells are later built for real (guard off), their shared edge with the dev area is
computed from the full pixel set and **differs** (sub-decimetre) from what the dev cells stored.
Production stores start empty, so this is dev-only. The fix is the reset described next.

**Reset done once:**

- The first bulk run had the guard without the origin range, so E2626 was refused and the 13 cells
  2627_1164…1176 were pinned with `"2626-…": null` neighbours.
- After the guard was widened, those 13 cells were **rebuilt unpinned** (their manifest `sources`
  replaced; the only time sources were rewritten), then E2626 was built.
- To find stale entries again: any `altiNeighbours` value of null whose tile is now in the guard.

## Raw files

- `SWISSDATA_KEEP_RAW=1` (dev default) keeps COGs in `$SWISSDATA_RAW`.
- With 0 (Dockerfile), a cell's own COG is deleted after its build. Its edges stay cached in scratch,
  so neighbours don't re-download it.
- A neighbour that was downloaded only for its edges stays until its own cell is built.
- Not yet scratch-cap aware.

## Small changes outside owned paths

- `sidecar/swissdata/config.py`: added `dev_bbox` (`SWISSDATA_DEV_BBOX`) and env
  `SWISSDATA_STORE_CAP_BYTES`.
- `sidecar/pyproject.toml`: pytest markers `network`/`slow`, with `-m 'not network'` by default.
- `sidecar/.dockerignore`: new.
- `sidecar/swissdata/buildings.py`, `gwr.py` (sidecar-buildings): the cache temp file name carries pid and
  thread id, since parallel rebuild workers may write the same sheet/GWR cache at once. No logic change.
- `sidecar/swissdata/buildings.py` `sheet_from_solids` (sidecar-buildings): sheets of the **2020 schema**
  (e.g. `…_2020_1249-24`, `…_2020_1250-13`, `…_2020_1251-11`, south-east of the test range) have no
  `GEBAEUDEEINHEIT` column, which raised `KeyError` and failed 49 cells. When the column is absent, a roof
  skin is linked by UUID, which in that schema usually equals its body's UUID. Sheets with the column behave
  exactly as before. Test: `test_sheet_of_2020_schema_links_roofs_by_uuid`.
  - Measured on the 7 cached 2020 sheets: 44 orphan (dropped) roof skins of ≈ 810, i.e. ≈ 5 %.
    1250-24 accounts for 40 of 718.
  - The 2021 Interlaken sheet 1208-43 has 37 of 5,918 (0.6 %).
  - So some 2020 roofs do not share their body's UUID. Those bodies keep their flat body top; the cells are valid.
  - A better link (e.g. spatial or EGID) is sidecar-buildings' call.

## Shared store

- The stray 27 KB synthetic `2632_1170.swd` was already gone when the real build started; the real
  cell has a manifest entry now.
- Tests only use `tmp_path` stores.

## Acceptance run (2026-10-19, shared store, `uv run python -m swissdata.server`, 2 workers)

- `POST /request {"cells":[[2631,1170]],"priority":"now"}` produced `store/cells/2631_1170.swd`
  (130 KB) in under 1 s; `/wait` returned `ready`. The COGs were already in raw.
- Bulk run through the queue, scripted as a client in the session scratchpad:
  - origin area 2626–2640 × 1164–1176 at `now`, then E 2628–2672 × N 1154–1181 at `prefetch`;
  - **origin area done after 61 s, everything after 301 s**;
  - **1,273 cells ready.** The 13 errors are exactly the E2626 column (dev guard). After the guard was
    widened and the E2627 column reset (see *Dev bbox guard*), a second pass built the 13 missing cells
    in 31 s. The final store holds **1,286 cells**: the whole origin area and the whole test range.
- **Store:** 391,372,546 bytes for 1,286 cells, i.e. **≈ 304 KB per cell**.
- **Downloads:** 1,267 COGs, 1.44 GB in the first pass. ALTI in raw totals 1.4 GB for 1,345
  tiles; total new downloads are ≈ 1.46 GB, including boundaries (19 MB) and one xyz (1 MB). Upstream errors: 1, a transient DNS failure that the fetch retry
  absorbed. Scratch (edge cache): 22.6 MB.
- **Seams:** all 2,497 shared edges between adjacent cells are byte-identical, including the 13 at
  E2626/2627. Heights run
  556.5–4,273.5 m, with no NaN.
- **No border cells** in the test bbox, so no INSD was emitted there. The INSD path is covered by the
  network test on 2610_1269 (Basel/Allschwil).

## Rebuild (`python -m swissdata.rebuild`)

`python -m swissdata.rebuild (--bbox E0 N0 E1 N1 | --all) [--workers N] [--dry-run]`, run from `sidecar/`.

- **Selection:** live manifest entries only. Evicted cells are rebuilt on demand by the server from their
  kept sources. The bbox is inclusive in km. With the dev guard on, a cell outside the dev area is logged and
  skipped, never written. Order: mod-core's origin area (2626–2640 × 1164–1176) first, then the rest by key.
- **Pins:** each cell is assembled with its manifest `sources` as `pinned`. `build.merge_sources(old, new)`
  keeps every old key, adds new ones (`tlm`, `bathy`, `buildings`, `gwr`), which pins them, and raises
  `PinChanged` if a builder returns a different value for an existing key. Such a cell fails and is left
  untouched. The server's `build_cell` uses the same merge, so a rebuild of an evicted cell cannot drop keys.
- **Atomicity:** worker *processes* assemble and encode (voxelising and GDAL are CPU-bound; threads mostly
  serialised on the GIL). Only the main process writes:
  - each file goes to a unique temp name in `cells/`, is fsynced and then renamed over the old one, so the mod
    sees the old or the new cell, never a partial one;
  - the manifest entry is replaced in batches (25 cells or 5 s). Until its batch lands, a cell is still ready
    with its old entry; `bytes` in that entry may briefly be stale.
  - The first cell is built in the main process, so on-disk caches (GWR table, sheets) are warm before the
    pool starts. The temp names of those caches (buildings, gwr) now carry pid and thread id, because
    workers may race to write the same cache (files outside owned paths, see below).
- **LRU:** a rebuild writes with `touch=False`, so access times and the `/generated` marks stay as they were
  (otherwise origin-first order would make the origin area the first eviction victims).
- **Do not run it next to a server that builds the same cells.** Nothing breaks (the last manifest write
  wins per cell, both entries are valid), but work is wasted, and the server's in-memory LRU state would
  overwrite lru.json on its next flush.

### Batched writes (task 3)

- `Manifest.record_many` / `Store.write_many`: one locked rewrite of `manifest.json` per batch instead of one
  per cell. The server still records per cell, because readiness needs the entry.
- `lru.json` is advisory and is now written at most every `LRU_FLUSH_S` = 10 s, plus on eviction,
  `/generated`, server shutdown and `Store.flush()`. A crash loses at most 10 s of access times.

### GWR pin

The pinned `gwr` snapshot (`gwr_be_<date>`) stays reproducible only while the parsed table in
`scratch/gwr/be.v1.npz` or `raw/public.madd.bfs.admin.ch/be.zip` is kept: upstream publishes a new snapshot
daily and old ones cannot be re-downloaded. `BuildingsBuilder` does not check a pinned `gwr` against the table
it loaded (sidecar-buildings' code; flagged, not changed).

**Consequence since `merge_sources`:**
- If that table changes (be.zip re-fetched after scratch and raw were wiped, or the cache version bumps), the
  builder emits a new `gwr` id for every cell.
- `build.merge_sources` then raises `PinChanged`. Both the rebuild and the server's `build_cell` (including
  rebuilds of evicted cells) would fail such cells permanently with `error`/`retryAfterS` 30.
- That is consistent with `tlm.check_pinned`, which already fails hard, but the mod would see permanent errors.
- **Fix needed (sidecar-buildings, via coordinator):** honour a pinned `gwr` by keeping each snapshot's table
  under its id, or decide that `gwr` is advisory and exempt it from the pin check.

### TLM extract margin

The TLM extract (`tlm.EXTRACT_BBOX` = 2626–2674 × 1152–1183 km) covers every store cell, but there is no
margin west of E2626. Features lying entirely west of 2626 000 are missing for a builder that looks beyond
the cell edge of column E2626. Features that cross into the cell are complete (bbox filter keeps whole
features).

### Rebuild run (2026-10-19, shared store, 8 worker processes, no server running)

- Baseline copy of the store (cells, manifest, lru) in the session scratchpad before the run.
- `--all`: 1,286 cells selected. **Origin area (195 cells) done after 211 s**, everything after 865 s.
  - 1,237 cells rebuilt.
  - 49 failed on the 2020-schema sheets (see above), all at N1154–1155 E2650–2672 and E2672 N1156–1158.
    They kept their old file and entry.
  - After the fix, `--bbox 2650 1154 2672 1155` and `--bbox 2672 1156 2672 1158` rebuilt exactly those 49
    in 51 s. **Total ≈ 916 s.**
- **Store:**
  - 1,286 cells, 478,777,695 bytes, i.e. **≈ 372 KB per cell**; it was 304 KB terrain-only.
  - Median 372 KB, min 67 KB, max 1.41 MB (Interlaken, 959 buildings).
  - manifest.json is 1.0 MB.
- **Sources:**
  - Every entry now has `alti, altiNeighbours, bathy, buildings, gwr, tlm`.
  - `tlm` = `swisstlm3d_2026-02`; `gwr` = `gwr_be_2026-10-19`.
  - `buildings` is non-empty in 1,220 cells; `bathy` is non-empty in 70.
  - All old pins are unchanged against the baseline.
- **TERR vs baseline:**
  - identical in 184 cells;
  - changed only in the 1,102 cells that contain water (WKND), which is the water builder's carving and
    bathymetry;
  - **0 dry cells changed**, so the ALTI pins held.
- **Seams:** all 2,497 shared TERR edges are byte-identical after the water carving too.
- **Other checks:**
  - `lru.json` is identical to the baseline (touch=False);
  - the manifest `bytes` matches each file;
  - no temp files are left in `cells/`;
  - 38,839 buildings in total.
- **New downloads:** ≈ 100 MB (raw went from 5,044,504 KB to 5,142,192 KB), all swissBUILDINGS3D per-sheet
  gdb.zip.
  - The whole range needs 120 sheets (≈ 174 MB); 4 were there before, and sidecar-buildings had fetched more.
  - Some sheets lie outside the WGS84 box, in the brief's LV95 range (same precedent as ALTI).
  - No country-wide item.
- Memory: 8 workers peaked at ≈ 4 GB RSS in total (trial run).

## Canopy (CNPY + TTOP, `swissdata/canopy.py`)

### Sources (opendata.swiss terms_by: "Open use. Must provide the source")
- **swissSURFACE3D Raster**, STAC collection `ch.swisstopo.swisssurface3d-raster` (not the older point-cloud
  collection `ch.swisstopo.swisssurface3d`). Items `swisssurface3d-raster_<year>_<e>-<n>`, asset
  `<item>_0.5_2056_5728.tif`: 2000×2000 float32 COG, 0.5 m DSM, nodata -9999, Area, origin at the NW corner.
  Licence: https://www.swisstopo.admin.ch/ogd-conditions, attribution "© swisstopo".
  - Newest item per tile via `stac.SurfaceIndex` (a generic `TileIndex`, which `AltiIndex` now also uses);
    pinned per cell as `sources.surface`. Years differ by tile (2632-1170: 2022, 2652-1170: 2023 (no 2022
    item), 2632-1171 has a 2024 item).
  - Download size per tile: 12,357,677 B (2632-1170), 15,857,897 (2652-1170), 16,746,456 (2631-1171),
    15,914,978 (2642-1161). So 12-17 MB, ~15 MB typical.
- **Waldmischungsgrad LFI** (forest mix rate NFI), STAC collection `ch.bafu.landesforstinventar-waldmischungsgrad`,
  one item `landesforstinventar-waldmischungsgrad` (2023-10-31), asset `…_2056.tif`: 170,912,760 B, whole
  country, 10 m, int8 = percent broadleaf, -128 = no forest, Sentinel-1/2 machine-learning product.
  opendata.swiss dataset `waldmischungsgrad-lfi`, terms_by, attribution "© BAFU / WSL (LFI)".
  - It is only published for the whole country. It is small, so it is fetched once and kept (it counts
    towards the raw footprint), pinned as `sources.forestMix` = `landesforstinventar-waldmischungsgrad@2023-10-31`.
  - Used for `kind`: conifer (1) / broadleaf (2), drawn per tree with probability = the pixel's broadleaf
    share, from a hash of the global column (deterministic). No forest pixel -> 0. Larch (3) is never emitted:
    no open larch layer was found (the mix raster counts larch as conifer).

### Method (documented in the module docstring)
- CHM = DSM − TERR (bilinear between 2 m nodes at each pixel centre). CNPY = rint(max of the column's 4
  pixels), 0 below 2 m.
- Masked (CNPY 0, no tops): BSPN columns + 1-column ring (roof edges), water (WKND/WLVL), bridge/dam decks
  (OFLG), columns with CHM > 50 m + 1 ring (cliff rims the 2 m TERR misses, masts: ~0.1 % of tops, up to
  77 m before), and rock/scree/glacier (SURF 4/5/6) outside the mix raster's forest (boulders and faces read
  as 5-22 m "canopy" on 2652_1170).
  - The building ring was checked: the share of CNPY > 0 at chessboard distance 2-6 from buildings is flat
    (0.26-0.31) on 2632_1170, so 1 column is enough.
- Tops: Gaussian smoothing (σ 0.5 m), 3×3 maxima ≥ 3 m, then a variable window: a top survives if no higher
  candidate lies within r(h) = (3 m + 0.1·h)/2 (Popescu & Wynne 2004; lidR's lmf example). Tops on overlays
  (OCLS ≠ 0, tunnels excepted) or masked columns are dropped. heightM = CNPY at the top's column (a tested
  invariant).
- crownDm: canopy columns ≥ half a top's height join the nearest top within 1 m + 0.15·h; radius of a disc
  of that area. That is a nearest-top stand-in for a watershed (cheaper; good enough for crown sizing).

### Seam rule (**deviation from the task**)
The task suggested using neighbour DSMs if they are in raw, otherwise suppressing tops within 2 m of the east
and south edges. Both parts were dropped:
- Neighbour use would make TTOP depend on cache state: if only one side of a seam had its neighbour in raw,
  both cells could keep the same tree, and a rebuild would not reproduce the cell from its pins.
- An east/south band of 2 m (or r(h)) still duplicates trees whose crown is wider than the band: the west
  cell sees the cut crown rising towards the edge and keeps a top there.

Rule used instead (raw-independent): each tile is processed alone, and tops whose smoothed maximum lies on the
outermost pixel ring are dropped (after the window test, so they still suppress the lower bumps of their own
crown). A cut crown always peaks at the edge, so the cell that really contains the top is the only one keeping
it, on all four sides alike. Cost: tops within ~1 m of a seam may be lost (~0.4 % of trees), never duplicated.
Tests: crowns at −6.25…+6.25 m from an E-W and an N-S seam with radii 2.5/4/6 m, and a 4-cell corner.

### Validation (private store, scratchpad; never the shared store)
| cell | what | s | trees | trees/ha in forest¹ | height p10/50/90/99, max | CNPY / TTOP bytes |
|---|---|---|---|---|---|---|
| 2632_1170 | Interlaken town | 3.8 | 4,408 | 278 (0.5 ha) | 4/7/17/27, 42 | 136,727 / 35,268 |
| 2652_1170 | subalpine 1560-2010 m | 1.4 | 6,832 | 190 | 4/10/23/31, 37 | 169,396 / 54,660 |
| 2631_1171 | dense lowland mixed forest | 1.2 | 18,316 | 189 | 11/21/32/39, 48 | 481,017 / 146,532 |
| 2642_1161 | dense conifer forest | 1.7 | 22,157 | 230 | 12/22/28/32, 39 | 471,315 / 177,260 |

¹ trees whose top lies on SURF forest classes 1/2/3, per ha of those classes.
- Lowland forest: dominant trees (p90-p99) 28-39 m, which fits the expected 25-40 m. The median is ~22 m
  because ITD also finds intermediate and edge trees. Subalpine forest is lower (p90 26 m in forest).
- 190-280 trees/ha: plausible for ITD in mature forest (a 1 m window would give > 800/ha).
- Times are compute only, with the DSM already on disk (DSM read ≈ 1 s), plus a ~15 MB download per cell.
  Earlier full builds were ~2.7 s per cell per worker, so canopy adds ~1-2 s.
- Kinds: 2642_1161 is 95 % conifer, 2631_1171 is 76 % broadleaf, matching the mix raster.
- Previews: `.data-cache/previews/{2632_1170,2652_1170,2631_1171,2642_1161}_CNPY.png` (CNPY plus a 200 m zoom
  with tops, crowns and kinds).

### Footprint
- Raw: 171 MB mix (kept) + one DSM per cell in flight (≤ 17 MB per worker), deleted after extraction unless
  `SWISSDATA_KEEP_RAW=1` is set **explicitly** (`Config.keep_surface_raw`; unlike the ALTI COGs, the dev
  default does not keep them). With `KEEP_RAW=1` the test bbox needs ~1,286 × 15 MB ≈ 19 GB, too close to the
  20 GB limit next to the current 5.1 GB raw.
- Store: cells grow by CNPY (137-481 KB) + TTOP (35-177 KB), so forest cells go from ~0.4 MB to ~1 MB. My
  estimate for the 1,286 shared cells is ~0.5 GB → ~1.1 GB.
- **A shared-store rebuild re-downloads ~19 GB of DSM** (transient, deleted per cell), ~1,286 × 15 MB.
  Not run: the coordinator holds it while mod-core benchmarks.
- On demand, every newly built cell now downloads ~15 MB more, which adds latency for a player exploring new ground.

### Contract changes (authorised by the task; please review)
- `swd.py`: `CNPY` in the u8 layers (encoded after INSD); `Tree` dataclass, `KIND_*`; `Cell.trees` = None
  (TTOP absent: no DSM, the mod keeps its statistical trees) vs `[]` (TTOP present and empty: DSM, no trees).
  TTOP is sorted by (dz, dx) on write.
- `build.py`: `LayerResult.trees`; `assemble` passes it on (only from builders declaring the TTOP tag);
  `CanopyBuilder` is registered after Buildings (needs BSPN) and before DefaultSurface.
- `fixtures/synthetic_2632_1170.*`: CNPY 18 m on the forest strip dx 500-599 (0 under the bridge deck), and
  3 trees in TTOP; expected.json has `cnpy` per probe column and a `trees` list. `./gradlew test --offline`:
  45 tests, 0 failures (1 skipped), so the Java readers ignore the new tags as specified.
- New dependency: `scipy>=1.13` (ndimage, cKDTree).
