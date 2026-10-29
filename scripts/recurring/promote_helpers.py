#!/usr/bin/env python3
"""Find the helper classes of a profile's overlays that are generic (no mod names, only shim or other generic helpers)
and could move into porter's adapter shim. Prints the movable set; promote_rules.py uses it for rules that call them.

  promote_helpers.py --overlays <overlays/adapters> --helper-prefix <pkg.> --mod-word <word> --shim-src rewrite/shim/src [--json out]
"""
import argparse, json, os, re, sys


def scan(overlays):
    files = {}
    for d, _, fs in os.walk(overlays):
        for f in fs:
            if f.endswith('.java'):
                files.setdefault(f[:-5], []).append(os.path.join(d, f))
    return files


def analyse(overlays, helper, word, shim_names, exclude=()):
    files = scan(overlays)
    info = {}
    for name, fl in files.items():
        t = open(fl[0], encoding='utf8').read()
        pkg = re.search(r'^package ([\w.]+);', t, re.M)
        pkg = pkg.group(1) if pkg else ''
        imps = re.findall(r'^import (?:static )?([\w.]+?)(?:\.\*)?;', t, re.M)
        probe = t.replace(helper + 'ported.shim.', 'SHIM.').replace(helper + 'ported.compat.more.', 'HELPER.')
        probe = re.sub(r'(?m)^package .*$', '', probe)
        mod_hit = []
        for l in probe.splitlines():
            code = re.sub(r'//.*$', '', l)
            if code.lstrip().startswith(('*', '/*')):
                continue
            if re.search(r'(?i)' + re.escape(word), code):
                mod_hit.append(l.strip()[:100])
        info[name] = {'path': fl[0], 'pkg': pkg, 'imports': imps, 'mod': mod_hit, 'dup': len(fl) > 1}
    movable = set()
    changed = True
    while changed:
        changed = False
        for name, i in info.items():
            if name in movable or i['mod'] or i['dup'] or name in shim_names:
                continue
            ok = True
            for imp in i['imports']:
                if imp.startswith(helper + 'ported.shim.'):
                    continue
                if imp.startswith(helper + 'ported.compat.more.'):
                    dep = imp.split('.')[-1]
                    if dep in movable or dep == name:
                        continue
                    ok = False
                elif imp.startswith(helper) or imp.startswith(word + '.'):
                    ok = False
            # same-package references without import
            for other, oi in info.items():
                if other != name and oi['pkg'] == i['pkg'] and other not in movable:
                    if re.search(r'\b' + re.escape(other) + r'\b', open(i['path'], encoding='utf8').read()):
                        ok = False
            if ok:
                movable.add(name)
                changed = True
    # a helper that names an excluded (or no longer movable) helper, by simple name or fully qualified, cannot move either
    blocked = set(exclude)
    changed = True
    while changed:
        changed = False
        for name in sorted(movable - blocked):
            t = open(info[name]['path'], encoding='utf8').read()
            if any(re.search(r'\b' + re.escape(b) + r'\b', t) for b in blocked if b != name):
                blocked.add(name)
                changed = True
    movable -= blocked
    return info, movable


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--overlays', required=True)
    ap.add_argument('--helper-prefix', required=True)
    ap.add_argument('--mod-word', required=True)
    ap.add_argument('--shim-src', required=True)
    ap.add_argument('--json')
    a = ap.parse_args()
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import classify_rules as C
    shim = C.shim_classes(a.shim_src)
    info, mv = analyse(a.overlays, a.helper_prefix, a.mod_word, shim)
    print('helpers %d, movable %d' % (len(info), len(mv)))
    for n in sorted(info):
        i = info[n]
        print('%-26s %-8s %s' % (n, 'MOVE' if n in mv else 'keep', ('collides with shim' if n in shim else '; '.join(i['mod'][:2]) if i['mod'] else ('dup name' if i['dup'] else 'depends on a kept helper'))if n not in mv else ''))
    if a.json:
        json.dump(sorted(mv), open(a.json, 'w'))
