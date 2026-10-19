"""Minimal RCON client for dev smoke tests: python3 -I tools/rcon.py <port> <password> <cmd>..."""
import socket, struct, sys
def pkt(s, i, t, body):
    d = struct.pack("<ii", i, t) + body.encode() + b"\0\0"
    s.sendall(struct.pack("<i", len(d)) + d)
    n = struct.unpack("<i", s.recv(4))[0]; r = b""
    while len(r) < n: r += s.recv(n - len(r))
    return r[8:-2].decode(errors="replace")
s = socket.create_connection(("127.0.0.1", int(sys.argv[1]))); pkt(s, 1, 3, sys.argv[2])
for c in sys.argv[3:]: print(">", c, "\n ", pkt(s, 2, 2, c))
