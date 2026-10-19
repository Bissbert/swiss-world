"""Cross-check acc.py's Anvil reader against the live server (rcon `execute if block`). Run after gen + save-all."""
import random, sys
import acc
r = acc.Rcon()
random.seed(1)
ok = bad = 0
x1, z1, x2, z2 = (int(v) for v in (sys.argv[1] if len(sys.argv) > 1 else "-32,-32,31,31").split(","))
print(r(f"execute in {acc.DIM} run forceload add {x1} {z1} {x2} {z2}"))
import time; time.sleep(3)
for _ in range(int(sys.argv[2]) if len(sys.argv) > 2 else 12):
    x, z = random.randint(x1, x2), random.randint(z1, z2)
    top = acc.top_at(x, z)
    for y in (top, top - 1, top - 3, top + 1, top - 40):
        name = acc.block_at(x, y, z)
        res = r(f"execute in {acc.DIM} if block {x} {y} {z} {name}")
        good = "passed" in res
        ok += good; bad += not good
        if not good: print("MISMATCH", x, y, z, name, res)
    if bad < 5: print(f"x {x} z {z} top {top} {acc.block_at(x, top, z)} terr {acc.terr_at_block(x, z):.2f} m -> y {acc.y_of(acc.terr_at_block(x, z)):.2f} alti {acc.alti_m(*acc.lv95_of(x, z)):.2f}")
r(f"execute in {acc.DIM} run forceload remove {x1} {z1} {x2} {z2}")
print(f"reader check: {ok} match, {bad} mismatch")
