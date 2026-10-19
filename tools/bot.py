"""Headless 1.21.1 (protocol 767) player for dev smoke tests. Owner: mod-core. Stdlib only.

Needs an offline-mode server with network-compression-threshold=-1. Op the bot while it is online (an offline
`op Bot` resolves the name against Mojang and ops someone else). Steps run in order:

    python3 -I tools/bot.py 25640 Bot "cmd:swiss" "wait:20" "cmd:swiss where" "walk:1000,0,20" "cmd:swiss back"

    cmd:<command>        send a chat command (no leading slash)
    wait:<seconds>       keep the connection alive and print chat
    walk:<dx>,<dz>,<v>   fly in a straight line at v m/s (spectator mode recommended), one move packet per tick

BOT_VIEW=<chunks> sets the requested view distance (default 8; the server clamps it to its own).
Chat is printed as "chat: ..."; URLs in click events are printed as "link: ...". Login and respawn packets print
"spawn: <dimension> flat=<isFlat>" (the client's sky horizon depends on isFlat).
"""

from __future__ import annotations

import hashlib
import os
import socket
import struct
import sys
import threading
import time
import uuid

PROTOCOL = 767


def varint(n: int) -> bytes:
    n &= 0xFFFFFFFF
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def string(s: str) -> bytes:
    d = s.encode()
    return varint(len(d)) + d


class Buf:
    def __init__(self, data: bytes):
        self.d, self.i = data, 0

    def take(self, n: int) -> bytes:
        r = self.d[self.i:self.i + n]
        self.i += n
        return r

    def varint(self) -> int:
        n = shift = 0
        while True:
            b = self.take(1)[0]
            n |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                return n - (1 << 32) if n >= 1 << 31 else n

    def unpack(self, fmt: str):
        s = struct.Struct(">" + fmt)
        return s.unpack(self.take(s.size))

    def string(self) -> str:
        return self.take(self.varint()).decode(errors="replace")


def nbt_strings(b: Buf, tag: int, out: list[tuple[str, str]], key: str = "") -> None:
    """Collects (key, value) of every TAG_String in a network NBT payload."""
    if tag == 1: b.take(1)
    elif tag == 2: b.take(2)
    elif tag in (3, 5): b.take(4)
    elif tag in (4, 6): b.take(8)
    elif tag == 7: b.take(b.unpack("i")[0])
    elif tag == 8: out.append((key, b.take(b.unpack("H")[0]).decode(errors="replace")))
    elif tag == 9:
        t, n = b.unpack("bi")
        for _ in range(n):
            nbt_strings(b, t, out, key)
    elif tag == 10:
        while (t := b.unpack("b")[0]) != 0:
            name = b.take(b.unpack("H")[0]).decode()
            nbt_strings(b, t, out, name)
    elif tag == 11: b.take(4 * b.unpack("i")[0])
    elif tag == 12: b.take(8 * b.unpack("i")[0])


def component(b: Buf) -> tuple[str, list[str]]:
    out: list[tuple[str, str]] = []
    nbt_strings(b, b.unpack("b")[0], out, "text")
    text = "".join(v if k in ("text", "") else f"<{v}>" for k, v in out if k in ("text", "", "translate"))
    return text, [v for k, v in out if v.startswith("http")]


class Bot:
    def __init__(self, port: int, name: str):
        self.s = socket.create_connection(("127.0.0.1", port))
        self.lock = threading.Lock()
        self.pos = None
        self.alive = True
        self.name = name
        self.send(0x00, varint(PROTOCOL) + string("127.0.0.1") + struct.pack(">H", port) + varint(2))
        self.send(0x00, string(name) + uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:" + name.encode()).digest(), version=3).bytes)
        self.state = "login"
        threading.Thread(target=self.reader, daemon=True).start()

    def send(self, pid: int, payload: bytes = b"") -> None:
        body = varint(pid) + payload
        with self.lock:
            self.s.sendall(varint(len(body)) + body)

    def recv_exact(self, n: int) -> bytes:
        r = b""
        while len(r) < n:
            c = self.s.recv(n - len(r))
            if not c:
                raise EOFError
            r += c
        return r

    def read_packet(self) -> Buf:
        n = shift = 0
        while True:
            b = self.recv_exact(1)[0]
            n |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        return Buf(self.recv_exact(n))

    def reader(self) -> None:
        try:
            while True:
                b = self.read_packet()
                self.handle(b.varint(), b)
        except (EOFError, OSError):
            pass
        finally:
            self.alive = False

    def handle(self, pid: int, b: Buf) -> None:
        if self.state == "login":
            if pid == 0x00:
                print("disconnected:", b.string(), flush=True)
            elif pid == 0x02:
                self.send(0x03)
                self.state = "config"
                self.send(0x00, string("en_us") + bytes([int(os.environ.get("BOT_VIEW", "8"))]) + varint(0) + b"\x01" + bytes([0x7F]) + varint(1) + b"\x00\x01")
            elif pid == 0x03:
                sys.exit("set network-compression-threshold=-1")
            elif pid == 0x04:
                self.send(0x02, varint(b.varint()) + b"\x00")
        elif self.state == "config":
            if pid == 0x02:
                print("disconnected:", component(b)[0], flush=True)
            elif pid == 0x03:
                self.send(0x03)
                self.state = "play"
            elif pid == 0x04:
                self.send(0x04, b.take(8))
            elif pid == 0x05:
                self.send(0x05, b.take(4))
            elif pid == 0x0E:
                self.send(0x07, varint(0))
        else:
            if pid == 0x1D:
                print("disconnected:", component(b)[0], flush=True)
            elif pid == 0x26:
                self.send(0x18, b.take(8))
            elif pid == 0x35:
                self.send(0x27, b.take(4))
            elif pid == 0x0C:
                self.send(0x08, struct.pack(">f", 20.0))
            elif pid == 0x40:
                x, y, z, yaw, pitch, flags = b.unpack("dddffB")
                tid = b.varint()
                if self.pos is not None and flags:
                    px, py, pz = self.pos
                    x, y, z = (px + x if flags & 1 else x), (py + y if flags & 2 else y), (pz + z if flags & 4 else z)
                self.pos = (x, y, z)
                self.send(0x00, varint(tid))
                self.send(0x1B, struct.pack(">dddff?", x, y, z, 0.0, 0.0, False))
                print(f"position: {x:.1f} {y:.1f} {z:.1f}", flush=True)
            elif pid in (0x2B, 0x47):  # login / respawn: CommonPlayerSpawnInfo
                if pid == 0x2B:
                    b.take(5)
                    for _ in range(b.varint()):
                        b.string()
                    b.varint(), b.varint(), b.varint()
                    b.take(3)
                b.varint()
                dim = b.string()
                b.take(10)
                debug, flat = b.unpack("??")
                print(f"spawn: {dim} flat={flat}", flush=True)
            elif pid == 0x6C:
                text, links = component(b)
                print("chat:", text, flush=True)
                for link in links:
                    print("link:", link, flush=True)

    def command(self, cmd: str) -> None:
        self.send(0x04, string(cmd))

    def walk(self, dx: float, dz: float, speed: float) -> None:
        x0, y, z0 = self.pos
        dist = (dx * dx + dz * dz) ** 0.5
        ticks = max(1, int(dist / speed * 20))
        start = time.time()
        for t in range(1, ticks + 1):
            x, z = x0 + dx * t / ticks, z0 + dz * t / ticks
            self.send(0x1A, struct.pack(">ddd?", x, y, z, False))
            self.pos = (x, y, z)
            time.sleep(max(0.0, start + t / 20 - time.time()))
        print(f"walked to {self.pos[0]:.1f} {self.pos[1]:.1f} {self.pos[2]:.1f} in {time.time() - start:.1f}s", flush=True)


def main() -> None:
    port, name, steps = int(sys.argv[1]), sys.argv[2], sys.argv[3:]
    bot = Bot(port, name)
    while bot.pos is None and bot.alive:
        time.sleep(0.1)
    for step in steps:
        if not bot.alive:
            sys.exit("connection closed")
        kind, _, arg = step.partition(":")
        print(">", step, flush=True)
        if kind == "cmd":
            bot.command(arg)
            time.sleep(0.5)
        elif kind == "wait":
            time.sleep(float(arg))
        elif kind == "walk":
            dx, dz, v = (float(a) for a in arg.split(","))
            bot.walk(dx, dz, v)
    time.sleep(1)


if __name__ == "__main__":
    main()
