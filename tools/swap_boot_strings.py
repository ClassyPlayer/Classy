#!/usr/bin/env python3
"""
swap_boot_strings.py - reword the Android boot-progress strings in framework-res.apk
without changing a single byte of the file's size (so the APK's zip layout, alignment
and every other entry stay exactly as shipped).

The strings are plain entries in the global string pool of resources.arsc. Each entry
is stored as [utf16 length][utf8 byte length][bytes][NUL], and the pool's offset table
points at the entry start. As long as the replacement is no longer than the original
(same prefix width), the new text can be written at the same offset and the tail of the
old entry zero-filled - the reader only ever trusts the two length prefixes.

Usage: swap_boot_strings.py <framework-res.apk> [--check]
"""
import struct
import sys
import zipfile


def read_pool(arsc, chunk_off):
    """Parse the ResStringPool chunk header, flags, and string offsets from raw ARSC bytes."""
    _, header_size, chunk_size = struct.unpack_from("<HHI", arsc, chunk_off)
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<IIIII", arsc, chunk_off + 8
    )
    offsets = struct.unpack_from("<%dI" % string_count, arsc, chunk_off + header_size)
    data_off = chunk_off + strings_start
    return {
        "header_size": header_size,
        "chunk_size": chunk_size,
        "count": string_count,
        "style_count": style_count,
        "flags": flags,
        "strings_start": strings_start,
        "styles_start": styles_start,
        "offsets": offsets,
        "data_off": data_off,
        "end": chunk_off + chunk_size,
    }


def read_string(arsc, pool, idx):
    """Decode a UTF-8 or UTF-16 string from the string pool at the given index."""
    off = pool["data_off"] + pool["offsets"][idx]
    utf8 = bool(pool["flags"] & 0x100)
    if utf8:
        u16len = arsc[off]
        o = off + 1
        if u16len & 0x80:
            u16len = ((u16len & 0x7F) << 8) | arsc[o]
            o += 1
        blen = arsc[o]
        o += 1
        if blen & 0x80:
            blen = ((blen & 0x7F) << 8) | arsc[o]
            o += 1
        return arsc[o : o + blen].decode("utf-8")
    u16len = struct.unpack_from("<H", arsc, off)[0]
    return arsc[off + 2 : off + 2 + u16len * 2].decode("utf-16le")


def entry_footprint(pool, idx):
    """Bytes available at this string's offset before the next string starts."""
    starts = pool["offsets"]
    if idx + 1 < pool["count"]:
        return starts[idx + 1] - starts[idx]
    data_end = pool["styles_start"] if pool["styles_start"] else pool["chunk_size"]
    return data_end - starts[idx]


def replace_string(arsc, pool, idx, new_text):
    """Replace a string entry in-place with new text, preserving byte offsets and alignment."""
    old = read_string(arsc, pool, idx)
    off = pool["data_off"] + pool["offsets"][idx]
    room = entry_footprint(pool, idx)

    if pool["flags"] & 0x100:
        payload = new_text.encode("utf-8")
        u16len = len(new_text.encode("utf-16-le")) // 2
        # same prefix widths as the original entry
        old_first = arsc[off]
        prefix = 1 + (1 if old_first & 0x80 else 0)
        o = off + prefix
        old_second = arsc[o]
        prefix2 = 1 + (1 if old_second & 0x80 else 0)
        body_off = off + prefix + prefix2
        old_body_len = (pool["offsets"][idx + 1] - pool["offsets"][idx]) if idx + 1 < pool["count"] else room
        need = len(payload) + 1
        if u16len > 0x7F or len(payload) > 0x7F:
            raise SystemExit("replacement too long for a 1-byte length prefix: %r" % new_text)
        if body_off + need > off + room:
            raise SystemExit(
                "%r does not fit: needs %d bytes, entry has %d" % (new_text, need, off + room - body_off)
            )
        arsc[off] = u16len
        arsc[o] = len(payload)
        arsc[body_off : body_off + need] = payload + b"\x00"
        for i in range(body_off + need, off + room):
            arsc[i] = 0
    else:
        payload = new_text.encode("utf-16le")
        if len(payload) + 2 > room:
            raise SystemExit("%r does not fit" % new_text)
        struct.pack_into("<H", arsc, off, len(payload) // 2)
        arsc[off + 2 : off + 2 + len(payload)] = payload
        for i in range(off + 2 + len(payload), off + room):
            arsc[i] = 0
    return old


def patch_arsc(arsc, wanted):
    """Apply all (old_string, new_string) replacements to the string pool of resources.arsc."""
    chunk_off = struct.unpack_from("<H", arsc, 0)[0]  # ResTable header size
    pool = read_pool(arsc, 12)
    by_text = {}
    for i in range(pool["count"]):
        by_text.setdefault(read_string(arsc, pool, i), []).append(i)
    changed = []
    for old, new in wanted:
        idxs = by_text.get(old, [])
        if not idxs:
            raise SystemExit("string not found: %r" % old)
        for i in idxs:
            replace_string(arsc, pool, i, new)
            changed.append((i, old, new))
    return changed


def fix_crc(path, name, data):
    """Rewrite the CRC-32 of one stored entry, in the local header and the central dir."""
    import zlib

    crc = zlib.crc32(bytes(data)) & 0xFFFFFFFF
    with open(path, "r+b") as fh:
        blob = fh.read()
        # end of central directory
        eocd = blob.rfind(b"PK\x05\x06")
        cd_off = struct.unpack_from("<I", blob, eocd + 16)[0]
        cd_size = struct.unpack_from("<I", blob, eocd + 12)[0]
        pos = cd_off
        while pos < cd_off + cd_size:
            if blob[pos : pos + 4] != b"PK\x01\x02":
                break
            name_len = struct.unpack_from("<H", blob, pos + 28)[0]
            extra_len = struct.unpack_from("<H", blob, pos + 30)[0]
            comment_len = struct.unpack_from("<H", blob, pos + 32)[0]
            entry_name = blob[pos + 46 : pos + 46 + name_len].decode("utf-8", "replace")
            if entry_name == name:
                fh.seek(pos + 16)
                fh.write(struct.pack("<I", crc))
                lho = struct.unpack_from("<I", blob, pos + 42)[0]
                fh.seek(lho)
                lh = fh.read(30)
                if lh[3] & 0x08 == 0:  # no data descriptor
                    fh.seek(lho + 14)
                    fh.write(struct.pack("<I", crc))
                return crc
            pos += 46 + name_len + extra_len + comment_len
    raise SystemExit("entry %r not found in central directory" % name)


def arsc_offset_in_zip(path, name="resources.arsc"):
    """Locate the raw file offset and compression method of an entry in a zip archive."""
    with zipfile.ZipFile(path) as z:
        info = z.getinfo(name)
    with open(path, "rb") as fh:
        fh.seek(info.header_offset)
        hdr = fh.read(30)
        if hdr[:4] != b"PK\x03\x04":
            raise SystemExit("bad local header")
        name_len = struct.unpack_from("<H", hdr, 26)[0]
        extra_len = struct.unpack_from("<H", hdr, 28)[0]
        return info.header_offset + 30 + name_len + extra_len, info.compress_type


WANTED = [
    ("Android is upgrading\u2026", "Classy is starting\u2026"),
    ("Optimizing app %1$d of %2$d.", "Updating %1$d of %2$d"),
    ("Starting apps.", "Starting up\u2026"),
    ("Finishing boot.", "Finishing up\u2026"),
]


def main():
    """Main CLI entry point to patch boot strings in framework-res.apk or check their status."""
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    check = "--check" in sys.argv

    with zipfile.ZipFile(path) as z:
        arsc = bytearray(z.read("resources.arsc"))

    pool = read_pool(arsc, 12)
    stock = {old for old, _ in WANTED}
    tracked = stock | {new for _, new in WANTED}
    now = {}
    for i in range(pool["count"]):
        s = read_string(arsc, pool, i)
        if s in tracked:
            now.setdefault(s, 0)
            now[s] += 1

    if check:
        print(now)
        return 0 if not any(k in stock for k in now) else 1

    before = len(arsc)
    changed = patch_arsc(arsc, WANTED)
    assert len(arsc) == before

    offset, method = arsc_offset_in_zip(path)
    if method != zipfile.ZIP_STORED:
        raise SystemExit("resources.arsc is compressed in this APK; refusing to patch")

    with open(path, "r+b") as fh:
        fh.seek(offset)
        fh.write(bytes(arsc))

    fix_crc(path, "resources.arsc", arsc)

    for i, old, new in changed:
        print("string %d: %r -> %r" % (i, old, new))
    print("patched %s in place (%d bytes, size unchanged)" % (path, len(arsc)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
