"""Polite, deduplicated downloads into the shared raw cache. Owner: sidecar-core (others only call fetch())."""

from __future__ import annotations

import fcntl
import os
import time
from pathlib import Path
from urllib.parse import urlparse

import httpx

from . import __version__
from .config import Config

USER_AGENT = f"swissdata/{__version__} (swissworld Minecraft mod; on-demand swisstopo open data)"


def cache_path(cfg: Config, url: str) -> Path:
    u = urlparse(url)
    return cfg.raw / u.netloc / u.path.lstrip("/")


def fetch(cfg: Config, url: str, retries: int = 4) -> Path:
    """Returns the local path of `url`, downloading it once. Safe across processes and worktrees:
    a per-asset lock file serialises downloads, data lands in *.part and is renamed atomically."""
    dest = cache_path(cfg, url)
    if dest.exists():
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    lock = dest.with_name(dest.name + ".lock")
    with open(lock, "w") as lf:
        fcntl.flock(lf, fcntl.LOCK_EX)
        if dest.exists():
            return dest
        part = dest.with_name(dest.name + ".part")
        delay = 2.0
        for attempt in range(retries):
            try:
                with httpx.stream("GET", url, headers={"User-Agent": USER_AGENT}, timeout=120,
                                  follow_redirects=True) as r:
                    r.raise_for_status()
                    with open(part, "wb") as f:
                        for chunk in r.iter_bytes(1 << 20):
                            f.write(chunk)
                        f.flush()
                        os.fsync(f.fileno())
                os.replace(part, dest)
                return dest
            except (httpx.HTTPError, OSError):
                if attempt == retries - 1:
                    raise
                time.sleep(delay)
                delay *= 2
    return dest
