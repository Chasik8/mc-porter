#!/usr/bin/env python3
"""scengen: writes functional testkit scenarios for machines from the extracted IR.

  scengen.py --ir <file|dir>[,...] --machines <machines.yaml|json> --out <dir>
             [--ids <idmap>] [--known <registry names, one per line>] [--tags <tags.yaml|json>]
             [--group N] [--prefix NAME] [--only RE]

What it generates (every case = one machine at its own position, `spacing` blocks apart, `group` cases per scenario;
one `porter test run --scenario <dir>` runs a whole directory in one server launch):

  process   "machine processes recipe": for every machine of the table, the IR recipes it handles are selected
            (`handles`: recipe type, custom type or the IR `machines` field), a few of them are picked (`max`,
            spread over the list), and each becomes: place the machine, power it (`insert_energy` of one packet at
            the machine's voltage per tick), fill the recipe's fluids, insert the ingredients into the machine's input
            slots, tick, assert that the result item is in one of the machine's output slots (the fluid in its tank) afterwards.
            Input and output slots come from the IR `menu` node (slots without a role left of the progress bar are
            inputs, right of it outputs; override with `inputs` / `outputs` in the table).
  storage   energy storage: feed packets, assert the stored energy (probe `stored`) is above zero afterwards.
  bench     charging bench: an empty battery item in `slot` is charged (the stack gets `charge` NBT).
  station   discharging station: a full battery item is drained into the machine (stored energy > 0).
  generator fuel in (fluid and/or items), no energy in: assert it produces (probe `produces` > 0, default stored).
  raw       a case in the case format of the spec (see below), written as is.

Recipes whose ids cannot be resolved are skipped and listed in `scengen-report.json` (ids that the id map or the
`--known` registry dump do not know, tags without an entry in `--tags`, more ingredients than input slots, ...), so
the generator never writes a scenario that cannot work by construction.

Machine table (YAML or JSON):

  defaults: {x0: 8, z0: 8, y: 64, spacing: 24, wait: 400, max: 3, batch: 2, voltage: 32,
             energy_times: 150, probes: {stored: "call:@/getStoredEU", produces: "call:@/getStoredEU"}}
  tiers: {lv: 32, mv: 128, hv: 512, ev: 2048, iv: 8192}        # optional, name -> voltage
  fallbacks: [{from: "examplemod:", to: "hostmod:"}]   # IR id prefix -> the name the game registers (checked against --known)
  machines:
    - machine: "mod:blast_furnace"           # block id used in the scenario (mapped by the id map)
      kind: process                          # process (default) | storage | bench | station | generator | raw
      tier: lv                               # or voltage: 32
      menu: "examplemod:blast_furnace"              # IR menu node (default: the machine id)
      handles: [{type: blasting}, {custom: "examplemod:macerator"}, {machine: "examplemod:sawmill"}]
      inputs: [1]  outputs: [2]              # slot overrides
      max: 3  batch: 2  wait: 400  energy_times: 150
      fluids: [{fluid: "minecraft:water", amount: 4000}]   # extra fluids for every case of the machine
      exclude: ["examplemod:some_recipe_id"]        # recipe ids to leave out
      extra_recipes: [{id: "log_to_coal", ingredients: [{item: "minecraft:log"}], result: {item: "minecraft:coal"}}]
                                             # recipes the IR does not carry (same shape as IR recipe nodes)
      extra_items: [{item: "minecraft:coal", count: 8, slot: 0}]   # inserted in every case (fuel, upgrades)
      no_energy: true                        # fuel machines: no insert_energy
      state: {facing: north}                 # copied into place_block
      first: true                            # the case runs in a leading scenario (world clock: daylight for solar panels)
    - machine: "mod:charging_bench_mv" kind: bench battery_empty: "alias:battery_empty" slot: 0 voltage: 128
    - machine: "mod:battery_station_mv" kind: station battery_full: "alias:battery_full" slot: 0
    - machine: "mod:liquid_generator" kind: generator fuel: {fluid: "mod:bio_fuel", amount: 4000} wait: 100
    - id: "pipe-1" kind: raw pre: [...] post: [...] wait: 200 samples: [...] expect: [...]   # raw/spec case

A raw case (and the fields kind=generator also accepts) follows the generic case format: `pre`, `block`, `state`,
`fluids`, `items` [{item, count, slot, side}], `energy` {per, times}, `post`, `wait`, `samples`, `expect` (boolean
expressions, `@` = the case origin, `@[dx,dy,dz]` an offset), `expect_contains` {key, values} (one `contains(key, 'value')`
assert per value), `expect_inv` (substrings of the inventory dump), `each`
(template over a list of values, `{v}` replaced, or over a table `{name: {field: ...}}` with `{v.field}`). Raw steps in
`pre`/`post` are testkit steps, their `at` an offset from the origin; `place_box {from, to, block}` and
`place_ring {y, radius, block}` expand to `place_block` steps.
"""
import json
import re
import sys
from pathlib import Path

DEFAULTS = {"x0": 8, "z0": 8, "y": 64, "spacing": 24, "wait": 400, "max": 3, "batch": 2, "voltage": 32,
            "energy_times": 150,
            "probes": {"stored": "call:@/getStoredEU", "produces": "call:@/getStoredEU"}}
TIERS = {"lv": 32, "mv": 128, "hv": 512, "ev": 2048, "iv": 8192}
CASE_ID = re.compile(r"[^a-z0-9_-]+")


# ---------------------------------------------------------------- loading

def load_data(path):
    text = Path(path).read_text(encoding="utf-8")
    if str(path).endswith(".json"):
        return json.loads(text)
    import yaml  # PyYAML, only for YAML inputs

    return yaml.safe_load(text)


def load_nodes(spec):
    """IR nodes of one or several files / directories (`*.ir.json`, `{nodes: [...]}`), in file order."""
    nodes = []
    for part in [p for p in str(spec).split(",") if p]:
        p = Path(part)
        files = sorted(p.glob("*.ir.json")) if p.is_dir() else [p]
        for f in files:
            data = json.loads(f.read_text(encoding="utf-8"))
            nodes.extend(data["nodes"] if isinstance(data, dict) else data)
    return nodes


def load_known(path):
    if not path:
        return None
    text = Path(path).read_text(encoding="utf-8")
    if str(path).endswith(".json"):
        data = json.loads(text)
        names = data if isinstance(data, list) else [n for v in data.values() for n in v]
        return set(names)
    return {ln.strip() for ln in text.splitlines() if ln.strip() and not ln.startswith("#")}


# ---------------------------------------------------------------- id resolution

class Resolver:
    """Which IR ids can a scenario use, and under which name? An id is usable as it is if the id map maps it or it is an
    `alias:` id, or if it is in the known registry names (then the runner passes it through). Otherwise the `fallbacks`
    ({from, to} prefix rewrites, e.g. the host's own namespace) are tried against the known names and the rewritten
    name is used in the scenario. No id map and no known list = every id is usable as it is (synthetic tests)."""

    def __init__(self, ids=None, known=None, tags=None, fallbacks=None):
        self.ids = ids or {}
        self.known = known
        self.tags = tags or {}
        self.fallbacks = fallbacks or []
        self.mapped = set()
        for table in ("blocks", "items", "fluids"):
            self.mapped.update((self.ids.get(table) or {}).keys())

    def resolve(self, rid):
        """scenario id for an IR id, or None."""
        if rid.startswith("alias:") or rid in self.mapped:
            return rid
        if self.known is None:
            return rid if not self.ids else None
        if rid in self.known:
            return rid
        for fb in self.fallbacks:
            if rid.startswith(fb["from"]):
                cand = fb["to"] + rid[len(fb["from"]):]
                if cand in self.known:
                    return cand
        return None

    def item(self, ref):
        """ingredient / result node -> (scenario item id, None) or (None, reason)."""
        if not ref:
            return None, "empty"
        if "item" in ref:
            sid = self.resolve(ref["item"])
            return (sid, None) if sid else (None, "unknown item " + ref["item"])
        if "tag" in ref:
            for cand in self.tags.get(ref["tag"], []):
                sid = self.resolve(cand)
                if sid:
                    return sid, None
            return None, "no item for tag " + ref["tag"]
        return None, "unsupported ingredient " + json.dumps(ref)

    def fluid(self, rid):
        if rid in ("minecraft:water", "minecraft:lava"):
            return rid, None
        sid = self.resolve(rid)
        return (sid, None) if sid else (None, "unknown fluid " + rid)


# ---------------------------------------------------------------- recipe selection

def recipes_for(entry, recipes):
    """recipe nodes a machine table entry handles, in IR order."""
    out = []
    for r in recipes:
        for h in entry.get("handles", []):
            if ("type" in h and r.get("recipeType") == h["type"]) \
                    or ("custom" in h and r.get("customType") == h["custom"]) \
                    or ("machine" in h and h["machine"] in (r.get("machines") or [])):
                out.append(r)
                break
    return out


def spread(items, n):
    """n items spread evenly over the list (first and last included), all of them if n >= len."""
    if n <= 0 or not items:
        return []
    if n >= len(items):
        return list(items)
    if n == 1:
        return [items[0]]
    idx = sorted({round(i * (len(items) - 1) / (n - 1)) for i in range(n)})
    return [items[i] for i in idx]


def slots_from_menu(menu):
    """(inputs, outputs) slot indexes of a menu node: slots without a role, left / right of the progress bar."""
    if not menu:
        return [], []
    bars = [b for b in menu.get("bars", []) if b.get("value") == "progress" or b.get("id") == "progress"]
    if not bars:
        return [], []
    bar = bars[0]
    ins, outs = [], []
    for s in menu.get("slots", []):
        if s.get("role"):
            continue
        (outs if s["x"] >= bar["x"] + bar.get("width", 0) else ins).append(s["index"])
    return sorted(ins), sorted(outs)


def case_id(text):
    return CASE_ID.sub("-", text.lower()).strip("-")[:60] or "case"


def process_cases(entry, recipes, menus, resolver, d, report):
    """cases + skip notes for one process machine."""
    mid = entry["machine"]
    menu = menus.get(entry.get("menu") or mid)
    menu_in, menu_out = slots_from_menu(menu)
    inputs = entry.get("inputs", menu_in)
    cands = []
    own = [dict(r, kind="recipe", machines=[mid]) for r in entry.get("extra_recipes", [])]
    for r in own + recipes_for(entry, recipes):
        rid = r.get("id", "?")
        if rid in entry.get("exclude", []):
            continue
        reason = None
        ings = list(r.get("ingredients") or [])
        if len(ings) > len(inputs):
            reason = "%d ingredients, %d input slots" % (len(ings), len(inputs))
        items, post_items = [], []
        for i, ing in enumerate(ings if not reason else []):
            item, why = resolver.item(ing)
            if why:
                reason = why
                break
            items.append({"item": item, "count": max(1, int(ing.get("count", 1))) * entry.get("batch", d["batch"]),
                          "slot": inputs[i]})
        result = None
        res = r.get("result") or (r.get("results") or [None])[0]
        fres = (r.get("fluidResults") or [None])[0]
        if not reason:
            if res:
                result, why = resolver.item(res)
                reason = why
            elif fres:
                result, why = resolver.fluid(fres["fluid"])
                reason = why
            else:
                reason = "no result"
        items += [dict(i) for i in entry.get("extra_items", [])]
        fluids = [dict(f) for f in entry.get("fluids", [])]
        if not reason:
            for f in r.get("fluidIngredients") or []:
                fid, why = resolver.fluid(f["fluid"])
                if why:
                    reason = why
                    break
                fluids.append({"fluid": fid, "amount": f["amount"]})
        if not reason and res and not fres and any(i["item"] == result for i in items):
            reason = "result equals an ingredient"
        if reason:
            report["skipped"].append({"machine": mid, "recipe": rid, "reason": reason})
            continue
        t = r.get("cookingTime") or r.get("duration") or 0
        wait = entry.get("wait", max(d["wait"], min(4 * int(t), 3000)))
        cands.append({"id": case_id("%s-%s" % (mid.split(":")[-1], rid.split(":")[-1])),
                      "kind": "process", "block": mid, "state": entry.get("state"), "items": items,
                      "fluids": fluids, "wait": wait,
                      "energy": {"per": voltage(entry, d),
                             "times": 0 if entry.get("no_energy") else min(wait, entry.get("energy_times", d["energy_times"]))},
                      "expect": [result_expect(result, bool(fres and not res), entry.get("outputs", menu_out))],
                      "_recipe": rid, "_result": result, "_outputs": entry.get("outputs", menu_out)})
    return spread(cands, entry.get("max", d["max"]))


def result_expect(result, is_fluid, outputs):
    """the assert for a recipe result: in one of the output slots (the whole inventory dump also holds the ingredients,
    so an id that is a prefix of an ingredient id - ore / ore dust - would pass without anything being made)."""
    if is_fluid:
        return "contains(fluid:@, '%s')" % result
    if not outputs:
        return "contains(inv:@, '%s')" % result
    return "(" + " || ".join("contains(item:@#%d, '%s')" % (o, result) for o in outputs) + ")"


def voltage(entry, d):
    if "voltage" in entry:
        return entry["voltage"]
    return d["tiers"].get(str(entry.get("tier", "")).lower(), d["voltage"])


def table_case(entry, d):
    """storage / bench / station / generator entries -> a case in the generic format."""
    kind = entry.get("kind", "process")
    mid = entry["machine"]
    probes = d["probes"]
    stored = entry.get("stored", probes["stored"])
    v = voltage(entry, d)
    c = {"id": entry.get("id") or case_id("%s-%s" % (kind, mid.split(":")[-1])), "kind": "produce", "block": mid,
         "state": entry.get("state"), "wait": entry.get("wait", 40), "fluids": entry.get("fluids", []),
         "items": [dict(i) for i in entry.get("items", [])], "samples": [stored]}
    times = entry.get("energy_times", 40)
    if kind == "storage":
        c["energy"] = {"per": v, "times": times}
        c["expect"] = [stored + " > 0"]
    elif kind == "bench":
        slot = entry.get("slot", 0)
        c["items"].append({"item": entry["battery_empty"], "slot": slot})
        c["energy"] = {"per": v, "times": times}
        c["samples"] += ["item:@#%d" % slot]
        c["expect"] = ["contains(item:@#%d, 'charge')" % slot]
    elif kind == "station":
        slot = entry.get("slot", 0)
        c["items"].append({"item": entry["battery_full"], "slot": slot})
        c["energy"] = {"times": 0}
        c["samples"] += ["inv:@"]
        c["expect"] = [stored + " > 0"]
    elif kind == "generator":
        fuel = entry.get("fuel") or {}
        if "fluid" in fuel:
            c["fluids"] = c["fluids"] + [{"fluid": fuel["fluid"], "amount": fuel.get("amount", 4000)}]
        if "item" in fuel:
            c["items"].append({"item": fuel["item"], "count": fuel.get("count", 4), "slot": fuel.get("slot", 0)})
        produces = entry.get("produces", probes["produces"])
        c["energy"] = {"times": 0}
        c["samples"] = [produces] + entry.get("samples", [])
        c["expect"] = [produces + " > 0"]
    else:
        raise ValueError("unknown kind " + kind)
    if "expect" in entry:
        c["expect"] = entry["expect"]
    if "samples" in entry and kind != "generator":
        c["samples"] += entry["samples"]
    return c


# ---------------------------------------------------------------- case -> steps

def pos_text(o, dd=(0, 0, 0)):
    return "[%d,%d,%d]" % (o[0] + dd[0], o[1] + dd[1], o[2] + dd[2])


def subst(value, o):
    """'@' = the case origin, '@[dx,dy,dz]' an offset position; recursively; `at` lists are offsets."""
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


def _fill(value, key, data):
    """`{v}` -> the key; `{v.name}` -> data[name] (a string that is only `{v.name}` keeps the value's type)."""
    if isinstance(value, str):
        m = re.fullmatch(r"\{v\.(\w+)\}", value)
        if m and isinstance(data, dict):
            return data[m.group(1)]
        value = value.replace("{v}", str(key))
        if isinstance(data, dict):
            value = re.sub(r"\{v\.(\w+)\}", lambda mm: str(data[mm.group(1)]), value)
        return value
    if isinstance(value, list):
        return [_fill(v, key, data) for v in value]
    if isinstance(value, dict):
        return {k: _fill(v, key, data) for k, v in value.items()}
    return value


def expand(cases):
    """a case with `each` is a template, one case per value: `each: [a, b]` replaces `{v}` in every string;
    `each: {a: {fx: [...]}, b: {...}}` also gives `{v.fx}` (the value's field; a whole-string `{v.fx}` keeps its type)."""
    out = []
    for c in cases:
        if "each" not in c:
            out.append(c)
            continue
        base = {k: v for k, v in c.items() if k != "each"}
        values = c["each"].items() if isinstance(c["each"], dict) else [(v, None) for v in c["each"]]
        for key, data in values:
            out.append(_fill(base, key, data))
    return out


def expand_steps(steps):
    """`place_box {from, to, block}` and `place_ring {y, radius, block}` (offsets from the origin) -> place_block steps;
    `repeat {times, steps}` -> the steps N times."""
    out = []
    for st in steps:
        if "place_box" in st:
            b = st["place_box"]
            lo = [min(a, c) for a, c in zip(b["from"], b["to"])]
            hi = [max(a, c) for a, c in zip(b["from"], b["to"])]
            for x in range(lo[0], hi[0] + 1):
                for y in range(lo[1], hi[1] + 1):
                    for z in range(lo[2], hi[2] + 1):
                        out.append({"place_block": {"at": [x, y, z], "block": b["block"]}})
        elif "repeat" in st:
            r = st["repeat"]
            out.extend(expand_steps(list(r["steps"]) * int(r["times"])))
        elif "place_ring" in st:
            r = st["place_ring"]
            n = r["radius"]
            for x in range(-n, n + 1):
                for z in range(-n, n + 1):
                    if max(abs(x), abs(z)) == n:
                        out.append({"place_block": {"at": [x, r.get("y", 0), z], "block": r["block"]}})
        else:
            out.append(st)
    return out


def case_steps(case, d, index):
    o = (d["x0"] + index * d["spacing"], d["y"], d["z0"])
    at = list(o)
    steps = [subst(s, o) for s in expand_steps(case.get("pre", []))]
    if case.get("block"):
        place = {"at": at, "block": case["block"]}
        if case.get("state"):
            place.update(case["state"])
        steps.append({"place_block": place})
    steps.append({"tick": 2})
    for f in case.get("fluids", []):
        steps.append({"fill_fluid": {"at": at, "fluid": f["fluid"], "amount": f["amount"]}})
    # items first: the power packets follow, so a long power run (thousands of ticks) overlaps the processing instead of
    # filling the buffer and being lost before the input exists
    for it in case.get("items", []):
        step = {"at": at, "item": it["item"], "count": it.get("count", 1)}
        for k in ("slot", "side"):
            if k in it:
                step[k] = it[k]
        steps.append({"insert_item": step})
    energy = case.get("energy")
    if energy and energy.get("times", 0) > 0:
        for _ in range(energy["times"]):
            steps.append({"insert_energy": {"at": at, "amount": energy["per"]}})
            steps.append({"tick": 1})
    steps += [subst(s, o) for s in expand_steps(case.get("post", []))]
    steps.append({"tick": case.get("wait", d["wait"])})
    what = ["inv:" + pos_text(o)] if case.get("expect_inv") else []
    what += [subst(s, o) for s in case.get("samples", [])]
    if what:
        steps.append({"sample": {"what": list(dict.fromkeys(what))}})
    for sub in case.get("expect_inv", []):
        steps.append({"assert": {"expr": "contains(inv:%s, '%s')" % (pos_text(o), sub),
                                 "message": "%s: %s produced %s" % (case["id"], case.get("block", ""), sub)}})
    ec = case.get("expect_contains")
    if ec:
        for val in ec["values"]:
            steps.append({"assert": {"expr": "contains(%s, '%s')" % (subst(ec["key"], o), val),
                                     "message": "%s: %s has %s" % (case["id"], subst(ec["key"], o), val)}})
    for e in case.get("expect", []):
        steps.append({"assert": {"expr": subst(e, o), "message": "%s: %s" % (case["id"], subst(e, o))}})
    return steps


# ---------------------------------------------------------------- driver

def generate(nodes, table, resolver, only=None):
    """-> (cases, report). `nodes` = all IR nodes, `table` = the machine table."""
    d = json.loads(json.dumps(DEFAULTS))
    d.update({k: v for k, v in (table.get("defaults") or {}).items() if k != "probes"})
    d["probes"].update((table.get("defaults") or {}).get("probes", {}))
    d["tiers"] = dict(TIERS, **(table.get("tiers") or {}))
    recipes = [n for n in nodes if n.get("kind") == "recipe"]
    menus = {n["id"]: n for n in nodes if n.get("kind") == "menu"}
    report = {"skipped": [], "machines": {}}
    cases = []
    for entry in table["machines"]:
        kind = entry.get("kind", "process")
        name = entry.get("machine") or entry.get("id")
        if only and not re.search(only, name):
            continue
        before = len(cases)
        if kind == "process":
            got = process_cases(entry, recipes, menus, resolver, d, report)
            n_total = len(recipes_for(entry, recipes))
            report["machines"][name] = {"kind": kind, "recipes": n_total, "cases": len(got)}
            if not got:
                report["skipped"].append({"machine": name, "recipe": None,
                                          "reason": "no usable recipe (%d handled)" % n_total})
            cases += got
        elif kind == "raw":
            got = expand([entry])
            report["machines"][name] = {"kind": kind, "cases": len(got)}
            cases += got
        else:
            cases.append(table_case(entry, d))
            report["machines"][name] = {"kind": kind, "cases": 1}
        if entry.get("first"):
            for c in cases[before:]:
                c["_first"] = True
    seen = set()
    for c in cases:  # unique ids
        base, n = c["id"], 2
        while c["id"] in seen:
            c["id"] = "%s-%d" % (base, n)
            n += 1
        seen.add(c["id"])
    return cases, report, d


def load_schema():
    path = Path(__file__).resolve().parents[2] / "contracts" / "scenario.schema.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None


def lint_steps(steps, schema):
    """problems of generated steps against contracts/scenario.schema.json: unknown step types, resource ids that do not
    match the schema's pattern (e.g. upper case host ids: map them through the id map instead), bad positions."""
    if not schema:
        return []
    defs = schema["$defs"]
    pattern = re.compile(defs["resourceId"]["pattern"])
    known = defs["step"]["properties"]
    problems = []
    for i, st in enumerate(steps):
        if len(st) != 1 or next(iter(st)) not in known:
            problems.append("step %d: unknown or compound step %s" % (i, json.dumps(st)[:80]))
            continue
        kind, body = next(iter(st.items()))
        props = known[kind].get("properties", {})
        if not isinstance(body, dict):
            continue
        for key, val in body.items():
            ref = (props.get(key) or {}).get("$ref", "")
            if ref.endswith("/resourceId") and not (isinstance(val, str) and pattern.match(val)):
                problems.append("step %d %s.%s: id %r does not match the schema pattern" % (i, kind, key, val))
            if ref.endswith("/pos") and not (isinstance(val, list) and len(val) == 3
                                               and all(isinstance(n, int) for n in val)):
                problems.append("step %d %s.%s: bad position %r" % (i, kind, key, val))
    return problems


def write(cases, d, out_dir, group, prefix):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    def split(part):
        return [part] if group <= 0 else [part[i:i + group] for i in range(0, len(part), group)]

    # cases marked `first` (daylight, anything that depends on the world clock) lead the run in scenarios of their own
    chunks = split([c for c in cases if c.get("_first")]) + split([c for c in cases if not c.get("_first")])
    chunks = [c for c in chunks if c]
    written = []
    for n, chunk in enumerate(chunks):
        steps = []
        for i, c in enumerate(chunk):
            steps += case_steps(c, d, i)
        name = "%s-%02d" % (prefix, n)
        problems = lint_steps(steps, load_schema())
        if problems:
            raise ValueError("%s: %d problem(s): %s" % (name, len(problems), "; ".join(sorted(set(problems))[:20])))
        header = {"schemaVersion": 1, "id": name,
                  "description": "generated by scengen: " + ", ".join(c["id"] for c in chunk),
                  "world": "flat-void", "maxTicks": 400000, "tolerance": {"*": {"abs": 0}}}
        lines = ["# generated by testkit/scengen/scengen.py - do not edit"]
        for k, v in header.items():
            lines.append("%s: %s" % (k, json.dumps(v)))
        lines.append("steps:")
        lines += ["  - " + json.dumps(s) for s in steps]
        path = out_dir / (name + ".scenario.yaml")
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        written.append(path.name)
    return written


def main(argv):
    opts = {"--ir": None, "--machines": None, "--out": None, "--ids": None, "--known": None, "--tags": None,
            "--group": "5", "--prefix": "gen", "--only": None}
    i = 0
    while i < len(argv):
        if argv[i] not in opts or i + 1 >= len(argv):
            print(__doc__)
            return 2
        opts[argv[i]] = argv[i + 1]
        i += 2
    if not (opts["--ir"] and opts["--machines"] and opts["--out"]):
        print(__doc__)
        return 2
    nodes = load_nodes(opts["--ir"])
    table = load_data(opts["--machines"])
    resolver = Resolver(load_data(opts["--ids"]) if opts["--ids"] else None, load_known(opts["--known"]),
                        load_data(opts["--tags"]) if opts["--tags"] else None, table.get("fallbacks"))
    cases, report, d = generate(nodes, table, resolver, opts["--only"])
    written = write(cases, d, opts["--out"], int(opts["--group"]), opts["--prefix"])
    report["scenarios"] = written
    report["cases"] = [{"id": c["id"], "block": c.get("block"), "recipe": c.get("_recipe")} for c in cases]
    (Path(opts["--out"]) / "scengen-report.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
    print("%d cases -> %d scenario(s) in %s; %d recipe(s) skipped" % (len(cases), len(written), opts["--out"],
                                                                      len(report["skipped"])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
