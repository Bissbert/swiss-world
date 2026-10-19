from swissdata import swd
from swissdata.config import Config
from swissdata.manifest import Pins
from swissdata.store import Store


def cfg_for(tmp_path, cap=10**12):
    return Config(raw=tmp_path / "raw", store=tmp_path / "store", scratch=tmp_path / "scratch", store_cap_bytes=cap)


def test_file_without_manifest_entry_is_not_ready(tmp_path):
    store = Store(cfg_for(tmp_path))
    swd.write_atomic(store.path(2632, 1170), b"stray fixture")
    assert not store.is_ready(2632, 1170)
    store.write(2632, 1170, b"x" * 10, {"alti": "a"})
    assert store.is_ready(2632, 1170)


def test_eviction_prefers_generated_then_oldest_and_keeps_sources(tmp_path):
    store = Store(cfg_for(tmp_path, cap=30))
    for i, cell in enumerate([(1, 1), (2, 2), (3, 3)]):
        store.write(*cell, b"x" * 10, {"alti": f"item{i}"})
    store.touch([(1, 1)])  # (2, 2) is now the oldest
    store.mark_generated([(3, 3)])
    store.write(4, 4, b"x" * 10, {"alti": "item3"})
    assert not store.is_ready(3, 3) and store.is_ready(2, 2) and store.is_ready(4, 4)
    store.write(5, 5, b"x" * 10, {"alti": "item4"})
    assert not store.is_ready(2, 2) and store.is_ready(1, 1)
    assert store.bytes == 30 and store.count == 3
    entry = store.manifest.entry(3, 3)
    assert entry["sources"] == {"alti": "item2"} and "evicted" in entry


def test_rewrite_after_eviction_clears_evicted(tmp_path):
    store = Store(cfg_for(tmp_path, cap=10))
    store.write(1, 1, b"x" * 10, {"alti": "a"})
    store.write(2, 2, b"x" * 10, {"alti": "b"})
    assert "evicted" in store.manifest.entry(1, 1)
    store.write(1, 1, b"x" * 10, store.manifest.sources(1, 1))
    assert store.is_ready(1, 1) and store.manifest.sources(1, 1) == {"alti": "a"}


def test_lru_survives_restart(tmp_path):
    cfg = cfg_for(tmp_path, cap=20)
    store = Store(cfg)
    store.write(1, 1, b"x" * 10, {})
    store.write(2, 2, b"x" * 10, {})
    store.mark_generated([(2, 2)])
    store = Store(cfg)
    store.write(3, 3, b"x" * 10, {})
    assert store.is_ready(1, 1) and not store.is_ready(2, 2)


def test_pins_resolve_once(tmp_path):
    pins = Pins(tmp_path)
    calls = []
    assert pins.pin("alti", "1-1", lambda: calls.append(1) or "v1") == "v1"
    assert Pins(tmp_path).pin("alti", "1-1", lambda: "v2") == "v1"
    assert pins.pin("alti", "9-9", lambda: None) is None and Pins(tmp_path).get("alti", "9-9") == (True, None)
    assert calls == [1]


def test_dev_area_includes_origin_column():
    from swissdata import stac

    assert stac.in_dev_area(2626, 1170) and not stac.in_dev_area(2626, 1163) and not stac.in_dev_area(2700, 1170)
