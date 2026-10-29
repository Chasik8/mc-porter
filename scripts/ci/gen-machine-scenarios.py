#!/usr/bin/env python3
"""Writes functional testkit scenarios for machines from a table (a spec file), so a mod's machines are checked in a
few server launches instead of one hand-written scenario each.

  gen-machine-scenarios.py <spec.yaml|spec.json> <out-dir> [--group N] [--prefix NAME]

Spec (YAML or JSON):
  defaults: {x0: 8, z0: 8, y: 64, spacing: 24, energy: {per: 2048, times: 8}, wait: 200}
  cases:
    - id: ebf                     # unique, [a-z0-9_-]
      kind: process               # process | produce | raw
      block: "mod:machine"        # the machine, placed at the case origin
      state: {facing: north}      # optional, copied into place_block
      pre: [step, ...]            # steps before the machine is placed (raw step objects, see below)
      water: 8000                 # shortcut for fluids: [{fluid: minecraft:water, amount: 8000}]
      fluids: [{fluid: "minecraft:water", amount: 8000}]
      items:  [{item: "minecraft:iron_ore", count: 8, slot: 0}]
      energy: {per: 2048, times: 8}   # insert_energy amounts before the items; `times: 0` = none
      wait: 200                   # ticks to run before the check
      expect_inv: [iron_ingot]    # process: substrings the machine's inventory must contain afterwards
      expect: ["call:@/getStoredEU > 0"]   # any case: boolean expressions (assert), '@' = the case origin
      samples: ["call:@/getStoredEU"]      # extra probes sampled at the end
      post: [step, ...]           # raw steps after the items/energy, before `wait`
  kind produce = a generator or storage block: the same steps as process, but the check is `expect`/`samples`
  (a `process` case without `expect_inv` only checks `expect`). kind raw = only `pre`, the block (if any), `post`,
  `wait`, `samples` and `expect`.

A case with `each: [a, b, ...]` is a template: it expands to one case per value, with `{v}` replaced in every string
(`id: "drink-{v}"`). Raw steps are testkit steps as objects, e.g. {"place_block": {"at": [1, 0, 0], "block": "x:y"}}. Every `at` list is
an offset from the case origin (the case origin is x0 + index * spacing, y, z0); in strings `@` is the origin as
`[x,y,z]` and `@[dx,dy,dz]` an offset position. Scenarios are written as JSON (valid YAML), --group N puts N cases
into one scenario (one server launch runs a whole directory either way; groups only bound the scenario size).
"""
import json
import re
import sys
from pathlib import Path


def load(path):
    text = Path(path).read_text(encoding="utf-8")
    if path.endswith(".json"):
        return json.loads(text)
    import yaml  # PyYAML, only for YAML specs

    return yaml.safe_load(text)


def expand(cases):
    """a case with `each: [v1, v2, ...]` is a template: one case per value, `{v}` replaced in every string."""
    out = []
    for c in cases:
        if "each" not in c:
            out.append(c)
            continue
        text = json.dumps({k: v for k, v in c.items() if k != "each"})
        for v in c["each"]:
            out.append(json.loads(text.replace("{v}", str(v))))
    return out


def pos_text(o, d=(0, 0, 0)):
    return "[%d,%d,%d]" % (o[0] + d[0], o[1] + d[1], o[2] + d[2])


def subst(value, o):
    """strings: '@' and '@[dx,dy,dz]'; lists/dicts recursively; `at` handled by the caller."""
    if isinstance(value, str):
        value = re.sub(r"@\[(-?\d+),(-?\d+),(-?\d+)\]",
                       lambda m: pos_text(o, (int(m.group(1)), int(m.group(2)), int(m.group(3)))), value)
        return value.replace("@", pos_text(o))
    if isinstance(value, list):
        return [subst(v, o) for v in value]
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            if k == "at" and isinstance(v, list) and len(v) == 3 and all(isinstance(n, int) for n in v):
                out[k] = [o[0] + v[0], o[1] + v[1], o[2] + v[2]]
            else:
                out[k] = subst(v, o)
        return out
    return value


def case_steps(case, d, index):
    o = (d["x0"] + index * d["spacing"], d["y"], d["z0"])
    at = list(o)
    steps = []
    for s in case.get("pre", []):
        steps.append(subst(s, o))
    kind = case.get("kind", "process")
    if case.get("block"):
        place = {"at": at, "block": case["block"]}
        if case.get("state"):
            place.update(case["state"])
        steps.append({"place_block": place})
    steps.append({"tick": 2})
    for f in case.get("fluids", []):
        steps.append({"fill_fluid": {"at": at, "fluid": f["fluid"], "amount": f["amount"]}})
    if kind in ("process", "produce") and "fluids" not in case and case.get("water"):
        steps.append({"fill_fluid": {"at": at, "fluid": "minecraft:water", "amount": case["water"]}})
    energy = case.get("energy", d.get("energy"))
    if kind in ("process", "produce") and energy and energy.get("times", 0) > 0:
        for _ in range(energy["times"]):
            steps.append({"insert_energy": {"at": at, "amount": energy["per"]}})
            steps.append({"tick": 1})
    for it in case.get("items", []):
        step = {"at": at, "item": it["item"], "count": it.get("count", 1)}
        if "slot" in it:
            step["slot"] = it["slot"]
        if "side" in it:
            step["side"] = it["side"]
        steps.append({"insert_item": step})
    for s in case.get("post", []):
        steps.append(subst(s, o))
    steps.append({"tick": case.get("wait", d["wait"])})
    what = ["inv:" + pos_text(o)] if kind == "process" and case.get("expect_inv") else []
    what += [subst(s, o) for s in case.get("samples", [])]
    if what:
        steps.append({"sample": {"what": what}})
    for sub in case.get("expect_inv", []):
        steps.append({"assert": {"expr": "contains(inv:%s, '%s')" % (pos_text(o), sub),
                                 "message": "%s: %s produced %s" % (case["id"], case.get("block", ""), sub)}})
    for e in case.get("expect", []):
        steps.append({"assert": {"expr": subst(e, o), "message": "%s: %s" % (case["id"], e)}})
    return steps


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    group = 0
    prefix = "machines"
    for i, a in enumerate(argv):
        if a == "--group":
            group = int(argv[i + 1])
            args.remove(argv[i + 1])
        if a == "--prefix":
            prefix = argv[i + 1]
            args.remove(argv[i + 1])
    if len(args) != 2:
        print(__doc__)
        return 2
    spec = load(args[0])
    out_dir = Path(args[1])
    out_dir.mkdir(parents=True, exist_ok=True)
    d = {"x0": 8, "z0": 8, "y": 64, "spacing": 24, "wait": 200, "water": 8000,
         "energy": {"per": 2048, "times": 8}}
    d.update(spec.get("defaults", {}))
    cases = expand(spec["cases"])
    ids = [c["id"] for c in cases]
    if len(set(ids)) != len(ids):
        raise SystemExit("duplicate case ids")
    chunks = [cases] if group <= 0 else [cases[i:i + group] for i in range(0, len(cases), group)]
    written = 0
    for n, chunk in enumerate(chunks):
        steps = []
        for i, c in enumerate(chunk):
            steps += case_steps(c, d, i)
        name = "%s-%02d" % (prefix, n) if len(chunks) > 1 else prefix
        header = {"schemaVersion": 1, "id": name,
                  "description": "generated by gen-machine-scenarios.py: " + ", ".join(c["id"] for c in chunk),
                  "world": "flat-void", "maxTicks": 200000, "tolerance": {"*": {"abs": 0}}}
        lines = ["# generated by scripts/ci/gen-machine-scenarios.py - do not edit"]
        for k, v in header.items():
            lines.append("%s: %s" % (k, json.dumps(v)))
        lines.append("steps:")
        for s in steps:
            lines.append("  - " + json.dumps(s))
        (out_dir / (name + ".scenario.yaml")).write_text("\n".join(lines) + "\n", encoding="utf-8")
        written += 1
    print("%d cases -> %d scenario(s) in %s" % (len(cases), written, out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
