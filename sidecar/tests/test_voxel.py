import numpy as np

from swissdata import voxel


def prism(footprint, z0, roof):
    """Closed mesh: convex footprint [(x, y)...] counter-clockwise, flat floor at z0, top z = roof(x, y)
    (planar per fan triangle, so pass vertices on the ridge for gables)."""
    pts = np.asarray(footprint, dtype=float)
    top = [(x, y, roof(x, y)) for x, y in pts]
    bot = [(x, y, z0) for x, y in pts]
    tris = []
    for k in range(1, len(pts) - 1):
        tris.append([top[0], top[k], top[k + 1]])
        tris.append([bot[0], bot[k + 1], bot[k]])
    for k in range(len(pts)):
        m = (k + 1) % len(pts)
        tris.append([bot[k], bot[m], top[m]])
        tris.append([bot[k], top[m], top[k]])
    return np.asarray(tris, dtype=float)


def box(e, n, w, d, z0, z1):
    return prism([(e, n), (e + w, n), (e + w, n + d), (e, n + d)], z0, lambda x, y: z1)


def span_volume(sp):
    return float((sp.top - sp.bottom).sum())


def test_box_exact():
    b = box(2632000.0, 1170000.0, 10.0, 8.0, 600.0, 609.0)
    assert abs(voxel.solid_volume(b) - 720.0) < 1e-6
    sp = voxel.voxelise([b])
    assert len(sp) == 80 and sp.odd_columns == 0
    assert sp.e.min() == 2632000 and sp.e.max() == 2632009
    assert sp.n.min() == 1170000 and sp.n.max() == 1170007
    assert np.allclose(sp.bottom, 600.0) and np.allclose(sp.top, 609.0)
    assert abs(span_volume(sp) - 720.0) < 1e-6


def test_vertices_on_column_centres_are_counted_once():
    # edges and vertices exactly at x.5: the jitter must keep the crossing count even
    b = box(2632000.5, 1170000.5, 4.0, 4.0, 600.0, 603.0)
    sp = voxel.voxelise([b])
    assert sp.odd_columns == 0
    assert len(sp) == 16


def gable(e, n, w, d, z0, eave, ridge):
    """Gable roof with the ridge along x at the middle of the depth: two convex halves glued together."""
    mid = n + d / 2
    south = prism([(e, n), (e + w, n), (e + w, mid), (e, mid)], z0,
                  lambda x, y: eave + (ridge - eave) * (y - n) / (mid - n))
    north = prism([(e, mid), (e + w, mid), (e + w, n + d), (e, n + d)], z0,
                  lambda x, y: ridge - (ridge - eave) * (y - mid) / (n + d - mid))
    return south, north


def test_gable_volume_and_heights():
    s, nn = gable(2632100.0, 1170100.0, 12.0, 10.0, 600.0, 606.0, 610.0)
    sp = voxel.voxelise([s, nn])
    exact = voxel.solid_volume(s) + voxel.solid_volume(nn)
    assert abs(exact - 12 * 10 * 8.0) < 1e-6
    assert abs(span_volume(sp) - exact) / exact < 0.01
    # the column next to the ridge reaches almost the ridge height
    col = (sp.e == 2632105) & (sp.n == 1170104)
    assert abs(sp.top[col].max() - (606 + 4 * 4.5 / 5)) < 1e-4


def test_two_solids_one_column_and_hole():
    lower = box(2632000.0, 1170000.0, 3.0, 3.0, 600.0, 603.0)
    upper = box(2632000.0, 1170000.0, 3.0, 3.0, 606.0, 609.0)
    # both as one mesh (two shells): parity gives two spans per column
    sp = voxel.voxelise([np.concatenate([lower, upper])])
    col = (sp.e == 2632001) & (sp.n == 1170001)
    assert sorted(zip(sp.bottom[col], sp.top[col])) == [(600.0, 603.0), (606.0, 609.0)]
    # as separate solids each keeps its own span
    sp2 = voxel.voxelise([lower, upper])
    assert sorted(sp2.solid.tolist()) == [0] * 9 + [1] * 9


def test_open_mesh_drops_odd_columns():
    b = box(2632000.0, 1170000.0, 2.0, 2.0, 600.0, 601.0)
    sp = voxel.voxelise([np.delete(b, [1, 3], axis=0)])  # floor triangles removed
    assert sp.odd_columns > 0


def test_triangles_from_fiona_coords():
    ring = [(0.0, 0.0, 1.0), (1.0, 0.0, 1.0), (0.0, 1.0, 1.0), (0.0, 0.0, 1.0)]
    t = voxel.triangles([[ring], [ring]])
    assert t.shape == (2, 3, 3)
