#!/usr/bin/env python3
"""Static icon check of a built Forge 1.7.10 mod jar: the icon names the classes request with a literal must have
their texture in the jar.

Requests found: a "<ns>:<name>" string constant loaded right before a call of setTextureName / setBlockTextureName /
registerIcon (deobfuscated or SRG names), or stored into a String array by a class that calls registerIcon (icon
name tables). Each must exist as assets/<ns>/textures/items/<name>.png or .../blocks/<name>.png.

Usage: icon-check.py <mod.jar> <namespace>
Prints the unresolved names and the classes that request them; exit code 1 when there are any.
Icons registered from computed names (string concatenation, 1.19.2 code-driven item models) are not literals: check
those at runtime (the client log's "missing textures" list).
"""
import re
import struct
import sys
import zipfile

ICON_METHODS = {"setTextureName", "func_111206_d", "setBlockTextureName", "func_149658_d", "registerIcon",
                "func_94245_a"}
REGISTER_ICON = {"registerIcon", "func_94245_a"}


def parse(data):
    """(constant pool as {index: (tag, value)}, [code bytes of each method]) of a class file."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return {}, []
    count = struct.unpack(">H", data[8:10])[0]
    cp, i, idx = {}, 10, 1
    while idx < count:
        tag = data[i]
        if tag == 1:
            n = struct.unpack(">H", data[i + 1:i + 3])[0]
            cp[idx] = (1, data[i + 3:i + 3 + n].decode("utf-8", "replace"))
            i += 3 + n
        elif tag in (7, 8, 16, 19, 20):
            cp[idx] = (tag, struct.unpack(">H", data[i + 1:i + 3])[0])
            i += 3
        elif tag in (9, 10, 11, 12):
            cp[idx] = (tag, struct.unpack(">HH", data[i + 1:i + 5]))
            i += 5
        elif tag in (3, 4, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9
            idx += 1
        elif tag == 15:
            i += 4
        else:
            return cp, []
        idx += 1
    i += 6
    n_if = struct.unpack(">H", data[i:i + 2])[0]
    i += 2 + 2 * n_if
    codes = []
    for members in range(2):  # fields, methods
        n = struct.unpack(">H", data[i:i + 2])[0]
        i += 2
        for _ in range(n):
            i += 6
            n_attr = struct.unpack(">H", data[i:i + 2])[0]
            i += 2
            for _ in range(n_attr):
                name_idx, length = struct.unpack(">HI", data[i:i + 6])
                if members == 1 and cp.get(name_idx, (0, ""))[1] == "Code":
                    code_len = struct.unpack(">I", data[i + 10:i + 14])[0]
                    codes.append(data[i + 14:i + 14 + code_len])
                i += 6 + length
    return cp, codes


def method_name(cp, ref):
    entry = cp.get(ref)
    if not entry or entry[0] not in (10, 11):
        return None
    nat = cp.get(entry[1][1])
    return cp.get(nat[1][0], (0, None))[1] if nat else None


def requests(cp, codes, name_re):
    """(literal icon requests, String-array entries) of one class."""
    direct, table = set(), set()
    calls_register = any(t in (10, 11) and method_name(cp, k) in REGISTER_ICON for k, (t, _) in cp.items())
    for code in codes:
        for p in range(len(code) - 2):
            op = code[p]
            if op == 0x12:
                ref, nxt = code[p + 1], p + 2
            elif op == 0x13 and p + 3 < len(code):
                ref, nxt = struct.unpack(">H", code[p + 1:p + 3])[0], p + 3
            else:
                continue
            entry = cp.get(ref)
            if not entry or entry[0] != 8:
                continue
            m = name_re.match(cp.get(entry[1], (0, ""))[1])
            if not m:
                continue
            if nxt + 2 < len(code) and code[nxt] in (0xb6, 0xb7, 0xb9):
                if method_name(cp, struct.unpack(">H", code[nxt + 1:nxt + 3])[0]) in ICON_METHODS:
                    direct.add(m.group(1))
            elif nxt < len(code) and code[nxt] == 0x53 and calls_register:  # aastore into an icon name table
                table.add(m.group(1))
    return direct | table


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    jar, ns = argv
    name_re = re.compile("^" + re.escape(ns) + r":([a-z0-9_./-]+)$")
    wanted = {}
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        for entry in names:
            if entry.endswith(".class"):
                cp, codes = parse(z.read(entry))
                for n in requests(cp, codes, name_re):
                    wanted.setdefault(n, set()).add(entry[:-6].replace("/", "."))
    root = "assets/" + ns + "/textures/"
    missing = {n: c for n, c in wanted.items()
               if root + "items/" + n + ".png" not in names and root + "blocks/" + n + ".png" not in names}
    print("icon-check: %d literal icon names, %d without a texture" % (len(wanted), len(missing)))
    for n in sorted(missing):
        print("  %s:%s  (%s)" % (ns, n, ", ".join(sorted(missing[n])[:3])))
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
