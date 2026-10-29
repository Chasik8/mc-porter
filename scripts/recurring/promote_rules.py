#!/usr/bin/env python3
"""Promote the generic rules of a port profile into porter's public adapter pack.

Generic = classified `pure` by classify_rules.py (match and replacement name neither the mod nor a helper of the
profile's overlays) plus the idiom rules whose match text is listed in --allow-idioms, plus `needs-helper` rules whose
helper classes are listed in --helpers-ok (their Java must already be in the shim). Rule text is moved verbatim
(comments of the rule block are kept) except the id (`r-gen-...`) and the provenance reference, which are scrubbed so
the public pack carries no mod names. The script fails if a mod word is left in the output.

  promote_rules.py --rules <dir|file>... --pack-out rules/packs/adapters/1.19.2__1.7.10 \
        --mod-word <word> --helper-prefix <pkg.> --shim-src rewrite/shim/src \
        [--allow-idioms generic-idioms.txt] [--helpers-ok A,B] [--drop-from <scratch profile rules dir>] [--report out.json]

--drop-from removes the promoted rules from a SCRATCH copy of the profile rules (never from the real profile), which is
how the redundancy of the hand rules is proven.
"""
import argparse, collections, json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import classify_rules as C
import promote_helpers as H

THEMES = [
    ('generic-vanilla-forge', lambda f, m: f == 'vanilla-forge.rule.yaml'),
    ('generic-vanilla-constants', lambda f, m: f == 'more-recipes-vanilla.rule.yaml' or re.search(r'\.(Blocks|Items|ItemTags|BlockTags|Enchantments)#', m)),
    ('generic-client-gui', lambda f, m: re.search(r'\.client\.|\.gui\.|widget|blaze3d|TextureManager|TextureAtlas', m)),
    ('generic-lib-config', lambda f, m: 'carbonconfig' in m),
    ('generic-world-entity', lambda f, m: re.search(r'\.world\.|\.core\.|\.server\.|\.entity\.|\.level\.|\.nbt\.|\.network\.|forge', m)),
]


def theme_of(fname, doc):
    m = ' '.join(str(v) for v in doc.get('match', {}).values())
    for name, test in THEMES:
        if test(fname, m):
            return name
    return 'generic-misc'


def split_docs(text):
    """Text-level split of a multi-document YAML file: [(id, text)], keeps each block verbatim."""
    parts = re.split(r'(?m)^---[ \t]*$\n?', text)
    out = []
    for p in parts:
        m = re.search(r'(?m)^id:\s*(\S+)', p)
        out.append((m.group(1) if m else None, p))
    return out


PLACEHOLDER = '''schemaVersion: 1
id: r-promoted-placeholder-%s
description: "placeholder: every rule of this file moved to the public pack"
from: forge-1.19.2
to: forge-1.7.10
kind: mechanical
match:
  type: "placeholder.unused.Type"
replace: "placeholder.unused.Type"
confidence: 0.1
provenance: { source: manual, ref: "promoted" }
tests:
  - before: "import placeholder.unused.Type;"
    after: "import placeholder.unused.Type;"
'''


def slug(s):
    return re.sub(r'[^a-z0-9]+', '-', s.lower()).strip('-')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rules', action='append', required=True)
    ap.add_argument('--pack-out', required=True)
    ap.add_argument('--mod-word', required=True)
    ap.add_argument('--mod-prefix', required=True)
    ap.add_argument('--helper-prefix', required=True)
    ap.add_argument('--shim-src')
    ap.add_argument('--allow-idioms')
    ap.add_argument('--helpers-ok', default='')
    ap.add_argument('--helpers-exclude', default='', help='helpers that need a third-party library (not Minecraft/Forge): stay in the profile')
    ap.add_argument('--overlays', help='overlay java dir of the profile (adapters/); enables moving generic helper classes')
    ap.add_argument('--shim-out', help='shim java dir the helpers move to (.../dev/porter/shim/mc)')
    ap.add_argument('--drop-overlays', help='SCRATCH copy of the overlays: remove moved helpers and re-point references')
    ap.add_argument('--drop-from')
    ap.add_argument('--drop-ids', help='extra rule ids to drop (the profile rules that override a pack rule)')
    ap.add_argument('--drop-report', help='previous --report: drop exactly its rule ids and helper classes (the pack already holds them)')
    ap.add_argument('--report')
    a = ap.parse_args()

    shim = C.shim_classes(a.shim_src)
    ok_help = {h for h in a.helpers_ok.split(',') if h}
    h_info, h_movable = ({}, set())
    if a.overlays:
        h_info, h_movable = H.analyse(a.overlays, a.helper_prefix, a.mod_word, shim, {h for h in a.helpers_exclude.split(',') if h})
        ok_help |= h_movable
        ok_help -= {h for h in a.helpers_exclude.split(',') if h}
    allow = []
    if a.allow_idioms:
        allow = [l.rstrip('\n') for l in open(a.allow_idioms, encoding='utf8') if l.strip() and not l.startswith('#')]
    def sig(d):
        m = {k: v for k, v in d.get('match', {}).items() if k != 'bind'}
        return repr(sorted(m.items(), key=str)) + '|' + str(d.get('preconditions')) 

    def norm(d):
        # replacement with the bind variables renamed by position, so '$s' and '$a0' compare equal
        names = []
        b = d.get('match', {}).get('bind') or {}
        for v in ([b.get('recv')] if b.get('recv') else []) + list(b.get('args') or []):
            names.append(v)
        r = str(d.get('replace'))
        for i, n in enumerate(names):
            r = re.sub(re.escape(n) + r'(?![\w])', '$#%d' % i, r)
        return r
    existing = {}
    for f, d in C.iter_rules([a.pack_out]):
        if not os.path.basename(f).startswith('generic-'):
            existing[sig(d)] = norm(d)
    chosen = collections.defaultdict(list)   # theme -> [(new id, text)]
    dup_equal = []
    overrides = []
    needed = set()
    moved = {}                                # old id -> (new id, theme)
    kept = collections.Counter()
    seen_new = set()
    for src, doc in C.iter_rules(a.rules):
        cls, why = C.classify(doc, a.mod_prefix, a.helper_prefix, shim)
        mt = ' '.join(str(v) for v in doc.get('match', {}).values())
        ok = cls == 'pure' and doc.get('kind') != 'idiom'
        if doc.get('kind') == 'idiom' and cls == 'pure' and any(s in mt for s in allow):
            ok = True
        if cls == 'needs-helper' and why and all(h in ok_help for h in why.split(',')):
            ok = True
            needed.update(why.split(','))
        if ok and sig(doc) in existing:
            if existing[sig(doc)] == norm(doc):
                dup_equal.append(doc['id'])
            else:
                kept['override-of-a-pack-rule (different replacement)'] += 1
                overrides.append(doc['id'])
            continue
        if not ok:
            kept[cls if cls != 'pure' else 'idiom-mod-shaped'] += 1
            continue
        fname = os.path.basename(src)
        theme = theme_of(fname, doc)
        old = doc['id']
        base = 'r-gen-' + slug(re.sub(r'^r-(more|pilot|closure)[a-z0-9]*-', '', old))
        new, n = base, 2
        while new in seen_new:
            new = '%s-%d' % (base, n)
            n += 1
        seen_new.add(new)
        text = None
        with open(src, encoding='utf8') as fh:
            for rid, t in split_docs(fh.read()):
                if rid == old:
                    text = t
        if text is None:
            print('not found', old, src); continue
        text = text.replace(a.helper_prefix + 'ported.shim.', 'dev.porter.shim.')
        text = re.sub(re.escape(a.helper_prefix) + r'ported\.compat\.more\.[a-z0-9]+\.(\w+)', r'dev.porter.shim.mc.\1', text)
        text = chr(10).join(l for l in text.split(chr(10)) if not (l.lstrip().startswith('#') and re.search('(?i)' + re.escape(a.mod_word), l)))
        text = re.sub(r'(?m)^id:\s*\S+', 'id: ' + new, text, count=1)
        text = re.sub(r'(?m)^(provenance:.*ref:\s*)"[^"]*"', r'\1"generic mapping promoted from a port profile"', text)
        chosen[theme].append((new, text.strip('\n')))
        moved[old] = (new, theme)


    # helper classes the promoted rules call: copied into the shim (flat package dev.porter.shim.mc)
    moved_helpers = []
    if a.overlays and a.shim_out and needed:
        todo, done = [n for n in sorted(needed) if n in h_info], set()
        while todo:
            n = todo.pop()
            if n in done:
                continue
            done.add(n)
            for imp in h_info[n]['imports']:
                if imp.startswith(a.helper_prefix + 'ported.compat.more.'):
                    dep = imp.split('.')[-1]
                    if dep in h_info and dep not in done:
                        todo.append(dep)
        for n in sorted(done):
            t = open(h_info[n]['path'], encoding='utf8').read()
            t = re.sub(r'(?m)^package [\w.]+;', 'package dev.porter.shim.mc;', t, count=1)
            t = t.replace(a.helper_prefix + 'ported.shim.', 'dev.porter.shim.')
            t = re.sub(re.escape(a.helper_prefix) + r'ported\.compat\.more\.[a-z0-9]+\.(\w+)', r'dev.porter.shim.mc.\1', t)
            t = re.sub(r'(?m)^import dev\.porter\.shim\.mc\.\w+;', '', t)
            t = chr(10).join(re.sub('(?i)' + re.escape(a.mod_word), 'the mod', l) if (l.lstrip().startswith(('//', '*', '/*'))) else l for l in t.split(chr(10)))
            os.makedirs(a.shim_out, exist_ok=True)
            with open(os.path.join(a.shim_out, n + '.java'), 'w', encoding='utf8', newline=chr(10)) as fh:
                fh.write(t)
            moved_helpers.append(n)
    bad = []
    os.makedirs(a.pack_out, exist_ok=True)
    for theme, items in sorted(chosen.items()):
        body = '# %s: generic Minecraft/Forge 1.19.2 -> 1.7.10 mappings promoted from a port profile (no mod names).\n' % theme
        body += '\n---\n'.join([''] + [t for _, t in items]).lstrip('\n') + '\n'
        body = body.replace('\n---\n', '\n---\n')
        if re.search('(?i)' + re.escape(a.mod_word), body):
            for line in body.splitlines():
                if re.search('(?i)' + re.escape(a.mod_word), line):
                    bad.append((theme, line.strip()[:140]))
        with open(os.path.join(a.pack_out, theme + '.rule.yaml'), 'w', encoding='utf8', newline='\n') as fh:
            fh.write(body)
    if bad:
        print('MOD WORD LEFT IN PUBLIC PACK:')
        for b in bad:
            print('  ', b)

    if a.drop_report:
        prev = json.load(open(a.drop_report, encoding='utf8'))
        moved = {k: v for k, v in prev['moved'].items()}
        dup_equal = list(prev.get('duplicates', []))
        overrides = list(prev.get('overrides', []))
        moved_helpers = list(prev.get('helpers', []))
    if a.drop_from:
        drop = set(moved) | set(dup_equal) | set(a.drop_ids.split(',') if a.drop_ids else [])
        for d, _, fs in os.walk(a.drop_from):
            for f in fs:
                if not f.endswith('.yaml'):
                    continue
                p = os.path.join(d, f)
                t = open(p, encoding='utf8').read()
                docs = split_docs(t)
                keep = [x for rid, x in docs if rid not in drop]
                if len(keep) != len(docs):
                    keep = [k for k in keep if k.strip()]
                    if not any(re.search(r'(?m)^id:', k) for k in keep):
                        keep.append(PLACEHOLDER % slug(f))
                    open(p, 'w', encoding='utf8', newline='\n').write('---\n'.join(k if k.endswith('\n') else k + '\n' for k in keep))

    if a.drop_overlays and moved_helpers:
        mh = set(moved_helpers)
        for n in moved_helpers:
            for d, _, fs in os.walk(a.drop_overlays):
                if n + '.java' in fs:
                    os.remove(os.path.join(d, n + '.java'))
        pat = re.compile(re.escape(a.helper_prefix) + r'ported\.compat\.more\.[a-z0-9]+\.(\w+)')
        roots = [a.drop_overlays] + ([a.drop_from] if a.drop_from else [])
        for root in roots:
            for d, _, fs in os.walk(root):
                for f in fs:
                    if not f.endswith(('.java', '.yaml')):
                        continue
                    p = os.path.join(d, f)
                    t = open(p, encoding='utf8').read()
                    t2 = pat.sub(lambda m: (a.helper_prefix + 'ported.shim.mc.' + m.group(1)) if m.group(1) in mh else m.group(0), t)
                    if f.endswith('.java'):
                        pk = re.search(r'(?m)^package ([\w.]+);', t2)
                        for n in sorted(mh):
                            if pk and re.search(r'\b' + n + r'\b', t2) and ('import ' + a.helper_prefix + 'ported.shim.mc.' + n + ';') not in t2:
                                # same-package user of a moved helper (no import needed before): add one
                                if not re.search(r'(?m)^import [\w.]+\.' + n + ';', t2) and pk.group(1).startswith(a.helper_prefix + 'ported.compat.more.'):
                                    t2 = t2.replace(pk.group(0), pk.group(0) + chr(10) + 'import ' + a.helper_prefix + 'ported.shim.mc.' + n + ';', 1)
                    if t2 != t:
                        open(p, 'w', encoding='utf8', newline=chr(10)).write(t2)
    summary = {'helpers_moved_to_shim': len(moved_helpers), 'promoted': len(moved), 'duplicates_of_pack_rules_removable': len(dup_equal), 'kept_in_profile': dict(kept), 'by_theme': {t: len(i) for t, i in chosen.items()},
               'mod_word_left': len(bad)}
    print(json.dumps(summary, indent=1))
    if a.report:
        json.dump({'summary': summary, 'moved': moved, 'duplicates': dup_equal, 'helpers': moved_helpers, 'overrides': overrides}, open(a.report, 'w', encoding='utf8'), indent=1)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
