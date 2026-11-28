#!/usr/bin/env python3
"""Builds the boot animation (bootanimation.zip) from the boot logo (logo.bin).

The Y1 base ROM has the Rockbox animation in /system/media/bootanimation.zip: it shows after the boot logo until the
UI starts. This one has one frame, the picture of stream 0 of the logo partition, so the boot logo stays until the UI
starts. The zip is stored (not compressed), as the boot animation of Android 4.2 needs.

Usage:
  python3 tools/build_boot_animation.py
  python3 tools/build_boot_animation.py tools/rom_assets/logo_classy.bin app/src/y1/assets/boot/bootanimation.zip
"""
import io
import os
import struct
import sys
import zipfile
import zlib

from PIL import Image

W, H = 480, 360

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_LOGO_PATH = os.path.join(SCRIPT_DIR, "rom_assets", "logo_classy.bin")
DEFAULT_OUT_PATH = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "app", "src", "y1", "assets", "boot", "bootanimation.zip"))

# One frame a second, shown again until the system stops the animation
DESC = f"{W} {H} 1\np 0 0 part0\n"

# A fixed time in the zip: the same logo gives the same file
ZIP_TIME = (2026, 1, 1, 0, 0, 0)


def logo_frame(data: bytes) -> Image.Image:
    """The picture of stream 0 of an MTK LOGO partition (zlib, RGB565 little-endian, W x H)."""
    count, total = struct.unpack_from("<II", data, 512)
    offsets = struct.unpack_from(f"<{count}I", data, 520)
    end = offsets[1] if count > 1 else total
    raw = zlib.decompress(data[512 + offsets[0]:512 + end])
    assert len(raw) == W * H * 2, f"stream 0 has {len(raw)} bytes, not {W * H * 2}"
    rgb = bytearray(W * H * 3)
    for i in range(W * H):
        px = raw[2 * i] | (raw[2 * i + 1] << 8)
        r, g, b = (px >> 11) & 0x1F, (px >> 5) & 0x3F, px & 0x1F
        rgb[3 * i] = (r << 3) | (r >> 2)
        rgb[3 * i + 1] = (g << 2) | (g >> 4)
        rgb[3 * i + 2] = (b << 3) | (b >> 2)
    return Image.frombytes("RGB", (W, H), bytes(rgb))


def build_zip(frame: Image.Image) -> bytes:
    png = io.BytesIO()
    frame.save(png, "PNG", optimize=True)
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
        for name, content in (("desc.txt", DESC.encode()), ("part0/00000.png", png.getvalue())):
            info = zipfile.ZipInfo(name, ZIP_TIME)
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o644 << 16
            z.writestr(info, content)
    return out.getvalue()


if __name__ == "__main__":
    logo_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_LOGO_PATH
    out_path = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT_PATH
    with open(logo_path, "rb") as f:
        data = build_zip(logo_frame(f.read()))
    with open(out_path, "wb") as f:
        f.write(data)
    print(f"Wrote {out_path} ({len(data)} bytes) from {logo_path}")
