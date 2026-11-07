"""Render the existing Pocket H mark with only the Python standard library."""
from pathlib import Path
import struct
import zlib

size = 1024
root = Path(__file__).resolve().parents[1] / "resources"
bg, ink = (16, 27, 34), (112, 225, 197)
rows = bytearray()
for y in range(size):
    rows.append(0)
    for x in range(size):
        px, py = (x + .5) * 108 / size, (y + .5) * 108 / size
        dx, dy = max(24 - px, px - 84, 0), max(24 - py, py - 84, 0)
        alpha = 255 if dx * dx + dy * dy <= 24 * 24 else 0
        mark = (27 <= px < 39 or 69 <= px < 81) and 24 <= py < 84 or 39 <= px < 69 and 47 <= py < 59
        rows.extend((* (ink if mark else bg), alpha))

def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")
(root / "icon.png").write_bytes(png)
