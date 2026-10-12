"""Restore merged type-spec flags/counts from the original installed APK set.

The merge retained sparse entry IDs but shortened some type-spec arrays to the
number of populated entries. Restore the original per-ID configuration flags.
No resource values, file contents, IDs or DEX code are changed.
"""
from pathlib import Path
import hashlib
import json
import struct
import zipfile

import argparse

APP = None
BUILD = None
SPECS = {}


def chunks(data, start, end):
    position = start
    while position < end:
        kind, header, size = struct.unpack_from("<HHI", data, position)
        if size < header or position + size > end or header < 8:
            raise ValueError("Invalid resource chunk")
        yield position, kind, header, size
        position += size
    if position != end:
        raise ValueError("Resource chunk boundary mismatch")


def collect(data):
    for position, kind, header, size in chunks(data, struct.unpack_from("<H", data, 2)[0], len(data)):
        if kind != 0x200:
            continue
        package = struct.unpack_from("<I", data, position + 8)[0]
        for pos, typ, hs, length in chunks(data, position + header, position + size):
            if typ != 0x202:
                continue
            type_id = data[pos + 8]
            count = struct.unpack_from("<I", data, pos + 12)[0]
            values = struct.unpack_from("<" + "I" * count, data, pos + hs)
            flags = SPECS.setdefault((package, type_id), [])
            flags.extend([0] * max(0, count - len(flags)))
            for index, value in enumerate(values):
                flags[index] |= value


def repair(data):
    changes = []
    table_header = struct.unpack_from("<H", data, 2)[0]
    table_parts = [data[:table_header]]
    for position, kind, header, size in chunks(data, table_header, len(data)):
        if kind != 0x200:
            table_parts.append(data[position:position + size])
            continue
        package = struct.unpack_from("<I", data, position + 8)[0]
        parts = [data[position:position + header]]
        for pos, typ, hs, length in chunks(data, position + header, position + size):
            chunk = data[pos:pos + length]
            if typ == 0x202:
                key = (package, data[pos + 8])
                if key not in SPECS:
                    raise ValueError("Merged type spec lacks original provenance")
                old_count = struct.unpack_from("<I", data, pos + 12)[0]
                flags = SPECS[key]
                replaced = bytearray(data[pos:pos + hs])
                struct.pack_into("<I", replaced, 12, len(flags))
                replaced.extend(struct.pack("<" + "I" * len(flags), *flags))
                struct.pack_into("<I", replaced, 4, len(replaced))
                chunk = bytes(replaced)
                if chunk != data[pos:pos + length]:
                    changes.append({"package_id": package, "type_id": key[1], "merged_count": old_count, "restored_count": len(flags)})
            elif typ == 0x201:
                type_id, flag = data[pos + 8], data[pos + 9]
                count = struct.unpack_from("<I", data, pos + 12)[0]
                if flag & 1:
                    ids = [struct.unpack_from("<H", data, pos + hs + i * 4)[0] for i in range(count)]
                    if ids and max(ids) >= len(SPECS[(package, type_id)]):
                        raise ValueError("Sparse entry ID exceeds restored type spec")
                elif count > len(SPECS[(package, type_id)]):
                    raise ValueError("Dense entry count exceeds restored type spec")
            elif typ == 1:
                # Package header string-pool offsets remain valid: both pools
                # precede every resized type-spec chunk in this artifact.
                if changes:
                    raise ValueError("Unexpected string pool after resized spec")
            parts.append(chunk)
        package_bytes = bytearray(b"".join(parts))
        struct.pack_into("<I", package_bytes, 4, len(package_bytes))
        table_parts.append(bytes(package_bytes))
    result = bytearray(b"".join(table_parts))
    struct.pack_into("<I", result, 4, len(result))
    return bytes(result), changes


def main():
    global APP, BUILD
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, required=True)
    args = parser.parse_args()
    APP, BUILD = args.input_dir.resolve(), args.build_dir.resolve()
    for source in sorted(APP.glob("*.apk")):
        with zipfile.ZipFile(source) as archive:
            collect(archive.read("resources.arsc"))
    source = BUILD / "music-unsigned.apk"
    target = BUILD / "music-resources-fixed.apk"
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(target, "w") as after:
        old = before.read("resources.arsc")
        resources, changes = repair(old)
        for info in before.infolist():
            after.writestr(info, resources if info.filename == "resources.arsc" else before.read(info.filename))
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(target) as after:
        modified = [name for name in before.namelist() if before.read(name) != after.read(name)]
        if modified != ["resources.arsc"] or before.namelist() != after.namelist():
            raise ValueError("Unexpected APK content change")
    result = {"changed_member": "resources.arsc", "restored_specs": changes,
              "before_sha256": hashlib.sha256(old).hexdigest(), "after_sha256": hashlib.sha256(resources).hexdigest()}
    (BUILD / "resource-spec-repair.json").write_text(json.dumps(result, indent=2))
    print("Restored", len(changes), "type-spec declarations from original APKs; other members identical.")


if __name__ == "__main__":
    main()
