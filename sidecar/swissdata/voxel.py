"""Vertical ray-cast voxeliser: closed triangle solids -> per-column inside spans on the 1 m grid.

Columns are addressed by integer LV95 metres (Ei, Ni) = floor of the column centre, so the column centre is
(Ei + 0.5, Ni + 0.5) (docs/swd-format.md §1). All arithmetic runs in float64 relative to an integer origin.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

# Rays are cast a hair off the exact column centre so that they do not run through the mm-quantised
# vertices and shared edges of the source meshes (which would count one crossing twice).
_JITTER = (1.37e-6, 2.91e-6)
_MIN_AREA2 = 1e-6  # twice the projected area (m²) below which a triangle is a wall and never hit
_CHUNK = 4_000_000  # max candidate (triangle, column) pairs evaluated at once


def triangles(coords) -> np.ndarray:
    """fiona MultiPolygon Z coordinates (one triangle ring per polygon) -> float64 (T, 3, 3)."""
    rings = [ring[:3] for poly in coords for ring in poly[:1] if len(ring) >= 4]
    return np.asarray(rings, dtype=np.float64).reshape(-1, 3, 3)


def solid_volume(tris: np.ndarray) -> float:
    """Volume of a closed mesh (divergence theorem); orientation-independent."""
    if len(tris) == 0:
        return 0.0
    t = tris - tris.reshape(-1, 3).mean(axis=0)
    return abs(float(np.einsum("ij,ij->i", t[:, 0], np.cross(t[:, 1], t[:, 2])).sum()) / 6.0)


@dataclass
class Spans:
    """Flat span list. `solid` indexes the input solids; heights are metres (float64)."""

    solid: np.ndarray  # i4
    e: np.ndarray  # i8, column Ei
    n: np.ndarray  # i8, column Ni
    bottom: np.ndarray  # f8
    top: np.ndarray  # f8
    odd_columns: int = 0  # (solid, column) pairs dropped because of an odd crossing count

    def __len__(self) -> int:
        return len(self.solid)


def _hits(tris: np.ndarray, sid: np.ndarray, e0: int, n0: int):
    """All (solid, column, z) ray hits for triangles given relative to the integer origin (e0, n0)."""
    a, b, c = tris[:, 0], tris[:, 1], tris[:, 2]
    area2 = (b[:, 0] - a[:, 0]) * (c[:, 1] - a[:, 1]) - (c[:, 0] - a[:, 0]) * (b[:, 1] - a[:, 1])
    keep = np.abs(area2) > _MIN_AREA2
    a, b, c, area2, sid = a[keep], b[keep], c[keep], area2[keep], sid[keep]
    xs = np.stack([a[:, 0], b[:, 0], c[:, 0]], axis=1)
    ys = np.stack([a[:, 1], b[:, 1], c[:, 1]], axis=1)
    jx, jy = 0.5 + _JITTER[0], 0.5 + _JITTER[1]
    i0 = np.ceil(xs.min(axis=1) - jx).astype(np.int64)
    i1 = np.floor(xs.max(axis=1) - jx).astype(np.int64)
    j0 = np.ceil(ys.min(axis=1) - jy).astype(np.int64)
    j1 = np.floor(ys.max(axis=1) - jy).astype(np.int64)
    nx = np.maximum(i1 - i0 + 1, 0)
    ny = np.maximum(j1 - j0 + 1, 0)
    cnt = nx * ny
    out_s, out_i, out_j, out_z = [], [], [], []
    ends = np.cumsum(cnt)
    start = 0
    while start < len(cnt):
        # take triangles until the chunk budget is reached (at least one)
        base = ends[start - 1] if start else 0
        stop = max(start + 1, int(np.searchsorted(ends, base + _CHUNK, side="right")))
        sl = slice(start, stop)
        k = np.repeat(np.arange(start, stop), cnt[sl])
        if len(k):
            off = np.arange(len(k)) - np.repeat(ends[sl] - cnt[sl] - base, cnt[sl])
            ii = i0[k] + off % nx[k]
            jj = j0[k] + off // nx[k]
            px, py = ii + jx, jj + jy
            ak, bk, ck = a[k], b[k], c[k]
            # barycentric weights of (px, py) in the projected triangle
            w1 = ((px - ak[:, 0]) * (ck[:, 1] - ak[:, 1]) - (ck[:, 0] - ak[:, 0]) * (py - ak[:, 1])) / area2[k]
            w2 = ((bk[:, 0] - ak[:, 0]) * (py - ak[:, 1]) - (px - ak[:, 0]) * (bk[:, 1] - ak[:, 1])) / area2[k]
            w0 = 1.0 - w1 - w2
            inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            z = w0 * ak[:, 2] + w1 * bk[:, 2] + w2 * ck[:, 2]
            out_s.append(sid[k][inside])
            out_i.append(ii[inside] + e0)
            out_j.append(jj[inside] + n0)
            out_z.append(z[inside])
        start = stop
    if not out_s:
        empty = np.zeros(0, dtype=np.int64)
        return empty, empty, empty, np.zeros(0)
    return np.concatenate(out_s), np.concatenate(out_i), np.concatenate(out_j), np.concatenate(out_z)


def voxelise(solids: list[np.ndarray], min_len: float = 0.01) -> Spans:
    """Casts a vertical ray through every column centre covered by each solid.

    Crossings per (solid, column) are sorted by z and paired (parity rule), giving the inside intervals.
    Columns with an odd crossing count (non-closed mesh or a degenerate hit) are dropped and counted.
    """
    sizes = np.array([len(t) for t in solids], dtype=np.int64)
    if sizes.sum() == 0:
        z = np.zeros(0, dtype=np.int64)
        return Spans(z.astype(np.int32), z, z, np.zeros(0), np.zeros(0))
    tris = np.concatenate([t for t in solids if len(t)])
    sid = np.repeat(np.arange(len(solids)), sizes)
    e0 = int(np.floor(tris[:, :, 0].min()))
    n0 = int(np.floor(tris[:, :, 1].min()))
    local = tris - np.array([e0, n0, 0.0])
    s, e, n, z = _hits(local, sid, e0, n0)

    order = np.lexsort((z, n, e, s))
    s, e, n, z = s[order], e[order], n[order], z[order]
    new = np.ones(len(s), dtype=bool)
    new[1:] = (s[1:] != s[:-1]) | (e[1:] != e[:-1]) | (n[1:] != n[:-1])
    starts = np.flatnonzero(new)
    counts = np.diff(np.append(starts, len(s)))
    even = counts % 2 == 0
    odd_columns = int((~even).sum())
    group = np.cumsum(new) - 1
    rank = np.arange(len(s)) - starts[group]
    keep = even[group]
    lo = keep & (rank % 2 == 0)
    lo_idx = np.flatnonzero(lo)
    bottom, top = z[lo_idx], z[lo_idx + 1]
    ok = top - bottom >= min_len
    lo_idx = lo_idx[ok]
    return Spans(s[lo_idx].astype(np.int32), e[lo_idx], n[lo_idx], bottom[ok], top[ok], odd_columns)
