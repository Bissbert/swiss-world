# `.swd` cell format, version 1 (contract)

This is the binding contract between the **sidecar** (Python writer) and the **mod** (Java reader).

- Reference implementations: `sidecar/swissdata/swd.py` and `mod/src/main/java/ch/bissbert/swissworld/cell/SwdReader.java`.
- Golden fixture: `fixtures/synthetic_2632_1170.swd`, with expected values in `fixtures/synthetic_2632_1170.expected.json`. **Both sides must have a test that reads the fixture.**
- Changing the format requires bumping `formatVersion`, updating both readers and regenerating the fixture, all in one commit.

## 1. Cells and coordinates

- A cell is a 1 × 1 km square of LV95 (EPSG:2056), aligned with the swisstopo 1 km tiles.
- **The cell id is its lower-left (south-west) corner in km**: `(eKm, nKm)`.
  - The cell covers `E ∈ [eKm·1000, eKm·1000+1000)` and `N ∈ [nKm·1000, nKm·1000+1000)`.
  - Example: swissALTI3D tile `2631-1170` is cell `(2631, 1170)`.
- File name: `cells/{eKm}_{nKm}.swd`, e.g. `cells/2631_1170.swd`.

### Columns (1 m grid)

- A column is a 1 × 1 m square, which becomes one Minecraft block column.
- Local column indices inside a cell:
  - `dx = 0..999`: metres **east** of the west edge.
  - `dz = 0..999`: metres **south** of the north edge.
- So the column `(dx, dz)` has its centre at `E = eKm·1000 + dx + 0.5` and `N = nKm·1000 + 1000 − dz − 0.5`.
- All 1 m layers are **row-major with `dz` as the outer index**: `index = dz·1000 + dx`.

### World mapping (mod side, for reference)

The origin is `(E0, N0)`, integer metres.

- A block `(x, z)` has its centre at `E = E0 + x + 0.5` and `N = N0 − z − 0.5`.
- So `Ei = E0 + x` and `Ni = N0 − z − 1` (floor of the centre).
- Then `eKm = floorDiv(Ei, 1000)`, `dx = Ei − eKm·1000`, `nKm = floorDiv(Ni, 1000)`, `dz = nKm·1000 + 999 − Ni`.

### Heights

- **Every height is stored as `u16` decimetres with offset −200 m**: `raw = round((h_m + 200) · 10)`.
  - The range is −200 m … 6353.4 m.
  - `0xFFFF` means **no value**.
- Real height in metres: `h = raw / 10 − 200`.
- The mapping to Minecraft y (`y = h − 2000`, compressed above 3000 m) is done by the mod, never by the sidecar.

## 2. File layout

**All integers are little-endian.**

```
Header (24 bytes)
  0  char[4]  magic        "SWD1"
  4  u16      formatVersion = 1
  6  u16      flags        bit0 = cell intersects the national border (INSD layer present)
  8  i32      eKm
 12  i32      nKm
 16  u32      layerCount
 20  u32      reserved = 0
Layer directory: layerCount × 32 bytes
  0  char[4]  tag          e.g. "TERR"
  4  u32      codec        0 = raw, 1 = zlib (RFC 1950, java.util.zip.Inflater compatible)
  8  u64      offset       absolute file offset of the stored bytes
 16  u64      storedLength
 24  u64      rawLength    length after decompression
Layer blobs follow, in directory order.
```

- Readers **must ignore unknown tags**, so new optional layers don't need a version bump.
- Missing optional layers mean "nothing here": no water, no overlays, no buildings.
- A cell file is written atomically: write `*.swd.tmp`, fsync, then rename.

## 3. Layers

| Tag | Required | Type / shape | Meaning |
|---|---|---|---|
| `TERR` | yes | `u16[501·501]`, `index = j·501 + i` | **Ground/bed height nodes on a 2 m grid.** Node `(i, j)` lies at `E = eKm·1000 + 2i`, `N = nKm·1000 + 1000 − 2j`. The edge nodes (i or j = 500) duplicate the neighbour's first nodes, so interpolation is seamless. Lake beds are already merged in (bathymetry or synthetic depth), so `TERR` is the **solid surface under water**. |
| `SURF` | yes | `u8[1000·1000]` | Surface class, see §4 |
| `WLVL` | no | `u16[1000·1000]` heights | **Water surface** height per column; `0xFFFF` = no water. Water fills from `TERR` (exclusive) up to `WLVL`. Rivers are already stepped by the sidecar. |
| `WKND` | no | `u8[1000·1000]` | Water kind: 0 none, 1 lake, 2 river, 3 stream, 4 reservoir, 5 pond, 6 waterfall column |
| `OCLS` | no | `u8[1000·1000]` | Overlay class, see §5 |
| `OELV` | no | `u16[1000·1000]` heights | Overlay **top surface** height (road deck, bridge deck, tunnel floor, dam crest); `0xFFFF` = follow the terrain |
| `OFLG` | no | `u8[1000·1000]` | Overlay flags: bit0 bridge, bit1 tunnel, bit2 rail centre line (place a rail block here), bit3 overlay edge (kerb/shoulder), bit4 dam body |
| `INSD` | when flags.bit0 | `u8[1000·1000]` | 1 = inside Switzerland, 0 = outside (ocean). Absent = all inside. |
| `BTBL` | no | see §6 | Building table |
| `BSPN` | no | see §6 | Building column spans |

### Coverage index (not a cell)

The file `coverage.bin` in the store root says where cells exist:

```
char[4] "SWCV" · u16 version=1 · u16 reserved
i32 eMinKm · i32 nMinKm · u32 width · u32 height      (km grid)
u8[width·height], index = (n − nMinKm)·width + (e − eMinKm)
    0 = fully outside Switzerland (ocean; no cell file will ever exist)
    1 = border cell (cell exists, has INSD)
    2 = fully inside
```

It is written once from swissBOUNDARIES3D and is all the mod needs to generate ocean without waiting.

## 4. Surface classes (`SURF`)

| Value | Class | Value | Class |
|---|---|---|---|
| 0 | meadow / grassland (default) | 9 | wetland |
| 1 | broadleaf forest | 10 | urban sealed (paved) |
| 2 | conifer forest | 11 | urban green (gardens, parks) |
| 3 | mixed forest | 12 | orchard |
| 4 | rock | 13 | vineyard |
| 5 | scree / loose rock | 14 | sparse trees / bushes |
| 6 | glacier / firn | 15 | gravel / sand (river banks) |
| 7 | lake / standing water | 16 | farmland |
| 8 | river / flowing water | 17 | alpine pasture |

Values 18–255 are reserved. Readers map unknown values to 0.

## 5. Overlay classes (`OCLS`)

| Value | Class | Value | Class |
|---|---|---|---|
| 0 | none | 8 | rail bed (ballast) |
| 1 | motorway | 9 | rack railway bed (no rail blocks) |
| 2 | main road | 10 | platform |
| 3 | secondary road | 11 | dam / weir crest |
| 4 | minor / access road | 12 | runway / airfield |
| 5 | farm / forest track | 13 | sports pitch |
| 6 | footpath / hiking trail | 14 | parking |
| 7 | steps | | |

Rail blocks are placed where `OFLG.bit2` is set on a class-8 column. The mod derives the rail shape from the neighbouring rail-centre columns.

## 6. Buildings

### `BTBL`

```
u32 count
count × entry (48 bytes):
  u64 gid            stable global building id = first 8 bytes of SHA-1(UUID string), little-endian.
                     Identical in every cell the building touches.
  u64 egid           GWR EGID (0 = unknown)
  u16 objektart      swissBUILDINGS3D OBJEKTART as a stable code (table below; the source stores text)
  u16 gwrCategory    GKAT (0 = unknown)
  u16 gwrClass       GKLAS (0 = unknown)
  u16 gwrPeriod      GBAUP (0 = unknown)
  u8  floors         GASTW (0 = unknown)
  u8  entranceSide   0 unknown, 1 N, 2 E, 3 S, 4 W
  u16 baseH          height (u16 dm encoding) of the lowest point of the solid
  u16 topH           height of the highest point
  u16 reserved
  i32 minX, minZ, maxX, maxZ   footprint bbox in columns RELATIVE TO THIS CELL's (dx, dz);
                               may be < 0 or > 999 when the building crosses cell borders
```

### `BSPN`

Spans are sorted by `(dz, dx)`:

```
u32 columnCount
columnCount × column:
  u16 dx · u16 dz · u8 spanCount
  spanCount × span (8 bytes):
    u16 bottomH      u16 dm height, inside from here …
    u16 topH         … up to here (exclusive)
    u16 buildingIdx  index into this cell's BTBL
    u8  part         0 body, 1 roof overhang/eave (outside the walls), 2 annex
    u8  roofFlags    bit0 top of this span is roof, bit1 slope half-step (top ends at x.5)
```

**Span rules:**

- Spans in one column **never overlap** and are sorted bottom-up. The source has overlapping solids. The sidecar resolves them bottom-up: a span starting inside the one below is clipped to start at its top, or dropped if it lies fully inside.
- `roofFlags.bit0` is set on the **topmost span of each building** in a column, not only on the topmost span of the column.
- A building is listed in a cell's `BTBL` when at least one of its column centres (body or eave) lies in the cell.

**`objektart` codes.** These are append-only, because the codes are written into cells. Unknown text maps to 0.

| Code | OBJEKTART | Code | OBJEKTART |
|---|---|---|---|
| 0 | unknown / other | 6 | Unterirdisches Gebaeude |
| 1 | Gebaeude Einzelhaus | 7 | Lagertank |
| 2 | Hochhaus | 8 | Treibhaus |
| 3 | Sakrales Gebaeude | 9 | Mauer gross |
| 4 | Im Bau | 10 | Bruecke gedeckt |
| 5 | Gebaeude unsichtbar | | |

The mod derives walls, floors, stairs and roof shapes from spans and neighbours. A building that crosses a cell border has spans in each cell and the same `gid` everywhere.

## 7. `manifest.json` (store root, written by the sidecar only)

```json
{
  "formatVersion": 1,
  "cells": {
    "2631_1170": {
      "processor": "swissdata 0.1.0",
      "created": "2026-10-19T12:00:00Z",
      "sources": {
        "alti": "swissalti3d_2025_2631-1170",
        "buildings": ["swissbuildings3d_3_0_2021_1208-43"],
        "tlm": "swisstlm3d_2026-02",
        "bathy": ["swissbathy3d_brienzersee"],
        "boundaries": "swissboundaries3d_2026-01"
      },
      "bytes": 412345
    }
  }
}
```

Extra keys the sidecar writes:

- `sources.altiNeighbours`: `{"E-N": itemId | null}`, the neighbouring ALTI tiles used for edge nodes. A rebuild needs them to reproduce identical edges.
- `cells.<key>.evicted`: an ISO timestamp, set when the file was evicted. The entry and its `sources` stay. A cell is **ready** only when the file exists **and** it has a manifest entry without `evicted`.

**`sources` is never removed**, even when a cell file is evicted. A rebuild uses exactly these upstream item ids, so seams can't appear between rebuilt and saved areas.
