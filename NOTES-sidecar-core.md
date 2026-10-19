# NOTES — sidecar-core (Milestone 1)

Decisions made autonomously, deviations from the brief, and contract proposals. Nothing in a contract file
was changed.

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
