import numpy as np

from swissdata import build, swd
from swissdata.config import Config


class FlatTerrain:
    name = "flat"
    tags = ("TERR",)

    def build(self, e_km, n_km, cfg, partial, pinned):
        return build.LayerResult(layers={"TERR": swd.encode_height(np.full((swd.TERR_N, swd.TERR_N), 600.0))},
                                 sources={"alti": "test"})


def test_assemble_with_default_surface(tmp_path):
    cfg = Config(raw=tmp_path, store=tmp_path, scratch=tmp_path)
    cell, sources = build.assemble(2632, 1170, cfg, builders=[FlatTerrain(), build.DefaultSurface()])
    assert sources == {"alti": "test"}
    back = swd.decode(swd.encode(cell))
    assert back.layers["SURF"].max() == 0
