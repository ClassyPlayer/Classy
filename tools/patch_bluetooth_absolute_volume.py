#!/usr/bin/env python3
"""
patch_bluetooth_absolute_volume.py

Makes AVRCP absolute volume work on the Innioasis Y2 (MT6582, Android 4.4.2, MediaTek BlueAngel stack), so the
volume of a Bluetooth speaker or headphones and the player's volume are the same volume: the click wheel sets the
speaker's volume, and the speaker's own buttons move the player's volume. Classy uses the Android index on A2DP
(SpeakerVolume.split), so it works with these patches.

Three files, all checked by their exact bytes before they change:

1. /system/vendor/lib/hw/bluetooth.blueangel.so (the Bluetooth HAL, 2 bytes)
   btrc_handle_rc_features() turns absolute volume on only when the feature bits that mtkbt reports have
   Category 2 (0x2). mtkbt reports the remote *controller* record there (0x0 on most speakers, for example a Sonos
   that is AVRCP 1.5 with Category 2 in its target record), so absolute volume never turned on, although mtkbt sends
   SetAbsoluteVolume and the volume notification fine. The patch removes that test (cbz r3 -> nop at 0xe8c8): any
   remote with AVRCP 1.3 or later gets absolute volume (btrc_feature 0x3) and the volume notification. A remote that
   rejects SetAbsoluteVolume answers with REJECT, which Avrcp handles.

2. classes.dex of /system/app/Bluetooth.apk, and the same code in Bluetooth.odex (class Avrcp)
   Android 4.4 bug (fixed in 5.0): AudioService.setStreamVolume() passes the index * 10 to
   Avrcp.setAbsoluteVolume(), which scales it as if it were the index: ceil(index * 10 * 127 / 15), capped at 127.
   Every index from 2 up went to the speaker as 127 (full volume). convertToAvrcpVolume's constant 127.0 becomes
   12.0, so the speaker gets 8 * index (0..120); ceil(8 * index * 15 / 127) == index, so the speaker's answer gives
   the same index back (no drift). notifyVolumeChanged's flags 0x41 (FLAG_SHOW_UI | FLAG_BLUETOOTH_ABS_VOLUME)
   become 0x40: no Android volume panel each time the speaker reports its volume.
   The Y2 runs the APK's classes.dex (from /data/dalvik-cache): the Classy android.policy.jar makes the stock odex
   stale. Both get the patch so they stay the same code. In the APK, classes.dex gets new Adler-32 and SHA-1 and is
   stored (not compressed), so the output is the same on any machine; the other entries are copied byte for byte.
   For a /system app, Android 4.4 checks the signature of AndroidManifest.xml only, as for patch_bluetooth_profiles.py.

Usage: patch_bluetooth_absolute_volume.py [--check] <bluetooth.blueangel.so | Bluetooth.apk | Bluetooth.odex>
  --check  exit 0 if the file has the patch, else 1 (no change).
"""

import hashlib
import os
import struct
import sys
import tempfile
import zlib

# bluetooth.blueangel.so: btrc_handle_rc_features(), "and lr, r5, #2; uxth r3, lr; cbz r3, ..." -> nop
HAL_PATCHES = [
    (0xE8C4, bytes.fromhex("1ffa8ef3 53b1"), bytes.fromhex("1ffa8ef3 00bf"), "btrc_handle_rc_features: cbz r3 -> nop"),
]

# Offsets in the dex (classes.dex; in Bluetooth.odex add the dex offset of the odex header).
DEX_PATCHES = [
    (0x24376, bytes.fromhex("1802 0000000000c05f40"), bytes.fromhex("1802 0000000000002840"),
     "Avrcp.convertToAvrcpVolume: const-wide v2, 127.0 -> 12.0"),
    (0x246EE, bytes.fromhex("1302 4100"), bytes.fromhex("1302 4000"),
     "Avrcp.notifyVolumeChanged: const/16 v2, 0x41 -> 0x40 (no volume panel)"),
]


class PatchError(ValueError):
    pass


def apply(data, base, patches, check_only):
    """Returns True if all patches are (now) in data. Raises PatchError on unknown bytes."""
    done = True
    for off, old, new, what in patches:
        cur = bytes(data[base + off:base + off + len(old)])
        if cur == new:
            print("%s: patched" % what)
        elif cur == old:
            done = False
            if not check_only:
                data[base + off:base + off + len(new)] = new
                print("%s: patched now" % what)
            else:
                print("%s: not patched" % what)
        else:
            raise PatchError("%s: unexpected bytes at 0x%x: %s" % (what, base + off, cur.hex()))
    return done or not check_only


def fix_dex_header(data, base, length, sha1):
    dex = data[base:base + length]
    if sha1:
        data[base + 12:base + 32] = hashlib.sha1(bytes(dex[32:])).digest()
        dex = data[base:base + length]
    struct.pack_into("<I", data, base + 8, zlib.adler32(bytes(dex[12:])) & 0xFFFFFFFF)


def patch_hal(data, check_only):
    if data[:4] != b"\x7fELF":
        raise PatchError("not an ELF file")
    return apply(data, 0, HAL_PATCHES, check_only)


def patch_odex(data, check_only):
    dex_off, dex_len = struct.unpack_from("<II", data, 8)
    ok = apply(data, dex_off, DEX_PATCHES, check_only)
    if not check_only:
        # dexopt keeps the dex Adler-32 right and the SHA-1 stale; keep it so.
        fix_dex_header(data, dex_off, dex_len, sha1=False)
    return ok


def zip_entries(apk):
    """The central directory of a zip: (entries, cd_offset, eocd_offset)."""
    eocd = apk.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise PatchError("not a zip file")
    count, cd_size, cd_off = struct.unpack_from("<HII", apk, eocd + 10)
    entries, pos = [], cd_off
    for _ in range(count):
        if apk[pos:pos + 4] != b"PK\x01\x02":
            raise PatchError("bad central directory")
        (flags, method, crc, csize, usize, nlen, xlen, clen, local) = struct.unpack_from("<8xHH4xIIIHHH8xI", apk, pos)
        name = bytes(apk[pos + 46:pos + 46 + nlen]).decode("utf-8")
        entries.append(dict(name=name, cd=pos, cd_len=46 + nlen + xlen + clen, flags=flags, method=method,
                            crc=crc, csize=csize, usize=usize, local=local))
        pos += 46 + nlen + xlen + clen
    return entries, cd_off, eocd


def local_record(apk, e):
    """The bytes of an entry's local header, data and data descriptor."""
    pos = e["local"]
    if apk[pos:pos + 4] != b"PK\x03\x04":
        raise PatchError("bad local header of " + e["name"])
    nlen, xlen = struct.unpack_from("<HH", apk, pos + 26)
    end = pos + 30 + nlen + xlen + e["csize"]
    if e["flags"] & 0x0008:
        end += 16 if apk[end:end + 4] == b"PK\x07\x08" else 12
    return bytes(apk[pos:end])


def read_entry(apk, e):
    rec = local_record(apk, e)
    nlen, xlen = struct.unpack_from("<HH", rec, 26)
    raw = rec[30 + nlen + xlen:30 + nlen + xlen + e["csize"]]
    if e["method"] == 0:
        return bytearray(raw)
    if e["method"] == 8:
        return bytearray(zlib.decompress(raw, -15))
    raise PatchError("unknown compression of " + e["name"])


def patch_apk(apk, check_only):
    entries, cd_off, eocd = zip_entries(apk)
    dex_entry = next((e for e in entries if e["name"] == "classes.dex"), None)
    if dex_entry is None:
        raise PatchError("no classes.dex in the APK")
    dex = read_entry(apk, dex_entry)
    ok = apply(dex, 0, DEX_PATCHES, check_only)
    if check_only:
        return ok
    fix_dex_header(dex, 0, len(dex), sha1=True)
    crc = zlib.crc32(bytes(dex)) & 0xFFFFFFFF
    name = dex_entry["name"].encode("utf-8")
    out, new_local = bytearray(), {}
    for e in sorted(entries, key=lambda x: x["local"]):
        new_local[e["name"]] = len(out)
        if e is dex_entry:
            # Stored, no data descriptor, no extra field; same time and date.
            mtime, mdate = struct.unpack_from("<HH", apk, e["local"] + 10)
            out += struct.pack("<IHHHHHIIIHH", 0x04034B50, 10, e["flags"] & ~0x0008, 0, mtime, mdate,
                               crc, len(dex), len(dex), len(name), 0) + name + dex
        else:
            out += local_record(apk, e)
    cd_start = len(out)
    for e in entries:
        rec = bytearray(apk[e["cd"]:e["cd"] + e["cd_len"]])
        if e is dex_entry:
            struct.pack_into("<HH", rec, 8, e["flags"] & ~0x0008, 0)
            struct.pack_into("<III", rec, 16, crc, len(dex), len(dex))
        struct.pack_into("<I", rec, 42, new_local[e["name"]])
        out += rec
    end = bytearray(apk[eocd:])
    struct.pack_into("<II", end, 12, len(out) - cd_start, cd_start)
    out += end
    apk[:] = out
    return True


def kind(path, data):
    if data[:4] == b"\x7fELF":
        return patch_hal
    if data[:4] == b"dey\n":
        return patch_odex
    if data[:2] == b"PK":
        return patch_apk
    raise PatchError("%s: not bluetooth.blueangel.so, Bluetooth.odex or Bluetooth.apk" % path)


def main():
    args = [a for a in sys.argv[1:] if a != "--check"]
    check_only = "--check" in sys.argv[1:]
    if len(args) != 1:
        print("Usage: patch_bluetooth_absolute_volume.py [--check] "
              "<bluetooth.blueangel.so | Bluetooth.apk | Bluetooth.odex>", file=sys.stderr)
        return 1
    path = args[0]
    try:
        with open(path, "rb") as f:
            data = bytearray(f.read())
        patcher = kind(path, data)
        if check_only:
            return 0 if patcher(data, True) else 1
        patcher(data, False)
        directory = os.path.dirname(os.path.abspath(path))
        fd, tmp = tempfile.mkstemp(dir=directory, suffix=".tmp")
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(data)
            os.replace(tmp, path)
        except BaseException:
            if os.path.exists(tmp):
                os.unlink(tmp)
            raise
        return 0
    except (OSError, PatchError, struct.error, zlib.error) as e:
        print("Error: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
