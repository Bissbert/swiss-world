# NOTES — sidecar-buildings (Milestone 1)

## Contract proposals (need the coordinator)

1. **OBJEKTART codes (docs/swd-format.md, BTBL `objektart`).** The swissBUILDINGS3D 3.0 FGDB stores OBJEKTART as
   *text* ("Gebaeude Einzelhaus", …), not as codes, and no coded domain ships with the gdb. `buildings.OBJEKTART`
   defines a stable append-only u16 table (0 = unknown/other, 1 Gebaeude Einzelhaus, 2 Hochhaus,
   3 Sakrales Gebaeude, 4 Im Bau, 5 Gebaeude unsichtbar, 6 Unterirdisches Gebaeude, 7 Lagertank, 8 Treibhaus,
   9 Mauer gross, 10 Bruecke gedeckt). Proposal: copy this table into swd-format.md as the meaning of "raw code".
2. **roofFlags bit0 semantics.** Implemented as: set on the topmost span of a building in a column (body or
   overhang). Spans of a lower building under another building's overhang keep their own bit0. If the mod
   wants "topmost span in the column regardless of building", say so — it is a one-line change.
3. **Spans in a column are disjoint** (see Decisions) — proposal to state it in swd-format.md §6.
4. **Building in a cell** = at least one of its column centres (body or overhang) lies in the cell. The BTBL bbox
   is the whole building (all its columns, including other cells), relative to this cell. A building touching
   the cell only by a sliver smaller than one column centre is not listed (Interlaken: 1 case, 0.78 m²).

No contract file was edited.

## Decisions

- **Caches** go to `cfg.scratch` (`SWISSDATA_SCRATCH`, default `<worktree>/.data-cache/scratch`):
  `buildings/<item>.v2.npz` (parsed + voxelised sheet) and `gwr/<canton>.v1.npz` (EGID table). They are
  rebuildable from `$SWISSDATA_RAW`, written atomically, and versioned by file name.
- **STAC search lives in `buildings.py`** (`find_sheets`), not in `stac.py` (owned by sidecar-core). It uses the
  items bbox search, follows `next` links, keeps only ids matching `^swissbuildings3d_3_0_(\d{4})_(\d{4}-\d{2})$`
  (so the 14 GB whole-country `swissbuildings3d_3_0_<year>` items can never be selected) and keeps the newest
  year per sheet. The search bbox is the cell padded by 150 m: a building belongs to exactly one sheet but may
  stick out of that sheet's nominal extent (seen: 43 m), so neighbouring sheets must be loaded too. Buildings are
  deduped by UUID across sheets anyway. Cell 2632_1170 needs 4 sheets (1208-43, 1208-44, 1228-21, 1228-22).
  If sidecar-core grows a generic STAC search, `find_sheets` should switch to it.
- **pinned["buildings"]** (list of item ids) skips STAC; `sources` always gets `{"buildings": [ids], "gwr": id}`,
  also for cells without buildings, so a rebuild from the manifest never picks newer sheets.
- **Geometry → spans.** Every FGDB polygon is a single triangle; Building_solid and Roof_solid meshes are closed.
  `voxel.voxelise` casts a vertical ray through each 1 m column centre (jittered by ~1e-6 m to avoid hitting shared
  edges/vertices twice) and pairs crossings by parity; columns with an odd count are dropped and counted
  (Interlaken sheet: 148 of ~750k).
  - Body spans (part 0) come from Building_solid.
  - Roof_solid is a ~0.2 m roof skin linked to the body by GEBAEUDEEINHEIT (not UUID); several bodies can share a
    unit, so a skin goes to the unit's body with the nearest centroid. Skins with no body ("orphans", 37 on the
    Interlaken sheet) are dropped and counted.
  - Skin columns where no body of the unit has a span → overhang span (part 1).
  - Skin resting on the body (underside within 0.5 m of the body top) raises that body span to the skin top, so
    roofs end at the real roof surface; a skin clearly above the body top in that column becomes its own body span;
    a skin below the body top is dropped.
  - part 2 (annex) is not produced yet.
- **Spans in a column are disjoint** (not stated in swd-format.md §6; proposal: make it a rule). Overlapping
  solids exist in the data (Interlaken cell: 1,248 overlapping pairs, 995 of them body-in-neighbour-body, median
  10 m). `buildings.disjoint` resolves them bottom-up per column: a span starting inside the one below starts
  at its top, or is dropped if fully contained. No BTBL row lost all its spans in the Interlaken cell.
- **roofFlags bit1 (half step)** is set when the span top in dm ends in 3..7 (i.e. within ±0.2 m of x.5).
- **BTBL** rows are sorted by gid; `baseH` = lowest body point, `topH` = highest point of body and roof skins;
  `entranceSide` = 0 (not derived yet). `floors` = GASTW.
- **GWR (gwr.py)**: only canton BE (`CANTONS = ("be",)`) because the test bbox lies in BE. For the whole country
  switch to `ch.zip` (433 MB) or add cantons.

## GWR source

- URL: `https://public.madd.bfs.admin.ch/be.zip` (also `<canton>.zip`, `ch.zip`); 123,783,089 bytes
  (Last-Modified 2026-10-19 03:09 GMT). Contains `gebaeude_batiment_edificio.csv` (TSV, UTF-8),
  `eingang_entree_entrata.csv`, `license.pdf`.
- Licence (license.pdf): free use, commercial use allowed, attribution mandatory:
  "Bundesamt für Statistik; Eidg. Gebäude- und Wohnungsregister" (Art. 16 VGWR, SR 431.841, access level A).
  → needs to go into the world's attribution/credits.
- The file is regenerated daily and **old snapshots cannot be re-downloaded**. `source_id` = `gwr_be_<GEXPDAT>`
  (e.g. `gwr_be_2026-10-19`) records which snapshot was used, but a rebuild after the raw cache is cleared will
  get a newer one. For strict reproducibility the raw be.zip must be kept (or archived per manifest).

## Results (evidence)

- `build.assemble(2632, 1170, cfg)` with the real BUILDERS list → sources
  `{"buildings": [4 item ids], "gwr": "gwr_be_2026-10-19"}`; feeding them back as `pinned` (JSON round trip)
  gives identical BTBL/BSPN. `swd.encode`/`decode` round-trips the cell (833 kB with a dummy TERR — there is no
  terrain builder on this branch, so `assemble` alone cannot produce an encodable cell here).
  Note: `assemble` merges sources flat (`dict.update`), so the top-level keys `buildings` and `gwr` are taken.
- `uv run --group dev pytest -q` → all pass (synthetic tests for voxeliser, buildings, gwr; real-data acceptance
  `sidecar/tests/test_interlaken.py`, skipped when the raw files are not cached, never uses the network).
- Cell 2632_1170 (Interlaken), sheets pinned to the 2021 versions:
  - BTBL 959 = solids whose projected Building_solid footprint intersects the cell (shapely reference, 959);
    one footprint sliver of 0.78 m² has no column centre in the cell, one building is in only via its eaves.
  - Span volume 2,216,797 m³ vs Building_solid + Roof_solid volume 2,244,692 m³ (−1.24 %).
  - GWR class for 877 / 949 buildings with an EGID (92.4 %); 10 buildings have no EGID.
  - Hotel Victoria-Jungfrau (EGID 1346414, GKLAS 1211 hotel): BTBL topH 604.5 m vs DACH_MAX 604.53 m; highest
    span top 603.6 m (−0.93 m — the apex of a pointed tower roof lies between column centres; passes ±1 m with
    only 7 cm margin). Its bbox crosses the west cell border (minX < 0), as expected.
  - 200,507 columns, 206,655 spans for the cell.
- Timing (M-series Mac): Interlaken sheet 1208-43 (3,643 bodies, 5,918 roof skins, 580k triangles):
  fiona read 5.7 s, voxelise + assemble 3.5 s. Cold build of the cell incl. 4 sheets ≈ 21 s; with sheet caches
  2.9 s total (cell assembly 0.9 s, STAC 0.2–0.7 s).
- Preview: `swiss-world/.data-cache/previews/buildings_2632_1170.png` (`python -m swissdata.buildings 2632 1170`),
  grey = roof height, orange = overhang-only columns.

## Downloads (all via `fetch`, all in the test bbox)

- swissBUILDINGS3D sheets: 1208-43 (19.2 MB), 1208-44 (13.2 MB), 1228-21 (11.1 MB), 1228-22 (2.1 MB)
- GWR be.zip 123.8 MB. Total ≈ 170 MB.

## Files outside the owned paths

- `sidecar/swissdata/build.py`: import of `BuildingsBuilder` and its registration in `BUILDERS` (required by the
  brief). `buildings.py` imports `LayerResult` lazily inside `build()` to avoid the circular import.

## Left for later

- part 2 (annex), entranceSide (could come from the GWR entrance table `eingang_entree_entrata.csv`, which has
  coordinates), building-part split by GEBAEUDE_NUTZUNG.
- Thin-feature sampling: spires/pointed roofs lose up to ~1 m at their apex with centre sampling; a max-z per
  column pass for roof skins would fix it at the cost of volume accuracy.
- GWR for other cantons / ch.zip.
