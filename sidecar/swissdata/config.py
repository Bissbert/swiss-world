"""Paths and limits. All agents/worktrees share one raw cache and one store via absolute env paths."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

_REPO = Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class Config:
    raw: Path  # downloaded upstream assets (SWISSDATA_RAW)
    store: Path  # cells/, coverage.bin, manifest.json (SWISSDATA_STORE)
    scratch: Path  # temporary extraction (SWISSDATA_SCRATCH)
    max_downloads: int = 2
    store_cap_bytes: int = 12_000_000_000
    scratch_cap_bytes: int = 2_000_000_000
    keep_raw: bool = True  # dev default; production sets SWISSDATA_KEEP_RAW=0
    # swissSURFACE3D tiles (12-16 MB each, ~18 GB for the test bbox) are deleted after extraction even in dev,
    # unless SWISSDATA_KEEP_RAW=1 is set explicitly
    keep_surface_raw: bool = False
    dev_bbox: bool = True  # dev default: never touch tiles outside the test bbox; production sets SWISSDATA_DEV_BBOX=0

    @staticmethod
    def from_env() -> "Config":
        def p(name: str, default: Path) -> Path:
            return Path(os.environ.get(name) or default).resolve()

        return Config(
            raw=p("SWISSDATA_RAW", _REPO / ".data-cache" / "raw"),
            store=p("SWISSDATA_STORE", _REPO / "store"),
            scratch=p("SWISSDATA_SCRATCH", _REPO / ".data-cache" / "scratch"),
            max_downloads=min(4, int(os.environ.get("SWISSDATA_MAX_DOWNLOADS", "2"))),
            keep_raw=os.environ.get("SWISSDATA_KEEP_RAW", "1") != "0",
            keep_surface_raw=os.environ.get("SWISSDATA_KEEP_RAW") == "1",
            dev_bbox=os.environ.get("SWISSDATA_DEV_BBOX", "1") != "0",
            store_cap_bytes=int(os.environ.get("SWISSDATA_STORE_CAP_BYTES", "12000000000")),
        )
