"""Reference reader/writer for the .swd cell format (docs/swd-format.md)."""

from __future__ import annotations

import os
import struct
import zlib
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

MAGIC = b"SWD1"
FORMAT_VERSION = 1
NO_HEIGHT = 0xFFFF
CELL = 1000
TERR_N = 501

FLAG_BORDER = 1

CODEC_RAW = 0
CODEC_ZLIB = 1

OFLG_BRIDGE = 1
OFLG_TUNNEL = 2
OFLG_RAIL = 4
OFLG_EDGE = 8
OFLG_DAM = 16
OFLG_MAIN = 32  # rail centre (bit2) on the main line of its railway
OFLG_TRUNK = 64  # main-line centre (bit5) on its network's trunk: the line kept where two main lines merge

_HEADER = struct.Struct("<4sHHiiII")
_DIR = struct.Struct("<4sIQQQ")
_BTBL_ENTRY = struct.Struct("<QQHHHHBBHHHiiii")
_SPAN = struct.Struct("<HHHBB")
_COL = struct.Struct("<HHB")
_TTOP_ENTRY = struct.Struct("<HHBBBB")

U16_LAYERS = {"TERR", "WLVL", "OELV"}
U8_LAYERS = {"SURF", "WKND", "OCLS", "OFLG", "INSD", "CNPY", "REGN", "LITH", "TOPO", "HABT"}

KIND_UNKNOWN = 0
KIND_CONIFER = 1
KIND_BROADLEAF = 2
KIND_LARCH = 3


def encode_height(h_m: np.ndarray | float) -> np.ndarray:
    """Metres -> u16 decimetre encoding (offset -200 m). NaN -> NO_HEIGHT."""
    h = np.asarray(h_m, dtype=np.float64)
    raw = np.rint((h + 200.0) * 10.0)
    raw = np.where(np.isnan(raw), NO_HEIGHT, np.clip(raw, 0, NO_HEIGHT - 1))
    return raw.astype("<u2")


def decode_height(raw: np.ndarray | int) -> np.ndarray:
    r = np.asarray(raw, dtype=np.float64)
    return np.where(r == NO_HEIGHT, np.nan, r / 10.0 - 200.0)


@dataclass
class Building:
    gid: int
    egid: int = 0
    objektart: int = 0
    gwr_category: int = 0
    gwr_class: int = 0
    gwr_period: int = 0
    floors: int = 0
    entrance_side: int = 0
    base_h: int = NO_HEIGHT  # encoded u16
    top_h: int = NO_HEIGHT  # encoded u16
    min_x: int = 0
    min_z: int = 0
    max_x: int = 0
    max_z: int = 0


@dataclass
class Span:
    bottom_h: int  # encoded u16
    top_h: int  # encoded u16
    building_idx: int
    part: int = 0
    roof_flags: int = 0


@dataclass
class Tree:
    dx: int
    dz: int
    height_m: int  # above TERR at the top column, 2..255
    crown_dm: int = 0  # crown radius in decimetres, 0 = unknown
    kind: int = KIND_UNKNOWN


@dataclass
class Cell:
    e_km: int
    n_km: int
    layers: dict[str, np.ndarray] = field(default_factory=dict)
    buildings: list[Building] = field(default_factory=list)
    # (dx, dz) -> spans
    spans: dict[tuple[int, int], list[Span]] = field(default_factory=dict)
    # None = no TTOP (the mod places trees statistically); [] = canopy data, but no trees in this cell
    trees: list[Tree] | None = None

    @property
    def border(self) -> bool:
        return "INSD" in self.layers


def gid_from_uuid(uuid: str) -> int:
    import hashlib

    return int.from_bytes(hashlib.sha1(uuid.encode("utf-8")).digest()[:8], "little")


def _encode_btbl(buildings: list[Building]) -> bytes:
    out = bytearray(struct.pack("<I", len(buildings)))
    for b in buildings:
        out += _BTBL_ENTRY.pack(
            b.gid, b.egid, b.objektart, b.gwr_category, b.gwr_class, b.gwr_period,
            b.floors, b.entrance_side, b.base_h, b.top_h, 0,
            b.min_x, b.min_z, b.max_x, b.max_z,
        )
    return bytes(out)


def _decode_btbl(data: bytes) -> list[Building]:
    (n,) = struct.unpack_from("<I", data, 0)
    res = []
    for i in range(n):
        v = _BTBL_ENTRY.unpack_from(data, 4 + i * _BTBL_ENTRY.size)
        res.append(Building(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[11], v[12], v[13], v[14]))
    return res


def _encode_bspn(spans: dict[tuple[int, int], list[Span]]) -> bytes:
    keys = sorted(spans, key=lambda k: (k[1], k[0]))
    out = bytearray(struct.pack("<I", len(keys)))
    for dx, dz in keys:
        col = spans[(dx, dz)]
        out += _COL.pack(dx, dz, len(col))
        for s in col:
            out += _SPAN.pack(s.bottom_h, s.top_h, s.building_idx, s.part, s.roof_flags)
    return bytes(out)


def _decode_bspn(data: bytes) -> dict[tuple[int, int], list[Span]]:
    (n,) = struct.unpack_from("<I", data, 0)
    off = 4
    res: dict[tuple[int, int], list[Span]] = {}
    for _ in range(n):
        dx, dz, cnt = _COL.unpack_from(data, off)
        off += _COL.size
        col = []
        for _ in range(cnt):
            col.append(Span(*_SPAN.unpack_from(data, off)))
            off += _SPAN.size
        res[(dx, dz)] = col
    return res


def _encode_ttop(trees: list[Tree]) -> bytes:
    out = bytearray(struct.pack("<I", len(trees)))
    for t in sorted(trees, key=lambda t: (t.dz, t.dx)):
        out += _TTOP_ENTRY.pack(t.dx, t.dz, t.height_m, t.crown_dm, t.kind, 0)
    return bytes(out)


def _decode_ttop(data: bytes) -> list[Tree]:
    (n,) = struct.unpack_from("<I", data, 0)
    return [Tree(*_TTOP_ENTRY.unpack_from(data, 4 + i * _TTOP_ENTRY.size)[:5]) for i in range(n)]


def _layer_bytes(tag: str, arr: np.ndarray) -> bytes:
    if tag in U16_LAYERS:
        expected = (TERR_N, TERR_N) if tag == "TERR" else (CELL, CELL)
        a = np.ascontiguousarray(arr, dtype="<u2")
    elif tag in U8_LAYERS:
        expected = (CELL, CELL)
        a = np.ascontiguousarray(arr, dtype="u1")
    else:
        raise ValueError(f"unknown array layer {tag}")
    if a.shape != expected:
        raise ValueError(f"{tag}: shape {a.shape} != {expected}")
    return a.tobytes()


def encode(cell: Cell) -> bytes:
    if "TERR" not in cell.layers or "SURF" not in cell.layers:
        raise ValueError("TERR and SURF are required")
    blobs: list[tuple[str, bytes]] = []
    order = ["TERR", "SURF", "WLVL", "WKND", "OCLS", "OELV", "OFLG", "INSD", "CNPY", "REGN", "LITH", "TOPO", "HABT"]
    for tag in order:
        if tag in cell.layers:
            blobs.append((tag, _layer_bytes(tag, cell.layers[tag])))
    if cell.buildings:
        blobs.append(("BTBL", _encode_btbl(cell.buildings)))
        blobs.append(("BSPN", _encode_bspn(cell.spans)))
    if cell.trees is not None:
        blobs.append(("TTOP", _encode_ttop(cell.trees)))
    flags = FLAG_BORDER if "INSD" in cell.layers else 0
    header = _HEADER.pack(MAGIC, FORMAT_VERSION, flags, cell.e_km, cell.n_km, len(blobs), 0)
    offset = _HEADER.size + _DIR.size * len(blobs)
    directory = bytearray()
    payload = bytearray()
    for tag, raw in blobs:
        # level 6 + no dictionary: deterministic output for identical input
        stored = zlib.compress(raw, 6)
        directory += _DIR.pack(tag.encode("ascii"), CODEC_ZLIB, offset, len(stored), len(raw))
        payload += stored
        offset += len(stored)
    return header + bytes(directory) + bytes(payload)


def decode(data: bytes) -> Cell:
    magic, version, flags, e_km, n_km, count, _ = _HEADER.unpack_from(data, 0)
    if magic != MAGIC:
        raise ValueError("bad magic")
    if version != FORMAT_VERSION:
        raise ValueError(f"unsupported formatVersion {version}")
    cell = Cell(e_km, n_km)
    for i in range(count):
        tag_b, codec, off, stored_len, raw_len = _DIR.unpack_from(data, _HEADER.size + i * _DIR.size)
        tag = tag_b.decode("ascii")
        blob = data[off:off + stored_len]
        if codec == CODEC_ZLIB:
            blob = zlib.decompress(blob)
        elif codec != CODEC_RAW:
            raise ValueError(f"unknown codec {codec}")
        if len(blob) != raw_len:
            raise ValueError(f"{tag}: length mismatch")
        if tag == "TERR":
            cell.layers[tag] = np.frombuffer(blob, dtype="<u2").reshape(TERR_N, TERR_N)
        elif tag in U16_LAYERS:
            cell.layers[tag] = np.frombuffer(blob, dtype="<u2").reshape(CELL, CELL)
        elif tag in U8_LAYERS:
            cell.layers[tag] = np.frombuffer(blob, dtype="u1").reshape(CELL, CELL)
        elif tag == "BTBL":
            cell.buildings = _decode_btbl(blob)
        elif tag == "BSPN":
            cell.spans = _decode_bspn(blob)
        elif tag == "TTOP":
            cell.trees = _decode_ttop(blob)
        # unknown tags are ignored by contract
    return cell


def cell_path(store: Path, e_km: int, n_km: int) -> Path:
    return Path(store) / "cells" / f"{e_km}_{n_km}.swd"


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


# --- coverage.bin -----------------------------------------------------------

_COV_HEADER = struct.Struct("<4sHHiiII")


def encode_coverage(e_min_km: int, n_min_km: int, grid: np.ndarray) -> bytes:
    """grid[n - nMin, e - eMin] in {0 outside, 1 border, 2 inside}."""
    h, w = grid.shape
    return _COV_HEADER.pack(b"SWCV", 1, 0, e_min_km, n_min_km, w, h) + np.ascontiguousarray(grid, dtype="u1").tobytes()


def decode_coverage(data: bytes) -> tuple[int, int, np.ndarray]:
    magic, version, _, e_min, n_min, w, h = _COV_HEADER.unpack_from(data, 0)
    if magic != b"SWCV" or version != 1:
        raise ValueError("bad coverage file")
    grid = np.frombuffer(data, dtype="u1", offset=_COV_HEADER.size, count=w * h).reshape(h, w)
    return e_min, n_min, grid
