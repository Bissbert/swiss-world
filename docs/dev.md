# Developer guide (for humans and agents)

## Shared paths: always absolute, shared by all worktrees

```sh
export SWISSDATA_RAW=<repo>/.data-cache/raw
export SWISSDATA_STORE=<repo>/store
export SWISSWORLD_STORE=$SWISSDATA_STORE          # read by mod runServer (system property swissworld.store)
```

- **Raw cache.**
  - Upstream files are stored at `$SWISSDATA_RAW/<host>/<path>`.
  - Always download through `swissdata.fetch.fetch(cfg, url)`. It handles the lock, `.part` and atomic rename, so parallel agents never fetch the same file twice.
- **Store.** It holds `cells/*.swd`, `coverage.bin` and `manifest.json`, plus `gwr/` (archived GWR snapshots: never evicted, and must be kept and backed up with the store).
  - Only `sidecar-core` code writes cells and the manifest.
  - Builders contribute layers through `swissdata/build.py`.
- **Never download:**
  - whole-country `swissbuildings3d_3_0_20xx` items (14 GB);
  - more than the test bbox (WGS84 7.80–8.39 E, 46.56–46.80 N).
  - Only `sidecar-tlm` downloads the swissTLM3D national file, once.

## Coordinates cheat sheet

| Item | Value |
|---|---|
| Default origin | E 2,632,900 / N 1,170,600 (Interlaken Höhematte) |
| Block → LV95 | `x = E − E0`, `z = N0 − N − 1` (see `SwissCoords`) |
| Height → y | `y = h − 2000` up to 3000 m, then `1000 + (h − 3000)·0.6` |
| Sea level / ocean surface | y −1807 |
| Fixture cell `2632_1170` | covers x −900…99, z −400…599 |

- **Fixture building:** dx 100–109, dz 100–107. That is x −800…−791, z −300…−293, with the base at 610 m (y −1390).
- `SwdCell.terrain(fx, fz)` takes metres from the cell's NW corner. **Sample column centres:** `terrain(dx + 0.5, dz + 0.5)`.

## Mod

```sh
cd mod && ./gradlew build        # first run in a fresh worktree: ~2 min NeoForge setup; not a hang
./gradlew runServer              # uses mod/run/; needs run/eula.txt and run/server.properties
```

**Fixture world:**

**Never put fixture or test cells into the shared store** (`$SWISSDATA_STORE`). Cell 2632_1170 is the real origin cell. Always use a private store per branch:

```sh
export SWISSWORLD_STORE=/tmp/swissworld-<branch>      # private; overrides the shared store for runServer
mkdir -p $SWISSWORLD_STORE/cells && cp fixtures/synthetic_2632_1170.swd $SWISSWORLD_STORE/cells/2632_1170.swd
```

Only sidecar-core writes to the shared store. A cell counts as built only if `manifest.json` has an entry for it, not merely because the file exists.

**Smoke test over RCON.** Gradle does not forward stdin, so use RCON. In `mod/run/server.properties`:

```
online-mode=false
server-port=256N0
enable-rcon=true
rcon.port=256N1
rcon.password=dev
level-name=dev
```

Here N is your workstream number: 1 sidecar-core (no server), 4 mod-core, 5 mod-content, 6 mod-surface.

```sh
python3 -I tools/rcon.py 256N1 dev "execute in swissworld:switzerland run forceload add -800 -300" \
  "execute in swissworld:switzerland if block -800 -1389 -300 <expected_block>" "stop"
```

- Delete `mod/run/dev` (the world) after changing generation. Already generated chunks are never regenerated.
- Wait for the RCON port to accept connections. Don't grep `latest.log`; it may be from the previous run.

## Sidecar

```sh
cd sidecar && uv run --group dev pytest -q
uv run python -m swissdata.fixture ../fixtures     # only when the format changes (coordinated)
```

- Python 3.12 via uv. Geodata comes from wheels only: **fiona** for FGDB (pyogrio fails on TIN Z, type 1016), rasterio for COGs, shapely, numpy.
- There is no system GDAL. Open FGDB zips as `zip://<file.gdb.zip>!<name>.gdb`.
- Run scripts that read downloaded data with `python -I`. Keep scripts out of data directories.

## Git

- One worktree and branch per workstream. Commit often to **your branch only**, and never push.
- Contract files need coordinator approval:
  - `docs/swd-format.md`, `docs/sidecar-api.md`;
  - `swd.py`, `SwdReader.java`, `SwdCell.java`;
  - `fixtures/`, `build.py`'s protocol.
- After the coordinator merges something to `main`: `git rebase main`. Don't merge `main` into your branch.
- Commit message trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Off limits:**
  - the production server;
  - writing to `~/minecraft-modpack` (reading is fine);
  - anything on the live server.
