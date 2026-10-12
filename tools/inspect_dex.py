"""Read-only, bounded DEX inspection; output is shell evidence, not REA Evidence."""
import json
import re
import struct
import zipfile
from pathlib import Path

import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
FINGERPRINT = "maybeRegenerateCpnAndStatsClient called unexpectedly, but no error."


class Dex:
    def __init__(self, data):
        self.data = data
        if not data.startswith(b"dex\n"):
            raise ValueError("Unsupported DEX header")
        self.strings = []
        count, offset = struct.unpack_from("<II", data, 56)
        for index in range(count):
            position = self.u32(offset + index * 4)
            _, position = self.uleb(position)
            end = data.index(0, position)
            self.strings.append(data[position:end].decode("utf-8", "replace"))
        count, offset = struct.unpack_from("<II", data, 64)
        self.types = [self.strings[self.u32(offset + i * 4)] for i in range(count)]
        count, offset = struct.unpack_from("<II", data, 72)
        self.protos = []
        for i in range(count):
            _, result, params = struct.unpack_from("<III", data, offset + i * 12)
            args = [] if not params else [self.types[self.u16(params + 4 + j * 2)] for j in range(self.u32(params))]
            self.protos.append("(" + "".join(args) + ")" + self.types[result])
        count, offset = struct.unpack_from("<II", data, 88)
        self.methods = []
        for i in range(count):
            owner, proto, name = struct.unpack_from("<HHI", data, offset + i * 8)
            self.methods.append(self.types[owner] + "->" + self.strings[name] + self.protos[proto])

    def u16(self, offset):
        return struct.unpack_from("<H", self.data, offset)[0]

    def u32(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError("Invalid ULEB128")

    def implementations(self):
        count, offset = struct.unpack_from("<II", self.data, 96)
        for i in range(count):
            position = self.u32(offset + i * 32 + 24)
            if not position:
                continue
            sizes = []
            for _ in range(4):
                value, position = self.uleb(position)
                sizes.append(value)
            for _ in range(sizes[0] + sizes[1]):
                _, position = self.uleb(position)
                _, position = self.uleb(position)
            for size in sizes[2:]:
                method = 0
                for _ in range(size):
                    diff, position = self.uleb(position)
                    access, position = self.uleb(position)
                    code, position = self.uleb(position)
                    method += diff
                    if code:
                        yield method, access, code


WIDTHS = {}
for width, ranges in {
    1: [(0, 1), (4, 4), (7, 7), (10, 18), (29, 30), (33, 33), (39, 40), (123, 143), (176, 207)],
    2: [(2, 2), (5, 5), (8, 8), (19, 19), (21, 22), (25, 26), (28, 28), (31, 32), (34, 35), (41, 41), (45, 61), (68, 109), (144, 175), (208, 226), (254, 255)],
    3: [(3, 3), (6, 6), (9, 9), (20, 20), (23, 23), (27, 27), (36, 38), (42, 44), (110, 114), (116, 120), (252, 253)],
    4: [(250, 251)],
    5: [(24, 24)],
}.items():
    for start, end in ranges:
        for opcode in range(start, end + 1):
            WIDTHS[opcode] = width


def instructions(dex, code):
    size = dex.u32(code + 12)
    words = struct.unpack_from("<" + "H" * size, dex.data, code + 16)
    position = 0
    while position < size:
        opcode = words[position] & 255
        width = WIDTHS.get(opcode)
        if words[position] == 0x100:
            width = 4 + words[position + 1] * 2
        elif words[position] == 0x200:
            width = 2 + words[position + 1] * 4
        elif words[position] == 0x300:
            elements = words[position + 2] | words[position + 3] << 16
            width = 4 + (elements * words[position + 1] + 1) // 2
        if width is None or position + width > size:
            raise ValueError("Unsupported or truncated instruction at " + hex(code + 16 + position * 2))
        yield position, opcode, words[position:position + width]
        position += width


def main():
    records = []
    for name, data in load_dex():
        dex = Dex(data)
        selected = {i for i, value in enumerate(dex.strings) if value == FINGERPRINT or re.search(r"AdBreakFetcher\.java|Used PlayerResponse\.ad_params|Failed to create slot from AdSlotRenderer|AdBreak start pings", value)}
        coverage = {"dex": name, "methods_decoded": 0, "methods_unresolved": 0, "hits": []}
        for index, access, code in dex.implementations():
            try:
                decoded = list(instructions(dex, code))
            except (ValueError, IndexError, struct.error):
                coverage["methods_unresolved"] += 1
                continue
            coverage["methods_decoded"] += 1
            matches = []
            for position, opcode, words in decoded:
                if opcode in (26, 27):
                    string = words[1] if opcode == 26 else words[1] | words[2] << 16
                    if string in selected:
                        matches.append({"string_index": string, "value": dex.strings[string], "dex_file_offset": hex(code + 16 + position * 2)})
            if not matches:
                continue
            hit = {"method": dex.methods[index], "code_offset": hex(code), "access_flags": access, "strings": matches}
            if any(m["value"] == FINGERPRINT for m in matches):
                hit["invoke_virtual_candidates"] = []
                for i in range(1, len(decoded) - 1):
                    if [decoded[j][1] for j in (i - 1, i, i + 1)] == [12, 110, 84]:
                        position, _, words = decoded[i]
                        hit["invoke_virtual_candidates"].append({"target": dex.methods[words[1]], "instruction_offset": hex(code + 16 + position * 2), "words": [hex(w) for w in words]})
            coverage["hits"].append(hit)
        records.append(coverage)
    (ROOT / "ad-code-references.json").write_text(json.dumps(records, indent=2))
    print(json.dumps(records, indent=2))


def load_dex():
    with zipfile.ZipFile(ROOT / "base.apk") as archive:
        for name in archive.namelist():
            if name.endswith(".dex"):
                yield name, archive.read(name)


if __name__ == "__main__":
    main()
