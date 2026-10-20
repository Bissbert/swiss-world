# NOTES: sidecar-zones (REGN / LITH / TOPO / HABT)

Four new optional u8 layers (`u8[1000·1000]`, index `dz·1000 + dx`) feed the ground-zone classifier in
mod-texture. The full contract is in `docs/swd-format.md` §6b. This page is the short version for the mod.

## Contract change (additive, approved this round)

- New tags `REGN`, `LITH`, `TOPO`, `HABT`. The fourth tag is `HABT`, not `HAB`, because tags are 4 bytes.
  No version bump: older readers skip unknown tags.
- `swd.py` adds the four tags to `U8_LAYERS` and the encode order, after `CNPY`.
- `SwdReader` decodes them. `SwdCell` gets 4 new trailing record components (`regn`, `lith`, `topo`, `habt`).
  - The 12- and 14-argument constructors still exist and pass null.
  - Every accessor returns 0 when its layer is absent. 0 means: unknown region/lithology, mid-slope / no flow /
    neutral wind, no habitat. Old cells and the golden fixture therefore classify as "no extra information".
- `build.py`: `ZonesBuilder` ("zones") runs after `tlm-landcover`, because it reads `WKND`.
- Manifest: 5 new pinned source keys: `bioRegions`, `lithology`, `raisedBogs`, `fens`, `floodplains`
  (§7 lists pins, licences and sizes).

## Field layout (`SwdCell` accessors)

| Layer | Bits | Accessor | Values |
|---|---|---|---|
| REGN | 0–2 | `region(dx,dz)` | `REGION_*`: 1 Jura, 2 Mittelland, 3 Alps north flank, 4 W central Alps, 5 E central Alps, 6 south flank; 0 unknown |
| REGN | 3–5 | `regn(..) >>> 3 & 7` | last digit of the BAFU subregion (e.g. 32 → 2) |
| REGN | 6 | `regn(..) & REGN_FILLED` | outside all polygons; nearest region taken |
| LITH | 0–2 | `lithClass` | `LITH_*`: 1 limestone, 2 crystalline, 3 schist/flysch, 4 molasse, 5 quaternary; 0 unknown |
| LITH | 3 | `lith(..) & LITH_GLACIER` | GK500 glacier; class = nearest rock under the ice |
| LITH | 4 | `lith(..) & LITH_LAKE` | GK500 lake (class 5) |
| LITH | 5–7 | `lithDetail` | per-class detail, see the §6b table (e.g. limestone 3 = dolomite, crystalline 0 = granite, 3 = gneiss, molasse 2 = Nagelfluh) |
| TOPO | 0–1 | `tpi50` | `TPI_MID` 0, `TPI_VALLEY` 1, `TPI_FLAT` 2, `TPI_RIDGE` 3 (±2 m, 50 m window) |
| TOPO | 2–3 | `tpi300` | same codes (±20 m, 300 m window) |
| TOPO | 4–5 | `flow` | `FLOW_NONE`, `FLOW_GULLY` (≥ 1,000 m²), `FLOW_CHANNEL` (≥ 1 ha), `FLOW_HOLLOW` (wet hollow); 0 on water |
| TOPO | 6–7 | `wind` | `WIND_NEUTRAL`, `WIND_LEE` (snow accumulates), `WIND_EXPOSED` (blown free); winds W–NW |
| HABT | 0 | `habitat(..) & HAB_RAISED_BOG` | raised bog |
| HABT | 1 | `habitat(..) & HAB_BOG_MARGIN` | bog margin (Hochmoorumfeld) |
| HABT | 2 | `habitat(..) & HAB_FEN` | fen / wet meadow |
| HABT | 3–5 | `aue` | `AUE_*`: 1 glacier forefield, 2 alpine alluvial plain, 3 river floodplain, 4 delta, 5 lake shore |

`hasZones()` (REGN and LITH present), `hasTopo()` and `hasHabitats()` tell "absent" apart from "0".

## Notes for the classifier

- **Resolution.** TOPO comes in 2×2-column blocks (one 2 m ALTI pixel each). REGN, LITH and HABT are vector
  rasterisations at the column centre. GK500 is 1:500,000, so lithology borders are only accurate to ~250 m.
  Blend lithology across the border if hard edges look wrong.
- **Seams.** All four layers are pure functions of national data at the world position. TOPO reads ±320 m of
  raw DEM from the pinned neighbour tiles. Checked byte for byte on real data across the 2632/2633_1170 seam,
  and with a synthetic test (`test_topo_agrees_across_a_seam`).
- **Test-area distribution** (Interlaken–Grimsel, all region 3):
  - TPI300: ~45 % valley/ridge in steep cells, mostly flat on the Bödeli.
  - Flow: gully 2–5 %, channel ~1 %.
  - Wind: lee 25–50 %, exposed 10–20 %. Deep N–S valleys such as Lauterbrunnen are up to 70 % lee, because their
    west walls shelter the floor.
- **Glacier/lake flags.** LITH bit 3 says what rock lies under or around the ice, which helps for the
  glacier-forefield rock colour. Use it together with SURF/TLM glacier rather than instead of it.

## Licences (state the source)

- BAFU (biogeographic regions, mires, floodplains): opendata.swiss "Open use".
- swisstopo GK500: "Open use. Must provide the source" (© swisstopo).

## Store

The private store copy was augmented in place: decode, add the four layers, re-encode; the existing layers
are byte-identical. The coordinator still has to rebuild store-next. Plain rebuilds pick up the zones builder
automatically, and the five pins go into each cell's manifest `sources`.
