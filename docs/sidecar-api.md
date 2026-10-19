# swissdata sidecar HTTP API, v1 (contract)

**Base URL:** configurable in the mod (`sidecarUrl`). Default `http://swissdata:8080` in compose, `http://127.0.0.1:8080` in dev.

**Format:** all bodies are JSON (UTF-8). Cells are `[eKm, nKm]` (see `swd-format.md` §1).

**The mod never downloads data through this API.** It reads `cells/*.swd` and `coverage.bin` directly from the **shared store directory**:

- dev: `<repo>/store`;
- compose: a volume mounted at `/store` in the sidecar and wherever the mod's config points.

The API is only the control plane.

## Endpoints

### `GET /health`
`200 {"ok": true, "version": "0.1.0", "formatVersion": 1}`

### `POST /request`
Ask for cells to be built. The call is idempotent and returns immediately.

```json
{"cells": [[2631,1170],[2632,1170]], "priority": "now"}
```

- `priority`: `"now"` (a chunk is waiting) or `"prefetch"`.

Response:

```json
{"cells": [
  {"cell":[2631,1170], "state":"ready"},
  {"cell":[2632,1170], "state":"queued", "position": 3},
  {"cell":[2700,1300], "state":"outside"},
  {"cell":[2633,1170], "state":"error", "error":"upstream 503", "retryAfterS": 30}
]}
```

| State | Meaning |
|---|---|
| `ready` | The file exists in the store |
| `queued` / `building` | In progress |
| `outside` | Coverage = 0; no file will ever exist |
| `error` | Failed after retries. The mod keeps the chunk pending and asks again after `retryAfterS`. |

### `GET /wait?cell=2631,1170&timeoutMs=20000`
Long-poll until the cell is `ready`, `outside` or `error`. Returns the same entry shape as `/request`.

The mod may use this, or simply watch the store directory.

### `POST /prefetch`
```json
{"e": 2633662.0, "n": 1170427.0, "radiusM": 2000, "headingDeg": 90.0, "speedMps": 30.0}
```

- Enqueues cells in the radius at prefetch priority.
- `headingDeg` is a **compass bearing**: 0 = north (+N, Minecraft −z), 90 = east (+E, Minecraft +x).
- If `speedMps > 0`, it also enqueues a corridor of length `max(500, 60·speed)` m along the heading.

`202 {"queued": 12}`

### `POST /generated`
The mod reports cells whose chunks are all generated. These are evicted first when the cache is over budget.

`{"cells": [[2631,1170]]}` → `204`

### `GET /status`
```json
{"queue": {"now": 0, "prefetch": 12, "building": 2},
 "builds": [{"cell": [2600, 1199], "stage": "buildings", "step": 6, "steps": 8, "elapsedS": 41.5,
             "download": {"file": "swissbuildings3d_3_0_2025_1166-24.gdb.zip", "bytes": 52428800, "totalBytes": 210000000}}],
 "store": {"cells": 431, "bytes": 182000000, "capBytes": 12000000000},
 "scratch": {"bytes": 0, "capBytes": 2000000000},
 "upstream": {"requests": 5021, "errors": 3, "lastError": null, "downloads": 1345, "downloadBytes": 1460000000},
 "versions": {"tlm": "swisstlm3d_2026-02", "boundaries": "swissboundaries3d_2026-01", "names": "swissnames3d_2026"}}
```

`builds` (added for the arrival progress bar) lists the cells being built right now, one entry per worker: the
builder running (`stage`, its 1-based `step` of `steps`), seconds since the build started, and `download` while that
builder streams a file into the raw cache (`totalBytes` null without a Content-Length). Downloads served from the cache
do not appear. The mod polls this every 2 s while a player waits for terrain.

### `GET /place?q=Grimsel&limit=10[&mode=prefix|exact]`
Gazetteer lookup (swissNAMES3D), used for `/swiss goto` and its tab completion.

`{"results":[{"name":"Grimselpass","kind":"pass","e":2668880,"n":1157032,"h":2163}]}`

- Case- and accent-insensitive (`zurich` finds Zürich). `mode=prefix` (default) matches the start of the name or of
  any word in it; `mode=exact` the whole name. `limit` is 1..25 (default 10).
- Order: exact before prefix; then settlements (`city` ≥ 50k inhabitants, `town` ≥ 2k, `village`, `hamlet`,
  `district`) before peaks/passes, lakes/glaciers/valleys, `quarter`s, stations, other named places; then size.
- `kind` is lowercase English: city, town, village, hamlet, district, quarter, peak, pass, hill, lake, glacier,
  valley, massif, island, station, … `h` (metres) may be null.
- Other languages: exonyms and the halves of bilingual names match too (`Genf` → Genève, `Biel` → Biel/Bienne); the
  result then carries `"matched": "Genf"`.
- **503** `{"error": …, "state": "building", "retryAfterS": 10}` while the index is not ready. The first query on a
  store without `names/places.v1.tsv.gz` downloads swissNAMES3D once (33 MB) and builds it (~1.4 MB, seconds).
  The item is pinned (`pins.json` `names/ch`) and shown in `/status.versions.names`.

## Behaviour rules

- **Upstream politeness:**
  - at most 2 concurrent downloads (configurable, hard maximum 4);
  - User-Agent `swissdata/<ver> (+contact)`;
  - exponential backoff;
  - **every upstream asset is downloaded at most once** into the shared raw cache, then deleted after processing unless `keepRaw` is set (dev).
- Cell building is deterministic: the same `sources` give byte-identical layers.
- `sources` for a cell are chosen once (the newest available item), then pinned in `manifest.json` forever.
