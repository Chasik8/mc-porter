#!/usr/bin/env python3
"""Classify the hand-written rules of a port profile as generic (MC/Forge only) or mod-specific.

A rule is GENERIC when nothing in its match names the mod (`--mod-prefix`) and its replacement names neither
the mod nor a helper class of the profile's overlays (`<overlay package>`). Of the generic rules, `pure` ones need
only classes that the bundled shim already ships or vanilla/Forge 1.7.10; `needs-helper` ones call a profile overlay
helper that would have to move to the shim first.

Usage: classify_rules.py --rules <dir> [--rules <dir>...] --mod-prefix <pkg.> --helper-prefix <pkg.>
                         [--shim-src rewrite/shim/src/adapters/java/dev/porter/shim] [--json out.json]
No mod content is stored in this script: prefixes come from the command line.
"""
import argparse, collections, json, os, re, sys
import yaml


def iter_rules(paths):
    for p in paths:
        files = []
        if os.path.isdir(p):
            for d, _, fs in os.walk(p):
                files += [os.path.join(d, f) for f in sorted(fs) if f.endswith('.yaml')]
        else:
            files.append(p)
        for f in files:
            with open(f, encoding='utf8') as fh:
                for doc in yaml.safe_load_all(fh):
                    if isinstance(doc, dict) and 'match' in doc:
                        yield f, doc


def texts(doc):
    m = doc.get('match', {})
    mt = ' '.join(str(v) for v in m.values())
    body = ' '.join(str(doc.get(k, '')) for k in ('replace', 'initializer', 'body', 'addMembers', 'appendMembers', 'addSupertypes'))
    e = doc.get('edit')
    if isinstance(e, dict):
        body += ' ' + ' '.join(str(v) for v in e.values())
    return mt, body


def shim_classes(shim_src):
    out = set()
    if shim_src:
        for d, _, fs in os.walk(shim_src):
            out |= {f[:-5] for f in fs if f.endswith('.java')}
    return out


def classify(doc, mod, helper, shim):
    mt, body = texts(doc)
    blob = yaml.dump({k: v for k, v in doc.items() if k not in ('provenance', 'description', 'tests')})
    blob = blob.replace(helper + 'ported.shim.', 'dev.porter.shim.')
    word = mod.rstrip('.').lower()
    if re.search(r'(?i)' + re.escape(word) + r'(?![a-z])', blob.replace(helper, 'HELPER.')):
        return 'mod-specific', 'rule names the mod'
    hs0 = sorted(set(re.findall(re.escape(helper) + r'[\w.]*?\.([A-Z]\w*)', blob.replace(helper + 'ported.shim.', 'dev.porter.shim.'))))
    if hs0:
        return 'needs-helper', ','.join(hs0)
    if mod in mt:
        return 'mod-specific', 'match names the mod'
    if re.search(re.escape(mod.rstrip('.')) + r'\.', body):
        return 'mod-specific', 'replacement names the mod'
    hs = sorted(set(re.findall(re.escape(helper) + r'[\w.]*?\.([A-Z]\w*)', body)))
    if hs:
        return 'needs-helper', ','.join(hs)
    for sc in re.findall(r'dev\.porter\.shim\.[\w.]*?([A-Z]\w*)', body):
        if sc not in shim:
            return 'needs-helper', sc
    bare = sorted(set(re.findall(r'\b([A-Z][A-Za-z0-9]+)\.(?:[a-z]\w*\(|[A-Z_0-9]+\b)', body)))
    unknown = [b for b in bare if b not in shim and b not in KNOWN_1710]
    if unknown:
        return 'needs-helper', ','.join(unknown)
    return 'pure', ''


KNOWN_1710 = {'Blocks', 'Items', 'Block', 'Item', 'ItemStack', 'Math', 'String', 'Objects', 'Integer', 'Long', 'Double', 'Float',
              'Boolean', 'Arrays', 'Collections', 'List', 'Map', 'Optional', 'Side', 'FMLCommonHandler', 'Vec3', 'ResourceLocation',
              'NBTTagByte', 'NBTTagFloat', 'NBTTagInt', 'NBTTagString', 'EnumEnchantmentType', 'Enchantment', 'Potion', 'Material',
              'EnumFacing', 'MathHelper', 'ForgeDirection', 'OreDictionary', 'FluidRegistry', 'Fluid', 'FluidStack', 'Loader',
              'GameRegistry', 'AxisAlignedBB', 'ChunkCoordinates', 'World', 'Entity', 'EntityPlayer'}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rules', action='append', required=True)
    ap.add_argument('--mod-prefix', required=True)
    ap.add_argument('--helper-prefix', required=True)
    ap.add_argument('--shim-src')
    ap.add_argument('--json')
    a = ap.parse_args()
    shim = shim_classes(a.shim_src)
    rows = []
    for f, doc in iter_rules(a.rules):
        cls, why = classify(doc, a.mod_prefix, a.helper_prefix, shim)
        rows.append({'file': os.path.basename(f), 'id': doc.get('id'), 'kind': doc.get('kind'), 'class': cls, 'why': why,
                     'match': '+'.join(doc['match'].keys())})
    c = collections.Counter(r['class'] for r in rows)
    print('rules %d: %s' % (len(rows), dict(c)))
    by = collections.defaultdict(collections.Counter)
    for r in rows:
        by[r['file']][r['class']] += 1
    for f, cc in sorted(by.items()):
        print('  %-40s %s' % (f, dict(cc)))
    if a.json:
        with open(a.json, 'w', encoding='utf8') as fh:
            json.dump(rows, fh, indent=1)


if __name__ == '__main__':
    main()
