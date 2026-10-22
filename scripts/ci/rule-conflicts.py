"""Fails when two call rules would be ambiguous on the same site: same member signature and priority, one of them
on java.lang.Object (the fallback for an unproven receiver) or both on the same owner, but different replacements and
no precondition telling them apart. The rewriter then leaves the call unrewritten (TODO ambiguous) and the compile
stage reports it. Regression guard for the TorchMaster lambda case (LivingEntity#broadcastBreakEvent). Usage: python -I rule-conflicts.py"""
import collections, glob, re, sys

groups = collections.defaultdict(list)
for f in sorted(glob.glob('rules/**/*.yaml', recursive=True)):
    for doc in open(f, encoding='utf8').read().split('\n---'):
        i = re.search(r'^id: (\S+)', doc, re.M)
        c = re.search(r'^\s+call: "([^"]+)"', doc, re.M)
        r = re.search(r'^replace: (.*)$', doc, re.M)
        if not (i and c and r):
            continue
        p = re.search(r'^priority: (-?\d+)', doc, re.M)
        owner, member = c.group(1).split('#', 1)
        groups[(member, int(p.group(1)) if p else 0)].append((i.group(1), owner, re.sub(r'\s', '', r.group(1))))

bad = []
for (member, prio), rules in groups.items():
    for a in range(len(rules)):
        for b in range(a + 1, len(rules)):
            x, y = rules[a], rules[b]
            if x[2] != y[2] and (x[1] == y[1] or 'java.lang.Object' in (x[1], y[1])):
                bad.append(f'{member} priority {prio}: {x[0]} vs {y[0]}')
for line in bad:
    print('ambiguous rules:', line)
sys.exit(1 if bad else 0)
