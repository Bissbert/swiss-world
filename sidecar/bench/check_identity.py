"""Byte-identity check: every <cell>.swd and <cell>.sources.json in OUT_A must equal OUT_B's (from profile_cell.py).

    python bench/check_identity.py OUT_BASE OUT_NEW   -> exit 1 on any difference
"""
import sys
from pathlib import Path

a, b = Path(sys.argv[1]), Path(sys.argv[2])
bad = 0
files = sorted(p.name for p in a.iterdir() if p.suffix == ".swd" or p.name.endswith(".sources.json"))
for name in files:
    ok = (b / name).exists() and (a / name).read_bytes() == (b / name).read_bytes()
    bad += not ok
    print(("same " if ok else "DIFF "), name)
print("IDENTICAL" if not bad and files else "MISMATCH", f"({len(files)} files)")
sys.exit(1 if bad or not files else 0)
