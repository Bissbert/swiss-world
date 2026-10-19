import io

import numpy as np

from swissdata import gwr

TSV = ("EGID\tGDEKT\tGKAT\tGKLAS\tGBAUP\tGASTW\tGEXPDAT\n"
       "1350894\tBE\t1020\t1110\t8013\t2\t2026-10-19\n"
       "1346414\tBE\t1040\t1211\t8011\t6\t2026-10-19\n"
       "\tBE\t1020\t\t\t\t2026-10-19\n"
       "99\tBE\t1060\t\t\t300\t2026-10-19\n")


def test_parse_and_lookup():
    t = gwr.parse_csv(io.StringIO(TSV))
    assert t.egid.tolist() == [99, 1346414, 1350894] and t.snapshot == "2026-10-19"
    found, gkat, gklas, gbaup, gastw = t.lookup(np.array([1346414, 0, 5, 99]))
    assert found.tolist() == [True, False, False, True]
    assert (gkat[0], gklas[0], gbaup[0], gastw[0]) == (1040, 1211, 8011, 6)
    assert gklas[3] == 0 and gastw[3] == 255  # missing class, floors clipped to u8
    assert gwr.source_id(t) == "gwr_be_2026-10-19"
