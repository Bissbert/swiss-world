"""Shared helpers for the acceptance sweep (run with the sidecar's uv env: numpy, rasterio).

    uv run --project sidecar python tools/acceptance/<script>.py ...

Contents: RCON client, LV95 <-> block coordinates, .swd cell oracle (TERR/WLVL/BTBL via sidecar/swissdata/swd.py),
swissALTI3D COG sampling, and an Anvil (.mca/.mcc) reader with palette, heightmap and block-entity decoding.
"""
from __future__ import annotations

import glob
import gzip
import hashlib
import io
import math
import os
import socket
import struct
import sys
import time
import zlib
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "sidecar"))
from swissdata import swd  # noqa: E402

E0 = int(os.environ.get("ACC_ORIGIN_E", 2632900))
N0 = int(os.environ.get("ACC_ORIGIN_N", 1170600))
MIN_Y = -2032
HEIGHT = 4064
DIM = "swissworld:switzerland"
RAW = Path(os.environ.get("SWISSDATA_RAW",
                          Path(__file__).resolve().parents[2] / ".data-cache" / "raw"))


# ---------------------------------------------------------------- coordinates
def block_of(e: float, n: float) -> tuple[int, int]:
    """LV95 metres -> block x, z (column containing the point)."""
    return math.floor(e - E0), math.floor(N0 - n)


def lv95_of(x: int, z: int) -> tuple[float, float]:
    """Block column centre -> LV95."""
    return E0 + x + 0.5, N0 - z - 0.5


def y_of(metres: float) -> float:
    return metres - 2000 if metres <= 3000 else 1000 + (metres - 3000) * 0.6


# ---------------------------------------------------------------- rcon
class Rcon:
    def __init__(self, port: int | None = None, password: str | None = None):
        self.port = int(port or os.environ.get("ACC_RCON_PORT", 25711))
        self.password = password or os.environ.get("ACC_RCON_PASS", "dev")
        self._connect()

    def _connect(self):
        self.s = socket.create_connection(("127.0.0.1", self.port), timeout=600)
        self._pkt(3, self.password)

    def _recv(self, n):
        b = b""
        while len(b) < n:
            c = self.s.recv(n - len(b))
            if not c:
                raise ConnectionError("rcon closed")
            b += c
        return b

    def _pkt(self, t, body):
        d = struct.pack("<ii", 7, t) + body.encode() + b"\0\0"
        self.s.sendall(struct.pack("<i", len(d)) + d)
        n = struct.unpack("<i", self._recv(4))[0]
        return self._recv(n)[8:-2].decode(errors="replace")

    def __call__(self, cmd: str) -> str:
        try:
            return self._pkt(2, cmd)
        except (ConnectionError, OSError, socket.timeout):
            time.sleep(1)
            self._connect()
            return self._pkt(2, cmd)

    def inside(self, cmd: str) -> str:
        return self(f"execute in {DIM} run {cmd}")


# ---------------------------------------------------------------- cells (sidecar output = oracle)
_cells: dict = {}


def store() -> Path:
    return Path(os.environ["ACC_STORE"])


def cell(e_km: int, n_km: int):
    k = (e_km, n_km)
    if k not in _cells:
        p = swd.cell_path(store(), e_km, n_km)
        _cells[k] = swd.decode(p.read_bytes()) if p.exists() else None
    return _cells[k]


def cell_xy(e: float, n: float):
    """(cell, dx, dz) of the column containing LV95 (e, n)."""
    ek, nk = math.floor(e / 1000), math.floor(n / 1000)
    return cell(ek, nk), math.floor(e) - ek * 1000, 999 - (math.floor(n) - nk * 1000)


def terr_m(c, fx: float, fz: float) -> float:
    """Bilinear TERR at (fx, fz) metres from the cell's NW corner (same as SwdCell.terrain)."""
    t = c.layers["TERR"]
    gx, gz = fx / 2.0, fz / 2.0
    i, j = min(int(gx), 499), min(int(gz), 499)
    u, v = gx - i, gz - j
    q = swd.decode_height(np.array([t[j, i], t[j, i + 1], t[j + 1, i], t[j + 1, i + 1]]))
    return float(q[0] * (1 - u) * (1 - v) + q[1] * u * (1 - v) + q[2] * (1 - u) * v + q[3] * u * v)


def terr_at_block(x: int, z: int) -> float:
    e, n = lv95_of(x, z)
    c, dx, dz = cell_xy(e, n)
    return terr_m(c, dx + 0.5, dz + 0.5) if c is not None else float("nan")


# ---------------------------------------------------------------- swissALTI3D (independent reference)
_alti_cache: dict = {}


def alti_m(e: float, n: float) -> float:
    """swissALTI3D 2 m COG value (nearest pixel) at LV95 (e, n), from the shared raw cache; NaN if not cached."""
    import rasterio
    key = (math.floor(e / 1000), math.floor(n / 1000))
    if key not in _alti_cache:
        hits = sorted(glob.glob(str(RAW / "data.geo.admin.ch/ch.swisstopo.swissalti3d"
                                    / f"swissalti3d_*_{key[0]}-{key[1]}" / "*_2_2056_5728.tif")))
        _alti_cache[key] = rasterio.open(hits[-1]) if hits else None
    ds = _alti_cache[key]
    if ds is None:
        return float("nan")
    r, c = ds.index(e, n)
    return float(ds.read(1, window=((r, r + 1), (c, c + 1)))[0, 0])


# ---------------------------------------------------------------- NBT
def _nbt(buf: io.BytesIO, t: int):
    r = buf.read
    if t == 1: return struct.unpack(">b", r(1))[0]
    if t == 2: return struct.unpack(">h", r(2))[0]
    if t == 3: return struct.unpack(">i", r(4))[0]
    if t == 4: return struct.unpack(">q", r(8))[0]
    if t == 5: return struct.unpack(">f", r(4))[0]
    if t == 6: return struct.unpack(">d", r(8))[0]
    if t == 7:
        n = struct.unpack(">i", r(4))[0]; return r(n)
    if t == 8:
        n = struct.unpack(">H", r(2))[0]; return r(n).decode("utf-8", "replace")
    if t == 9:
        et, n = struct.unpack(">bi", r(5)); return [_nbt(buf, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            et = r(1)[0]
            if et == 0: return d
            nl = struct.unpack(">H", r(2))[0]; name = r(nl).decode("utf-8", "replace")
            d[name] = _nbt(buf, et)
    if t == 11:
        n = struct.unpack(">i", r(4))[0]; return np.frombuffer(r(4 * n), ">i4")
    if t == 12:
        n = struct.unpack(">i", r(4))[0]; return np.frombuffer(r(8 * n), ">i8")
    raise ValueError(f"nbt tag {t}")


def nbt_root(data: bytes):
    buf = io.BytesIO(data)
    t = buf.read(1)[0]
    nl = struct.unpack(">H", buf.read(2))[0]; buf.read(nl)
    return _nbt(buf, t)


# ---------------------------------------------------------------- Anvil
def region_dir() -> Path:
    return Path(os.environ["ACC_DIM"]) / "region"


def chunk_nbt(cx: int, cz: int, rdir: Path | None = None):
    rdir = rdir or region_dir()
    p = rdir / f"r.{cx >> 5}.{cz >> 5}.mca"
    if not p.exists():
        return None
    with open(p, "rb") as f:
        f.seek(4 * ((cx & 31) + 32 * (cz & 31)))
        loc = struct.unpack(">I", f.read(4))[0]
        off, cnt = loc >> 8, loc & 0xFF
        if off == 0:
            return None
        f.seek(off * 4096)
        length, comp = struct.unpack(">IB", f.read(5))
        payload = f.read(length - 1)
    if comp & 0x80:
        payload = (rdir / f"c.{cx}.{cz}.mcc").read_bytes()
        comp &= 0x7F
    if comp == 1: data = gzip.decompress(payload)
    elif comp == 2: data = zlib.decompress(payload)
    elif comp == 3: data = payload
    else: raise ValueError(f"compression {comp}")
    return nbt_root(data)


def unpack(longs: np.ndarray, bits: int, count: int) -> np.ndarray:
    per = 64 // bits
    words = longs.astype(np.uint64) if longs.dtype != np.uint64 else longs
    idx = np.arange(count)
    w = words[idx // per]
    sh = ((idx % per) * bits).astype(np.uint64)
    return ((w >> sh) & np.uint64((1 << bits) - 1)).astype(np.int64)


def state_name(p: dict) -> str:
    props = p.get("Properties")
    if not props:
        return p["Name"]
    return p["Name"] + "[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]"


class Chunk:
    def __init__(self, cx: int, cz: int, root: dict):
        self.cx, self.cz, self.root = cx, cz, root
        self.status = root.get("Status")
        self.sections = {}
        for s in root.get("sections", []):
            bs = s.get("block_states")
            if not bs:
                continue
            pal = [state_name(p) for p in bs["palette"]]
            self.sections[s["Y"]] = (pal, bs.get("data"))
        self._dec = {}

    def section(self, sy: int) -> tuple[list[str], np.ndarray | None]:
        """(palette, indices[4096] or None if uniform), index = y*256 + z*16 + x."""
        if sy not in self.sections:
            return ["minecraft:air"], None
        if sy not in self._dec:
            pal, data = self.sections[sy]
            if data is None or len(pal) == 1:
                self._dec[sy] = (pal, None)
            else:
                bits = max(4, math.ceil(math.log2(len(pal))))
                self._dec[sy] = (pal, unpack(data, bits, 4096))
        return self._dec[sy]

    def block(self, lx: int, y: int, lz: int) -> str:
        pal, idx = self.section(y >> 4)
        if idx is None:
            return pal[0]
        return pal[int(idx[((y & 15) << 8) | (lz << 4) | lx])]

    def heightmap(self, name: str = "WORLD_SURFACE") -> np.ndarray:
        """Top non-air block y per column [z, x] (vanilla heightmap value - 1)."""
        hm = self.root["Heightmaps"][name]
        bits = math.ceil(math.log2(HEIGHT + 1))
        return (unpack(hm, bits, 256).reshape(16, 16) + MIN_Y - 1)

    def column(self, lx: int, lz: int, y_top: int, y_bot: int):
        """Yield (y, name) from y_top down to y_bot inclusive."""
        for y in range(y_top, y_bot - 1, -1):
            yield y, self.block(lx, y, lz)

    def block_entities(self) -> list[dict]:
        return self.root.get("block_entities", [])

    def section_hashes(self) -> dict[int, str]:
        out = {}
        for sy in sorted(self.sections):
            pal, idx = self.section(sy)
            h = hashlib.sha1()
            if idx is None:
                h.update(pal[0].encode())
            else:
                names = np.array(pal, dtype=object)[idx]
                h.update("\n".join(names.tolist()).encode())
            out[sy] = h.hexdigest()[:16]
        return out


def load_chunk(cx: int, cz: int, rdir: Path | None = None) -> Chunk | None:
    r = chunk_nbt(cx, cz, rdir)
    return Chunk(cx, cz, r) if r is not None else None


NATURAL_SKIP = ("log", "leaves", "snow", "grass", "fern", "flower", "bush", "torch", "lantern", "sapling",
                "_wood", "vine", "air", "water", "rail", "carpet", "button", "sign", "fence", "wall")


def is_natural_ground(name: str) -> bool:
    n = name.split("[")[0].removeprefix("minecraft:")
    if n in ("snow_block", "powder_snow", "grass_block", "short_grass", "tall_grass") :
        return n in ("snow_block", "powder_snow", "grass_block")
    return not any(k in n for k in NATURAL_SKIP) and not name.startswith("swissworld:")


_chunk_cache: dict = {}


def chunk_at(x: int, z: int) -> Chunk | None:
    k = (x >> 4, z >> 4)
    if k not in _chunk_cache:
        if len(_chunk_cache) > 512:
            _chunk_cache.clear()
        c = load_chunk(*k)
        _chunk_cache[k] = c if c is not None and c.status == "minecraft:full" else None
    return _chunk_cache[k]


def block_at(x: int, y: int, z: int) -> str | None:
    c = chunk_at(x, z)
    return None if c is None else c.block(x & 15, y, z & 15)


def top_at(x: int, z: int, name: str = "WORLD_SURFACE") -> int | None:
    c = chunk_at(x, z)
    return None if c is None else int(c.heightmap(name)[z & 15, x & 15])


def ground_at(x: int, z: int) -> tuple[int, str] | None:
    """Highest natural ground block (skips trees, snow layers, plants, buildings above via scan from heightmap)."""
    c = chunk_at(x, z)
    if c is None:
        return None
    top = int(c.heightmap()[z & 15, x & 15])
    for y, b in c.column(x & 15, z & 15, top, top - 80):
        if is_natural_ground(b):
            return y, b
    return None
