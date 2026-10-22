"""Polite, deduplicated downloads into the shared raw cache. Owner: sidecar-core (others only call fetch())."""

from __future__ import annotations

import fcntl
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import httpx

from . import __version__, progress
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


MAX_CONCURRENT = 4  # swisstopo politeness: never more than this many requests in flight, whatever the callers do
_http_slots = threading.BoundedSemaphore(MAX_CONCURRENT)
_client: httpx.Client | None = None
_limit = MAX_CONCURRENT
_client_lock = threading.Lock()


def set_process_limit(n: int) -> None:
    """Caps this process's in-flight requests at `n` (1..MAX_CONCURRENT). Pool workers call it first thing, so that
    all processes together stay within MAX_CONCURRENT. Must run before the first request."""
    global _http_slots, _limit
    _limit = max(1, min(n, MAX_CONCURRENT))
    _http_slots = threading.BoundedSemaphore(_limit)


def _http() -> httpx.Client:
    """One keep-alive client per process (httpx clients are thread-safe): no TLS handshake per request."""
    global _client
    with _client_lock:
        if _client is None:
            _client = httpx.Client(headers={"User-Agent": USER_AGENT}, timeout=httpx.Timeout(120, connect=30),
                                   follow_redirects=True,
                                   limits=httpx.Limits(max_connections=_limit,
                                                       max_keepalive_connections=_limit))
        return _client


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
                prog = progress.current()
                with _http_slots, _http().stream("GET", url) as r:
                    r.raise_for_status()
                    if prog is not None:
                        size = r.headers.get("content-length")
                        prog.bytes, prog.total_bytes = 0, int(size) if size and size.isdigit() else None
                        prog.file = dest.name
                    with open(part, "wb") as f:
                        for chunk in r.iter_bytes(1 << 20):
                            f.write(chunk)
                            if prog is not None:
                                prog.bytes += len(chunk)
                        f.flush()
                        os.fsync(f.fileno())
                    if prog is not None:
                        prog.file = None
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


def prefetch(cfg: Config, urls, workers: int = MAX_CONCURRENT) -> None:
    """Downloads the missing `urls` in parallel (at most MAX_CONCURRENT requests in flight process-wide). Errors are
    left for the later fetch() of the same URL to raise, with its retries, in the caller's own thread."""
    todo = list(dict.fromkeys(u for u in urls if not cache_path(cfg, u).exists()))
    if len(todo) < 2:
        return

    def one(u: str) -> None:
        try:
            fetch(cfg, u)
        except Exception:  # noqa: BLE001 - reported by the caller's own fetch()
            pass

    with ThreadPoolExecutor(min(workers, len(todo))) as pool:
        list(pool.map(one, todo))


def get_json(url: str, params: dict | None = None, retries: int = 4) -> dict:
    """GET a JSON document (STAC API) with the same User-Agent and backoff as fetch()."""
    delay = 2.0
    for attempt in range(retries):
        try:
            with _http_slots:
                r = _http().get(url, params=params, timeout=60)
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
