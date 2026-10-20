# NOTES mod-siteplan

Owner of `mod/src/main/java/ch/bissbert/swissworld/gen/site/`: `SitePlan`, `Claim`, `GenStats`, `WriteTrace`,
`SiteCommands` (and the site-plan cache / write guard once they land).

## Read API for other stages (mod-texture: GroundTextureStage)

```java
SitePlan site = ctx.site();          // never null in the game; the tile holding ctx.chunk
Claim c   = site.claim(x, z);        // who owns the column
int   g   = site.ground(x, z);       // G: final top solid block y of the column
int   d   = site.distanceTo(Claim.ROAD, x, z); // Chebyshev columns, 0..16
```

- **Validity.** `claim` and `ground`: every column of the tile plus `SitePlan.PAD` (16) around it, so any chunk column
  and its 16 neighbours. `distanceTo`: the tile's own columns (all columns of every chunk in it). Outside: IOOBE.
- **Claim** (`gen/site/Claim.java`), one per column, ordinal = resolution priority, ordinals stable (new ones appended):
  `WATER, BANK, RAIL, ROAD, PATH, STRUCTURE, BUILDING, LANDING, EARTHWORK, FREE`.
  - WATER: any column with water (lake/river/stream/reservoir/pond/fall), and columns outside every cell (ocean).
  - BANK: dry ground raised to adjacent water (`WaterPlan.bankTop`).
  - RAIL / ROAD / PATH: the way's deck (on ground, embankment, bridge, over a tunnel portal). PATH = OCLS 5, 6, 7, 13;
    RAIL = rail bed, rack bed, rail under a road bridge; everything else ROAD.
  - STRUCTURE: bridge/tunnel/dam parts that are not the walking deck (tunnel lining near the surface, walls, dam
    crest/body, piers). A bore deeper than `TreePlan.TUNNEL_COVER` (10) under the terrain leaves the surface FREE.
  - BUILDING: building footprint. LANDING: door landing, way out, steps (lands with the building step).
  - EARTHWORK: ground cut or filled to the way next to it (`TransportPlan.earthwork`).
  - FREE: natural ground.
- **G** (`ground`) = top solid block after the site is built: FREE = terrain; BANK = raised bank top; EARTHWORK = graded
  top; RAIL/ROAD/PATH = deck block; WATER = bed (top of the ground under the water); STRUCTURE = deck/crest/lining top;
  BUILDING = ground under it; LANDING = walking level.
- **distanceTo** = Chebyshev distance (max(|dx|,|dz|)) to the nearest column with that claim, `0` on it, capped at
  `SitePlan.FAR` = 16 meaning "none within 15". Computed lazily per claim per tile (one separable transform, ~0.1 ms).
- **Mapping for mod-texture's `Ground`**: `present`/`metres`/`surf`/... stay on `ctx.site().grid()` (a `ColumnGrid` over
  the tile + `SitePlan.MARGIN`); re-texture only where `claim` is FREE or EARTHWORK, at y = `ground(x, z)`.
- Every value is a pure function of the cell data near the column, keyed by world position: neighbouring tiles agree
  (`SitePlanTest.neighbouringTilesAgreeOnTheirOverlap`).

## Tile

128 x 128 columns, chunk aligned (8 x 8 chunks), `SitePlan.tile(x) = floorDiv(x, 128)`. Margin of data around it:
`SitePlan.MARGIN` = PAD 16 + `TransportPlan.MARGIN` 36 = 52. Per chunk the same margin would sample (16+104)²/16² = 56
columns per column; per tile (128+104)²/128² = 3.3. A 1 km cell is not chunk aligned and holds ~3,900 chunks.
