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
intersect the WGS84 test bbox **or** lie in the LV95 range E 2628–2672 × N 1154–1181. Results:

- **mod-core's origin area, 2626–2640 × 1164–1176: column E2626 is outside both**, so those 13 cells
  come back as `error` ("outside the dev test bbox"). E2627 is inside the WGS84 box. If mod-core
  needs E2626, widen the guard.
- Neighbours beyond the guard (E2673 and N1153 for the bbox edge cells) are treated as missing tiles, so
  edge nodes use only the pixels inside the bbox.
  - *Caveat:* if those cells are later built for real (guard off), their edge with the dev area is
    computed from the full pixel set and **differs** (sub-decimetre) from what the dev cells stored.
    Production stores start empty, so this is dev-only.

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
