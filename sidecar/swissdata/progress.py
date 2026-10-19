"""Live progress of the cell builds, for GET /status "builds" (docs/sidecar-api.md). Owner: sidecar-core.

A build worker sets its cell's CellProgress as the thread's current one; assemble() advances the step per builder and
fetch() reports the bytes of a download as they stream. Readers (the /status handler) only read plain attributes.
"""

from __future__ import annotations

import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass, field

_local = threading.local()


@dataclass
class CellProgress:
    cell: tuple[int, int]
    started: float = field(default_factory=time.monotonic)
    stage: str = "starting"
    step: int = 0
    steps: int = 0
    file: str | None = None  # the download in progress, if any
    bytes: int = 0
    total_bytes: int | None = None

    def as_dict(self) -> dict:
        out: dict = {"cell": list(self.cell), "stage": self.stage, "step": self.step, "steps": self.steps,
                     "elapsedS": round(time.monotonic() - self.started, 1)}
        f = self.file
        if f is not None:
            out["download"] = {"file": f, "bytes": self.bytes, "totalBytes": self.total_bytes}
        return out


def current() -> CellProgress | None:
    return getattr(_local, "progress", None)


@contextmanager
def tracking(p: CellProgress):
    _local.progress = p
    try:
        yield p
    finally:
        _local.progress = None
