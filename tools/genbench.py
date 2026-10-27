"""Load-time bench driver on top of tools/bot.py (throwaway, owner: gen-speed analysis). Stdlib only.

    python3 -I tools/genbench.py 25700 Bot "cmd:swiss" "fill:12,90" "walk:2000,0,20" ...

Extra steps (everything else is bot.py's):
    fill:<R>,<timeout>   wait until every chunk within R (square) of the bot has arrived; prints the time it took and
                         the arrival curve (seconds -> chunks received in the square) once a second
    walk:<dx>,<dz>,<v>   as bot.py, but also prints once a second: chunks missing in the R=BENCH_R (default 12) square
                         around the bot, and the worst-case missing count. Shows whether chunks keep up with the flight.
    arrive:<cmd>         send a command and time until a new login/respawn packet (dimension change) plus position
"""
import math
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bot as B  # noqa: E402

R = int(os.environ.get("BENCH_R", "12"))


def square(bot, r):
    if bot.pos is None:
        return 0, 0
    cx, cz = math.floor(bot.pos[0]) >> 4, math.floor(bot.pos[2]) >> 4
    have = sum(1 for dx in range(-r, r + 1) for dz in range(-r, r + 1) if (cx + dx, cz + dz) in bot.chunks)
    return have, (2 * r + 1) ** 2


def fill(bot, r, timeout):
    t0 = time.time()
    last = -1
    while time.time() - t0 < timeout:
        have, want = square(bot, r)
        t = int(time.time() - t0)
        if t != last:
            print(f"  fill t={t}s {have}/{want}", flush=True)
            last = t
        if have == want:
            print(f"filled R={r} in {time.time() - t0:.2f}s", flush=True)
            return
        time.sleep(0.05)
    print(f"fill R={r} TIMEOUT after {timeout}s {square(bot, r)}", flush=True)


def plateau(bot, quiet=3.0, timeout=120.0, t_ref=None):
    """Wait until no new chunk arrived for `quiet` s. Prints t50/t90/t99 (s from t_ref) against the final count."""
    t_ref = t_ref or time.time()
    series = []  # (t, count)
    last_n, last_change = -1, time.time()
    while time.time() - t_ref < timeout:
        n = len(bot.chunks)
        now = time.time()
        if n != last_n:
            series.append((now - t_ref, n))
            last_n, last_change = n, now
        elif now - last_change > quiet and n > 0:
            break
        time.sleep(0.02)
    final = series[-1][1] if series else 0
    out = {}
    for frac in (0.01, 0.1, 0.5, 0.9, 0.99, 1.0):
        out[frac] = next((t for t, n in series if n >= frac * final), float("nan"))
    print(f"plateau {final} chunks; t(first)={out[0.01]:.2f}s t10={out[0.1]:.2f} t50={out[0.5]:.2f} t90={out[0.9]:.2f} t99={out[0.99]:.2f} t100={out[1.0]:.2f}", flush=True)
    return final


def arrive(bot, cmd, r=12):
    """Teleport command: t_tp = pos jumps >200 blocks; then count chunks inside the view circle around the new pos
    until a plateau. Prints t_tp (command -> position change) and t10/t50/t90/t100 of the circle count from the command."""
    p0 = bot.pos
    r0 = getattr(bot, "respawn_t", 0)
    t0 = time.time()
    bot.command(cmd)
    while time.time() - t0 < 300:
        p = bot.pos
        if getattr(bot, "respawn_t", 0) != r0 and p:
            break
        if p and p0 and math.hypot(p[0] - p0[0], p[2] - p0[2]) > 200:
            break
        time.sleep(0.02)
    t_tp = time.time() - t0
    cx, cz = math.floor(bot.pos[0]) >> 4, math.floor(bot.pos[2]) >> 4
    series, last_n, last_change = [], -1, time.time()
    while time.time() - t0 < 300:
        n = sum(1 for (x, z) in list(bot.chunks) if (x - cx) ** 2 + (z - cz) ** 2 <= r * r)
        now = time.time()
        if n != last_n:
            series.append((now - t0, n))
            last_n, last_change = n, now
        elif now - last_change > 4 and n > 0:
            break
        time.sleep(0.05)
    final = series[-1][1]
    f = lambda q: next(t for t, n in series if n >= q * final)
    print(f"arrive[{cmd}]: tp at {t_tp:.2f}s ; circle(r={r}) plateau {final}: first {f(0.001):.2f}s t10 {f(0.1):.2f} t50 {f(0.5):.2f} t90 {f(0.9):.2f} t100 {series[-1][0]:.2f}  (s from command)", flush=True)


def walk(bot, dx, dz, v):
    stop = threading.Event()
    worst = [0]
    samples = []

    def mon():
        t0 = time.time()
        while not stop.is_set():
            have, want = square(bot, R)
            worst[0] = max(worst[0], want - have)
            samples.append(want - have)
            print(f"  walk t={time.time() - t0:.0f}s missing={want - have} total={len(bot.chunks)}", flush=True)
            time.sleep(1)

    th = threading.Thread(target=mon, daemon=True)
    th.start()
    bot.walk(dx, dz, v)
    stop.set()
    th.join()
    avg = sum(samples) / max(1, len(samples))
    print(f"walk summary: avg missing {avg:.1f} worst {worst[0]} of {(2 * R + 1) ** 2}", flush=True)


def main():
    port, name, steps = int(sys.argv[1]), sys.argv[2], sys.argv[3:]
    bot = B.Bot(port, name)
    while bot.pos is None and bot.alive:
        time.sleep(0.1)
    for step in steps:
        if not bot.alive:
            sys.exit("connection closed")
        kind, _, arg = step.partition(":")
        print(f"> {step}  [{time.strftime('%H:%M:%S')}]", flush=True)
        if kind == "fill":
            r, to = arg.split(",")
            fill(bot, int(r), float(to))
        elif kind == "walk":
            a, b, v = (float(x) for x in arg.split(","))
            walk(bot, a, b, v)
        elif kind == "arrive":
            arrive(bot, arg)
        elif kind == "plateau":
            plateau(bot)
        elif kind == "cmd":
            bot.command(arg)
            time.sleep(0.5)
        elif kind == "wait":
            time.sleep(float(arg))
        elif kind == "look":
            bot.yaw = float(arg)
        else:
            print("unknown step", step)
    time.sleep(1)


if __name__ == "__main__":
    main()
