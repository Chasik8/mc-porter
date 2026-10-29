#!/usr/bin/env python3
"""Mine the recurring error classes of a port: javac errors by normalised shape, run outcomes, counted edits and
hand-written rules grouped by shape. Numbers only; no mod content is stored here.

  mine.py --runs-dir <dir with run*.log> --compile-logs <dir|file>... --edits <manual-edits.yaml>
          --rules <dir|file>... [--json out.json] [--md out.md]
"""
import argparse, collections, glob, json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import classify_rules as C

PRIMS = {'int', 'long', 'short', 'byte', 'char', 'float', 'double', 'boolean', 'void'}


def norm_type(t):
    t = t.strip()
    t = re.sub(r'<.*>', '<..>', t)
    if t == 'Object':
        return 'Object'
    if t in PRIMS:
        return 'prim'
    if re.fullmatch(r'[A-Z]\d?', t):
        return 'TypeVar'
    if t.endswith('[]'):
        return norm_type(t[:-2]) + '[]'
    return 'Type' + ('<..>' if '<..>' in t else '')


def norm_msg(m):
    m = m.strip()
    r = re.match(r'incompatible types: (.+?) cannot be converted to (.+)$', m)
    if r:
        return 'incompatible types: %s cannot be converted to %s' % (norm_type(r.group(1)), norm_type(r.group(2)))
    r = re.match(r'incompatible types: inference variable (\w+) has incompatible bounds', m)
    if r:
        return 'incompatible types: inference variable has incompatible bounds'
    r = re.match(r'no suitable (method|constructor) found for (\w+)\((.*)\)', m)
    if r:
        args = ','.join(norm_type(x) for x in re.split(r',\s*(?![^<]*>)', r.group(3)) if x)
        return 'no suitable %s found for m(%s)' % (r.group(1), args)
    r = re.match(r'(method|constructor) .* cannot be applied to given types', m)
    if r:
        return '%s cannot be applied to given types' % r.group(1)
    if m.startswith('cannot find symbol'):
        return 'cannot find symbol'
    r = re.match(r'(.*) cannot be dereferenced', m)
    if r:
        return '%s cannot be dereferenced' % norm_type(r.group(1))
    r = re.match(r'variable (\w+) might not have been initialized', m)
    if r:
        return 'variable might not have been initialized'
    m = re.sub(r"'[^']*'", "'..'", m)
    m = re.sub(r'\b[A-Z]\w+(\.\w+)*\b', 'Type', m)
    return m[:100]


def javac_errors(paths):
    files = []
    for p in paths:
        if os.path.isdir(p):
            files += sorted(glob.glob(os.path.join(p, '*.log')))
        else:
            files.append(p)
    out = collections.Counter()
    per = {}
    for f in files:
        c = collections.Counter()
        for l in open(f, encoding='utf8', errors='ignore'):
            m = re.match(r'.*?\.java:\d+: error: (.*)', l)
            if m:
                c[norm_msg(m.group(1))] += 1
        if c:
            per[os.path.basename(f)] = sum(c.values())
            out.update(c)
    return out, per


def run_logs(d):
    stages = collections.Counter()
    fails = collections.Counter()
    castfix = collections.Counter()
    n = 0
    for f in sorted(glob.glob(os.path.join(d, 'run*.log'))):
        t = open(f, encoding='utf8', errors='ignore').read()
        if 'plan:' not in t:
            continue
        n += 1
        for m in re.finditer(r'^(\w+)\s+(ok|FAILED)\s', t, re.M):
            stages[m.group(1)] += 1
            if m.group(2) == 'FAILED':
                fails[m.group(1)] += 1
        m = re.search(r'castfix \[(\d+)', t)
        if m:
            castfix[int(m.group(1))] += 1
    return {'runs': n, 'failed_runs': sum(fails.values()), 'failed_by_stage': dict(fails), 'first_round_errors': dict(sorted(castfix.items()))}


EDIT_SHAPES = [
    ('generic cast / generic argument (T)', r'generic (cast|array|argument)'),
    ('for-each / iterator typed Object or wrong element type', r'for-each|iterator typed|wrong map entry'),
    ('raw type', r'raw '),
    ('leftover generic name', r'leftover generic'),
    ('lost declaration / loop shape / evaluation order', r'lost local|indexed loop|compound assignment'),
    ('generated block is not the logic class (valve instanceof)', r'valve blocks are not ValveBlock'),
    ('null-safety of a cut hub / missing inventory', r'cut hub|inventory is not there|not there \(yet\)|without a configured'),
    ('field/name rule did not fire in place', r'rule conflict|does not fire'),
    ('GUI/icon/sprite adaption', r'icon|sprite|texture|ArmorSlot'),
    ('host metadata / holder', r'metadata'),
    ('runtime behaviour fix', r'runtime|ClassCast|Player is an item handler|skipped instead'),
]


def edit_classes(path):
    import yaml
    d = yaml.safe_load(open(path, encoding='utf8'))
    c = collections.Counter()
    ex = {}
    for e in d.get('edits', []):
        r = e.get('reason', '')
        for name, rx in EDIT_SHAPES:
            if re.search(rx, r, re.I):
                c[name] += 1
                ex.setdefault(name, r[:90])
                break
        else:
            c['other'] += 1
            ex.setdefault('other', r[:90])
    return c, ex


def rule_shapes(paths):
    c = collections.Counter()
    ex = {}
    for f, doc in C.iter_rules(paths):
        m = doc.get('match', {})
        k = '+'.join(sorted(m.keys()))
        rep = str(doc.get('replace', ''))
        mt = ' '.join(str(v) for v in m.values())
        if 'field' in m and re.search(r'\.(Blocks|Items)#', mt):
            shape = 'vanilla constant field -> 1.7.10 constant/ItemStack/placeholder'
        elif 'field' in m and re.search(r'registries\.\w+#', mt):
            shape = 'host registry field -> holder lookup'
        elif 'field' in m:
            shape = 'field -> helper constant'
        elif 'call' in m and re.search(r'Holder#value|Optional', mt):
            shape = 'Holder#value / Optional unwrap'
        elif 'call' in m and re.fullmatch(r'\$\w+', rep.strip()):
            shape = 'call -> receiver (unwrap)'
        elif 'call' in m and re.fullmatch(r'-?\d+', rep.strip()):
            shape = 'call -> constant'
        elif 'call' in m and ('StateHolder#getValue' in mt or 'BlockState' in mt or 'setValue' in mt):
            shape = 'BlockState property -> meta'
        elif 'call' in m and re.match(r'[A-Z]\w+\.\w+\(', rep.strip()):
            shape = 'call -> static helper(recv, args)'
        elif 'call' in m:
            shape = 'call -> 1.7.10 method / expression'
        elif 'new' in m:
            shape = 'constructor -> 1.7.10 constructor / factory'
        elif 'type' in m and re.search(r'java\.lang\.Object', rep):
            shape = 'type -> Object (drop)'
        elif 'type' in m:
            shape = 'type -> type'
        elif 'declaration' in m:
            shape = 'declaration edit (' + ','.join(sorted(k2 for k2 in ('addMembers', 'appendMembers', 'addSupertypes', 'remove', 'body', 'initializer') if k2 in (doc.get('edit') or {}) or k2 in doc)) + ')'
        elif 'pattern' in m and re.search(r'\(\w+\)\s*\$', mt):
            shape = 'pattern with cast'
        elif 'pattern' in m:
            shape = 'pattern -> replacement'
        elif 'override' in m:
            shape = 'override'
        else:
            shape = k
        c[shape] += 1
        ex.setdefault(shape, (os.path.basename(f), doc.get('id')))
    return c, ex


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--runs-dir')
    ap.add_argument('--compile-logs', action='append', default=[])
    ap.add_argument('--edits')
    ap.add_argument('--rules', action='append', default=[])
    ap.add_argument('--json')
    ap.add_argument('--md')
    a = ap.parse_args()
    res = {}
    if a.runs_dir:
        res['runs'] = run_logs(a.runs_dir)
    if a.compile_logs:
        c, per = javac_errors(a.compile_logs)
        res['javac'] = {'total': sum(c.values()), 'by_shape': dict(c.most_common()), 'per_log': per}
    if a.edits:
        c, ex = edit_classes(a.edits)
        res['edits'] = {'total': sum(c.values()), 'by_class': dict(c.most_common()), 'example': ex}
    if a.rules:
        c, ex = rule_shapes(a.rules)
        res['rules'] = {'total': sum(c.values()), 'by_shape': dict(c.most_common()), 'example': {k: list(v) for k, v in ex.items()}}
    if a.json:
        json.dump(res, open(a.json, 'w', encoding='utf8'), indent=1)
    out = []
    for sec, v in res.items():
        out.append('## ' + sec)
        out.append(json.dumps(v, indent=1, ensure_ascii=False))
    text = '\n'.join(out)
    if a.md:
        open(a.md, 'w', encoding='utf8').write(text)
    print(text)


if __name__ == '__main__':
    main()
