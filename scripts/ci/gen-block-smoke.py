#!/usr/bin/env python3
"""Writes one runtime smoke scenario per registered block of a mod:

  gen-block-smoke.py <names> <namespace> <out-dir> [--no-tile REGEX] [--gui REGEX] [--skip REGEX] [--entity NAME]...

<names>      a text file with one registry name per line, or a testkit trace (*.trace.json) that sampled
             `registry:blocks/<namespace>:` (the generic smoke scenario of gen-smoke.py does).
<namespace>  only names `<namespace>:*` get a scenario.
Per block the scenario places it in a void world, ticks, right-clicks it with an empty hand and ticks 100 more, and
samples `block:`, `tile:` (class of the block entity) and `player:container` (class of the container the click
opened). Asserts: the block is placed; it has a tile entity (unless the name matches --no-tile); it opened a
container (only for names matching --gui). The server log is checked afterwards with smoke-log-check.sh, which also
catches exceptions of the 100 ticks and of the click (the scenario then ends with status error).
--client writes client-side scenarios instead (side: client, open_gui with a structural check) and --only FILE limits them
to the names (without namespace) listed in FILE, e.g. the blocks whose server smoke opened a container.
--recipes adds, per block, an assertion that a crafting recipe makes it (probe recipes:; --no-recipe REGEX
exempts blocks that are not craftable by design).
--entity NAME adds a scenario (use `registry:entities/` in the registry scenario to see the names) that spawns the entity registered under that EntityList name (testkit probe entity_new)
and ticks it 20 times.
Run: porter test run --version forge-1.7.10 --scenario <out-dir> --mod <jar> --mods <host mods> --ids <ids> --env <env>
"""
import json
import re
import sys
from pathlib import Path

args = sys.argv[1:]
opts = {"--no-recipe": None, "--no-tile": None, "--gui": None, "--skip": None, "--only": None}
client = "--client" in args
recipes = "--recipes" in args
args = [a for a in args if a not in ("--client", "--recipes")]
entities = []
pos = []
i = 0
while i < len(args):
    if args[i] in opts:
        opts[args[i]] = args[i + 1]
        i += 2
    elif args[i] == "--entity":
        entities.append(args[i + 1])
        i += 2
    else:
        pos.append(args[i])
        i += 1
names_file, ns, out = Path(pos[0]), pos[1], Path(pos[2])

names = []
text = names_file.read_text(encoding="utf-8")
if names_file.name.endswith(".json"):
    for s in json.loads(text).get("samples", []):
        if s.get("key", "").startswith("registry:blocks") and isinstance(s.get("value"), list):
            names = [n for n in s["value"] if isinstance(n, str)]
else:
    names = [l.strip() for l in text.splitlines() if l.strip()]
names = sorted({n for n in names if n.startswith(ns + ":")})
no_recipe = re.compile(opts["--no-recipe"]) if opts["--no-recipe"] is not None else None
no_tile = re.compile(opts["--no-tile"]) if opts["--no-tile"] else None
gui = re.compile(opts["--gui"]) if opts["--gui"] else None
skip = re.compile(opts["--skip"]) if opts["--skip"] else None
only = set(Path(opts["--only"]).read_text(encoding="utf-8").split()) if opts["--only"] else None

out.mkdir(parents=True, exist_ok=True)
for f in out.glob("[bc]smoke-*.scenario.yaml"):
    f.unlink()
count = 0
for n in names:
    if skip and skip.search(n):
        continue
    if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", n):
        print(f"skipped {n}: not a valid scenario resource id (upper case); map it in the ids file to test it")
        continue
    short = n.split(":", 1)[1]
    if only is not None and short not in only:
        continue
    if client:
        cid = "csmoke-" + re.sub(r"[^a-z0-9_-]", "-", short.lower())
        (out / f"{cid}.scenario.yaml").write_text("\n".join([
            "schemaVersion: 1", f"id: {cid}", "description: >-",
            f"  Generated client smoke of the block {n} (gen-block-smoke.py --client): its screen opens and renders (structural check).",
            "world: flat-void", "side: client", "maxTicks: 300", "steps:",
            f'  - place_block: {{ at: [0, 64, 0], block: "{n}" }}', "  - tick: 5",
            f'  - open_gui: {{ at: [0, 64, 0], capture: {cid[7:]}, keepOpen: true, check: {{ mode: structural }} }}',
            "  - tick: 5", "  - close_gui: {}"]) + "\n", encoding="utf-8")
        count += 1
        continue
    sid = "bsmoke-" + re.sub(r"[^a-z0-9_-]", "-", short.lower())
    steps = [
        f'  - place_block: {{ at: [0, 64, 0], block: "{n}" }}',
        "  - tick: 2",
        '  - sample: { what: ["block:[0,64,0]", "tile:[0,64,0]"] }',
        f'  - assert: {{ expr: "block:[0,64,0] != null && block:[0,64,0] != \'minecraft:air\'", message: "{n} is placed" }}',
    ]
    if not (no_tile and no_tile.search(short)):
        steps.append(f'  - assert: {{ expr: "tile:[0,64,0] != null", message: "{n} created its tile entity" }}')
    if recipes and not (no_recipe and no_recipe.search(short)):
        steps.append(f'  - sample: {{ what: ["recipes:{n}/craftable"] }}')
        steps.append(f'  - assert: {{ expr: "recipes:{n}/craftable >= 1", message: "{n} can be crafted (a crafting recipe makes it, every ingredient resolves)" }}')
    steps += [
        "  - interact: { at: [0, 64, 0], side: up }",
        '  - sample: { what: ["player:container"] }',
    ]
    if gui and gui.search(short):
        steps.append(f'  - assert: {{ expr: "player:container != null", message: "{n} opens a container" }}')
    steps += [
        "  - tick: 100",
        '  - sample: { what: ["block:[0,64,0]", "tile:[0,64,0]"] }',
        f'  - assert: {{ expr: "block:[0,64,0] != null", message: "{n} survives 100 ticks" }}',
    ]
    (out / f"{sid}.scenario.yaml").write_text("\n".join([
        "schemaVersion: 1", f"id: {sid}",
        "description: >-", f"  Generated runtime smoke of the block {n} (gen-block-smoke.py): place, tick, right-click, tick.",
        "world: flat-void", "maxTicks: 400", 'tolerance:', '  "*": { abs: 0 }', "steps:"] + steps) + "\n", encoding="utf-8")
    count += 1
for e in entities:
    sid = "bsmoke-entity-" + re.sub(r"[^a-z0-9_-]", "-", e.lower())
    (out / f"{sid}.scenario.yaml").write_text("\n".join([
        "schemaVersion: 1", f"id: {sid}", "description: >-", f"  Generated smoke of the entity {e}: it is registered, spawns and ticks.",
        "world: flat-void", "maxTicks: 100", 'tolerance:', '  "*": { abs: 0 }', "steps:",
        f'  - sample: {{ what: ["entity_new:[0,64,0]/{e}"] }}',
        f'  - assert: {{ expr: "entity_new != null", message: "{e} is registered and spawns" }}',
        "  - tick: 20"]) + "\n", encoding="utf-8")
    count += 1
print(f"{count} scenarios in {out}")
