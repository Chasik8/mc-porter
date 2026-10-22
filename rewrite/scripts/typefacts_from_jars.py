"""Supertype table (rewriter --type-facts TSV) for every class in the given jars: class, superclass, interfaces,
access flags, binary names with '$' for nested classes. Reads class-file headers only (constant pool, access,
this/super/interfaces); nothing is loaded or executed. Use it with the source-version jars a mod compiles against
(e.g. the mojmap-named Minecraft jar + the Forge universal jar), so rules declared on a supertype (LivingEvent,
LevelReader, BlockGetter, ...) apply to subtypes the bundled curated table does not list. The output is derived
from those jars: keep it out of the repository (local cache).
Usage: python -I typefacts_from_jars.py <out.tsv> <jar>... [--prefix net.minecraft. --prefix net.minecraftforge. ...]"""
import struct
import sys
import zipfile


def parse(b):
    if b[:4] != b'\xca\xfe\xba\xbe':
        return None
    n = struct.unpack('>H', b[8:10])[0]
    cp = [None] * n
    i, k = 10, 1
    while k < n:
        tag = b[i]
        if tag == 1:
            ln = struct.unpack('>H', b[i + 1:i + 3])[0]
            cp[k] = ('utf8', b[i + 3:i + 3 + ln].decode('utf-8', 'replace'))
            i += 3 + ln
        elif tag == 7:
            cp[k] = ('class', struct.unpack('>H', b[i + 1:i + 3])[0])
            i += 3
        elif tag in (8, 16, 19, 20):
            i += 3
        elif tag == 15:
            i += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9
            k += 1
        else:
            return None
        k += 1
    access, this_c, super_c, ni = struct.unpack('>HHHH', b[i:i + 8])
    ifs = [struct.unpack('>H', b[i + 8 + 2 * j:i + 10 + 2 * j])[0] for j in range(ni)]

    def cname(idx):
        if idx == 0:
            return ''
        return cp[cp[idx][1]][1].replace('/', '.')

    return cname(this_c), cname(super_c), [cname(x) for x in ifs], access


def main():
    args = sys.argv[1:]
    out = args.pop(0)
    prefixes = []
    jars = []
    while args:
        a = args.pop(0)
        if a == '--prefix':
            prefixes.append(args.pop(0))
        else:
            jars.append(a)
    rows = {}
    for j in jars:
        with zipfile.ZipFile(j) as z:
            for e in z.namelist():
                if not e.endswith('.class') or e.startswith('META-INF/') or e.endswith('module-info.class'):
                    continue
                r = parse(z.read(e))
                if r is None:
                    continue
                name, sup, ifs, acc = r
                if prefixes and not any(name.startswith(p) for p in prefixes):
                    continue
                rows.setdefault(name, (sup, ifs, acc))
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# class\tsuper\tinterfaces\taccess (typefacts_from_jars.py)\n')
        for name in sorted(rows):
            sup, ifs, acc = rows[name]
            f.write(f'{name}\t{sup}\t{",".join(ifs)}\t{acc}\n')
    print(f'{out}: {len(rows)} classes')


if __name__ == '__main__':
    main()
