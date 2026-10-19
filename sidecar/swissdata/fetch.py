"""Polite, deduplicated downloads into the shared raw cache. Owner: sidecar-core (others only call fetch())."""

from __future__ import annotations

import fcntl
import os
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import httpx

from . import __version__
from .config import Config

USER_AGENT = f"swissdata/{__version__} (swissworld Minecraft mod; on-demand swisstopo open data)"


@dataclass
class UpstreamStats:
    """Process-wide counters for GET /status."""

    requests: int = 0
    downloads: int = 0
    download_bytes: int = 0
    errors: int = 0
    last_error: str | None = None

    def __post_init__(self):
        self._lock = threading.Lock()

    def count(self, *, error: str | None = None, download_bytes: int | None = None) -> None:
        with self._lock:
            self.requests += 1
            if error is not None:
                self.errors += 1
                self.last_error = error
            if download_bytes is not None:
                self.downloads += 1
                self.download_bytes += download_bytes


STATS = UpstreamStats()


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
                STATS.count(download_bytes=dest.stat().st_size)
                return dest
            except (httpx.HTTPError, OSError) as ex:
                STATS.count(error=f"{url}: {ex}")
                if _permanent(ex) or attempt == retries - 1:
                    raise
                time.sleep(delay)
                delay *= 2
    return dest


def get_json(url: str, params: dict | None = None, retries: int = 4) -> dict:
    """GET a JSON document (STAC API) with the same User-Agent and backoff as fetch()."""
    delay = 2.0
    for attempt in range(retries):
        try:
            r = httpx.get(url, params=params, headers={"User-Agent": USER_AGENT}, timeout=60, follow_redirects=True)
            r.raise_for_status()
            STATS.count()
            return r.json()
        except (httpx.HTTPError, ValueError) as ex:
            STATS.count(error=f"{url}: {ex}")
            if _permanent(ex) or attempt == retries - 1:
                raise
            time.sleep(delay)
            delay *= 2
    raise AssertionError("unreachable")


def _permanent(ex: Exception) -> bool:
    """4xx other than 429 will not get better by retrying."""
    return (isinstance(ex, httpx.HTTPStatusError) and 400 <= ex.response.status_code < 500
            and ex.response.status_code != 429)
