#!/usr/bin/env python3
"""Stopgap until a full symbol graph is available: build a symbol-graph document (contract
symbol-graph.schema.json, type=graph, no edges) for ONE Minecraft version from Mojang's official
mappings + MCPConfig joined.tsrg, so the rewriter can alias SRG names (m_1234_/f_1234_) to mojmap
names. Optionally also writes a supertypes TSV (rewriter "type facts") from the obfuscated client jar.

Usage:
  python mojmap_srg_graph.py --version 1.19.2 --mojmap client.txt --tsrg joined.tsrg \
      [--client-jar client.jar] --out x.graph.json [--supertypes x.supertypes.tsv]

Output is deterministic (sorted). Mojang mappings must not be redistributed: write outputs outside the
repository (e.g. private/runs/rewriter/).
"""
import argparse
import hashlib
import json
import struct
import zipfile

PRIM = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S', 'int': 'I', 'long': 'J',
        'float': 'F', 'double': 'D'}


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


def jtype(t):
    dims = 0
    while t.endswith('[]'):
        dims += 1
        t = t[:-2]
    d = PRIM.get(t) or 'L' + t.replace('.', '/') + ';'
    return '[' * dims + d


def parse_mojmap(path):
    """Returns classes {mojmap: obf}, members [(ownerMoj, kind, nameMoj, descMoj, obfName)]."""
    classes, members, owner = {}, [], None
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            if not line.startswith(' '):
                moj, obf = line.rstrip().rstrip(':').split(' -> ')
                classes[moj] = obf
                owner = moj
                continue
            left, obf = line.strip().split(' -> ')
            if '(' in left:
                sig = left.split(':')[-1]
                ret, rest = sig.split(' ', 1)
                name, args = rest.split('(', 1)
                args = args.rstrip(')')
                desc = '(' + ''.join(jtype(x) for x in args.split(',') if x) + ')' + jtype(ret)
                members.append((owner, 'method', name, desc, obf))
            else:
                ftype, name = left.split(' ')
                members.append((owner, 'field', name, jtype(ftype), obf))
    return classes, members


def parse_tsrg(path):
    """Returns classes {obf: srgClass}, fields {(obfOwner, obf): srg}, methods {(obfOwner, obf, obfDesc): srg}."""
    classes, fields, methods, owner = {}, {}, {}, None
    with open(path, encoding='utf-8') as f:
        next(f)
        for line in f:
            if line.startswith('\t\t'):
                continue
            p = line.strip().split(' ')
            if not line.startswith('\t'):
                owner = p[0]
                classes[owner] = p[1]
            elif len(p) == 3:
                fields[(owner, p[0])] = p[1]
            elif len(p) == 4:
                methods[(owner, p[0], p[1])] = p[2]
    return classes, fields, methods


def remap_desc(desc, cmap):
    out, i = [], 0
    while i < len(desc):
        c = desc[i]
        if c == 'L':
            j = desc.index(';', i)
            n = desc[i + 1:j]
            out.append('L' + cmap.get(n, n) + ';')
            i = j + 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def read_supers(jar, obf2moj):
    """Class-file header parse: this, super, interfaces (constant pool walk)."""
    rows = []
    with zipfile.ZipFile(jar) as z:
        for n in sorted(z.namelist()):
            if not n.endswith('.class'):
                continue
            b = z.read(n)
            if b[:4] != b'\xca\xfe\xba\xbe':
                continue
            cnt = struct.unpack('>H', b[8:10])[0]
            pool, i, k = [None] * cnt, 10, 1
            while k < cnt:
                t = b[i]
                if t == 1:
                    ln = struct.unpack('>H', b[i + 1:i + 3])[0]
                    pool[k] = ('u', b[i + 3:i + 3 + ln].decode('utf-8', 'replace'))
                    i += 3 + ln
                elif t == 7:
                    pool[k] = ('c', struct.unpack('>H', b[i + 1:i + 3])[0])
                    i += 3
                elif t in (8, 16, 19, 20):
                    i += 3
                elif t == 15:
                    i += 4
                elif t in (3, 4, 9, 10, 11, 12, 17, 18):
                    i += 5
                elif t in (5, 6):
                    i += 9
                    k += 1
                else:
                    raise ValueError('bad cp tag %d in %s' % (t, n))
                k += 1
            acc, this, sup, ic = struct.unpack('>HHHH', b[i:i + 8])
            ifs = [struct.unpack('>H', b[i + 8 + 2 * q:i + 10 + 2 * q])[0] for q in range(ic)]

            def cn(idx):
                return pool[pool[idx][1]][1] if idx else None

            def m(x):
                return obf2moj.get(x, x).replace('/', '.').replace('$', '$') if x else ''

            rows.append((m(cn(this)), m(cn(sup)), ','.join(sorted(m(cn(x)) for x in ifs)), acc))
    return sorted(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--version', required=True)
    ap.add_argument('--mojmap', required=True)
    ap.add_argument('--tsrg', required=True)
    ap.add_argument('--client-jar')
    ap.add_argument('--out', required=True)
    ap.add_argument('--supertypes')
    a = ap.parse_args()
    mclasses, members = parse_mojmap(a.mojmap)
    tclasses, tfields, tmethods = parse_tsrg(a.tsrg)
    moj2obf = {k.replace('.', '/'): v for k, v in mclasses.items()}
    obf2moj = {v: k for k, v in moj2obf.items()}
    symbols = []
    for moj in sorted(mclasses):
        obf = mclasses[moj]
        names = {'mojmap': moj, 'obf': obf}
        if obf in tclasses:
            names['srg'] = tclasses[obf].replace('/', '.')
        symbols.append({'ref': {'version': a.version, 'kind': 'class', 'name': moj},
                        'names': dict(sorted(names.items()))})
    mem = []
    for owner, kind, name, desc, obf in members:
        oo = mclasses[owner]
        if kind == 'method':
            srg = tmethods.get((oo, obf, remap_desc(desc, moj2obf)))
        else:
            srg = tfields.get((oo, obf))
        if srg is None or srg == name:
            continue  # only members whose SRG name differs need aliasing
        ref = {'version': a.version, 'kind': kind, 'owner': owner, 'name': name}
        if kind == 'method':
            ref['desc'] = desc
        else:
            ref['desc'] = desc
        mem.append((owner, kind, name, desc, {'ref': ref, 'names': {'mojmap': name, 'obf': obf, 'srg': srg}}))
    mem.sort(key=lambda t: t[:4])
    symbols += [t[4] for t in mem]
    doc = {
        'schemaVersion': 1, 'type': 'graph',
        'versions': [{'id': a.version, 'canonical': 'mojmap', 'namespaces': ['mojmap', 'obf', 'srg'], 'sources': [
            {'name': 'mojang-client-mappings', 'version': a.version, 'sha256': sha256(a.mojmap)},
            {'name': 'mcpconfig-joined-tsrg', 'version': a.version, 'sha256': sha256(a.tsrg)}]}],
        'symbols': symbols, 'edges': []}
    with open(a.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(doc, f, indent=1, ensure_ascii=False)
        f.write('\n')
    print('symbols:', len(symbols))
    if a.supertypes and a.client_jar:
        rows = read_supers(a.client_jar, obf2moj)
        with open(a.supertypes, 'w', encoding='utf-8', newline='\n') as f:
            f.write('# class\tsuper\tinterfaces\taccess (client.jar sha256 %s)\n' % sha256(a.client_jar))
            for r in rows:
                f.write('%s\t%s\t%s\t%d\n' % r)
        print('types:', len(rows))


if __name__ == '__main__':
    main()
