#!/usr/bin/env python3
"""porter cropgen: a data file of crops -> Java CropCard classes for IC2 Classic (Forge 1.7.10), icons, lang, scenarios.

    cropgen.py generate --data crops.yaml --assets <texture root> --out <dir> [--scenarios-out <dir>]

The data file (format `porter-cropgen/1`, see examples/sample-crops.yaml) holds one entry per crop: properties,
growth conditions, drops, base seeds and the source texture of every growth stage. Nothing in this tool knows a
particular mod; the data comes from a profile (for IC2 Classic 1.19.2 an extractor reads the original sources).

Output (all files are overwritten, the tool is deterministic):
    <out>/overlay/<package path>/{CropSpec,Gate,MoreCropBase,FlowerCrop,SaplingCrop,OreCrop,FungusCrop,
                                    ConditionCrop,MoreCrops}.java
    <out>/resources/assets/<modid>/textures/blocks/<textureDir>/<id>_<size>.png
    <out>/lang/<locale>.crops.lang
    <out>/report.json
    <scenarios-out>/<family>.scenario.yaml + ids-forge-1.7.10.yaml   (one testkit scenario per family)
"""
import argparse
import json
import shutil
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
FORMAT = "porter-cropgen/1"
FAMILIES = ("flower", "sapling", "ore", "fungus", "condition")
STATUSES = ("ok", "skip", "needs-porter")


class DataError(Exception):
    pass


class NoAliasDumper(yaml.SafeDumper):
    """scenario files must not contain YAML anchors/aliases (the testkit schema check rejects the shared lists)"""

    def ignore_aliases(self, data):
        return True


def jstr(s):
    return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"') + '"'


def jfloat(v):
    return repr(float(v)) + "f"


def load(path):
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("format") != FORMAT:
        raise DataError(f"{path}: format must be {FORMAT}")
    for k in ("package", "modid", "iconPrefix", "crops"):
        if k not in data:
            raise DataError(f"{path}: missing '{k}'")
    data.setdefault("textureDir", "crops")
    data.setdefault("idBase", 1000)
    ids = set()
    nums = set()
    n = 0
    for c in data["crops"]:
        cid = c.get("id")
        if not cid or cid in ids:
            raise DataError(f"crop id missing or duplicated: {cid}")
        ids.add(cid)
        c.setdefault("status", "ok")
        if c["status"] not in STATUSES:
            raise DataError(f"{cid}: status {c['status']}")
        if c["status"] != "ok":
            continue
        if c.get("family") not in FAMILIES:
            raise DataError(f"{cid}: family {c.get('family')}")
        for k in ("name", "tier", "stats", "maxSize", "textures"):
            if k not in c:
                raise DataError(f"{cid}: missing '{k}'")
        if len(c["stats"]) != 5:
            raise DataError(f"{cid}: stats needs 5 values")
        sizes = sorted(t["size"] for t in c["textures"])
        if sizes != list(range(1, c["maxSize"] + 1)):
            raise DataError(f"{cid}: textures must cover sizes 1..{c['maxSize']}, got {sizes}")
        if "numericId" not in c:
            c["numericId"] = data["idBase"] + n
        n += 1
        if c["numericId"] in nums:
            raise DataError(f"{cid}: numericId {c['numericId']} used twice")
        nums.add(c["numericId"])
        if not (c.get("drops") or c.get("dropsRandom")):
            raise DataError(f"{cid}: no drops")
    return data


def ok_crops(data):
    return [c for c in data["crops"] if c["status"] == "ok"]


def icon_name(data, c, size):
    return f"{data['iconPrefix']}{c['id']}_{size}"


def gate_java(g):
    (k, v), = [(k, v) for k, v in g.items() if k != "fromSize"]
    frm = g.get("fromSize", 0)
    if k == "lightMin":
        return f"Gate.lightMin({int(v)})"
    if k == "humidityMin":
        return f"Gate.humidityMin({int(v)})"
    if k == "humidityMax":
        return f"Gate.humidityMax({int(v)})"
    if k == "oreBelow":
        return f"Gate.oreBelow({frm}, " + ", ".join(jstr(x) for x in v) + ")"
    if k == "neighbor":
        return f"Gate.neighbor({frm}, {jstr(v)})"
    if k == "moonMin":
        return f"Gate.moonMin({frm}, {jfloat(v)})"
    raise DataError(f"unknown gate {k}")


def spec_java(data, c):
    p = [f"new CropSpec({jstr(c['id'])})"]
    p.append(f".name({jstr(c['name'])}, {jstr(c.get('discoveredBy', 'unknown'))})")
    s = c["stats"]
    p.append(f".props({c['tier']}, {s[0]}, {s[1]}, {s[2]}, {s[3]}, {s[4]})")
    if c.get("attributes"):
        p.append(".attrs(" + ", ".join(jstr(a) for a in c["attributes"]) + ")")
    p.append(f".sizes({c['maxSize']}, {c.get('afterHarvest', 1)})")
    if c.get("harvestMin"):
        p.append(f".harvestFrom({c['harvestMin']})")
    dc = c.get("dropChance") or {}
    if "abs" in dc:
        p.append(f".dropChanceAbs({jfloat(dc['abs'])})")
    elif "mul" in dc:
        p.append(f".dropChanceMul({jfloat(dc['mul'])})")
    if c.get("duration") or c.get("durationMul"):
        p.append(f".duration({int(c.get('duration', 0))}, {jfloat(c.get('durationMul', 1))})")
    for r in c.get("rules", []):
        p.append(f".rule({r.get('min', 1)}, {r.get('max', 127)}, {r['ticks']})")
    if c.get("weights"):
        w = c["weights"]
        p.append(f".weights({jfloat(w[0])}, {jfloat(w[1])}, {jfloat(w[2])})")
    if c.get("light"):
        p.append(f".light({c['light']['size']}, {c['light']['level']})")
    if c.get("collision"):
        p.append(f".collision({jfloat(c['collision'])})")
    p.append(".icons(" + ", ".join(jstr(icon_name(data, c, i)) for i in range(1, c["maxSize"] + 1)) + ")")
    if c.get("drops"):
        p.append(".drops(" + ", ".join(jstr(d) for d in c["drops"]) + ")")
    if c.get("dropsRandom"):
        p.append(".dropsRandom(" + ", ".join(jstr(d) for d in c["dropsRandom"]) + ")")
    return "\n            ".join(p)


def crop_java(data, c):
    spec = spec_java(data, c)
    fam = c["family"]
    a = c.get("familyArgs") or {}
    if fam == "flower":
        return f"new FlowerCrop(\n            {spec})"
    if fam == "sapling":
        return f"new SaplingCrop(\n            {spec})"
    if fam == "ore":
        names = ", ".join(jstr(x) for x in a["oreNames"])
        return f"new OreCrop(\n            {spec}, {a.get('needFromSize', c['maxSize'] - 1)}, {names})"
    if fam == "fungus":
        return f"new FungusCrop(\n            {spec}, {'true' if a.get('needsHumidity') else 'false'})"
    gates = ", ".join(gate_java(g) for g in c.get("gates", []))
    return f"new ConditionCrop(\n            {spec}" + (", " + gates if gates else "") + ")"


def registry_java(data):
    pkg = data["package"]
    out = [f"// generated by porter cropgen - do not edit", f"package {pkg};", "",
           "import cpw.mods.fml.common.FMLLog;", "import ic2.api.crops.CropCard;", "import ic2.api.crops.Crops;",
           "import ic2.core.block.crop.IC2Crops;", "import net.minecraft.item.ItemStack;", "",
           "/** Registers the generated crops with the host IC2 Classic crop registry. Call register() once, after IC2 "
           "has initialised (FML postInit). */",
           "public final class MoreCrops {", "    private MoreCrops() {}", "",
           "    private static boolean done;", "",
           "    public static synchronized void register() {",
           "        if (done || Crops.instance == null) {", "            return;", "        }", "        done = true;"]
    for c in ok_crops(data):
        seeds = []
        for s in c.get("baseSeeds", []):
            seeds.append(f"new Object[] {{{jstr(s['item'])}, {s.get('count', 1)}, {s.get('size', 1)}, "
                         f"{s.get('growth', 1)}, {s.get('gain', 1)}, {s.get('resistance', 1)}}}")
        disp = jstr(c["displayItem"]) if c.get("displayItem") else "null"
        seeds_s = ", ".join(seeds)
        out.append(f"        add({crop_java(data, c)},\n            {c['numericId']}, {disp}"
                   + (", " + seeds_s if seeds_s else "") + ");")
    out += ["    }", "",
            "    private static void add(MoreCropBase card, int id, String display, Object[]... seeds) {",
            "        if (!card.usable()) {",
            "            FMLLog.warning(\"cropgen: crop %s skipped, an item of it is not registered\", card.name());",
            "            return;", "        }",
            "        if (!Crops.instance.registerCrop(card, id)) {",
            "            FMLLog.warning(\"cropgen: crop %s: id %d is taken, using a free one\", card.name(), id);",
            "            if (Crops.instance.registerCrop(card) < 0) {", "                return;", "            }", "        }",
            "        if (display != null) {",
            "            ItemStack d = MoreCropBase.stack(display);",
            "            if (d != null) {", "                IC2Crops.registerCropDisplayItem(card, d);", "            }", "        }",
            "        for (Object[] s : seeds) {",
            "            ItemStack st = MoreCropBase.stack((String) s[0]);",
            "            if (st != null) {",
            "                st.stackSize = (Integer) s[1];",
            "                Crops.instance.registerBaseSeed(st, (CropCard) card, (Integer) s[2], (Integer) s[3], "
            "(Integer) s[4], (Integer) s[5]);",
            "            }", "        }", "    }", "}", ""]
    return "\n".join(out)


def split_families(text):
    parts = {}
    cur = None
    for line in text.splitlines():
        if line.startswith("#== "):
            cur = line[4:].strip()
            parts[cur] = []
        elif cur:
            parts[cur].append(line)
    return {k: "\n".join(v) + "\n" for k, v in parts.items()}


def gen_java(data, out):
    pkg = data["package"]
    base = out / "overlay" / Path(*pkg.split("."))
    base.mkdir(parents=True, exist_ok=True)
    files = {}
    tdir = HERE / "templates"
    for name in ("CropSpec", "Gate", "MoreCropBase"):
        files[name] = (tdir / f"{name}.java.tpl").read_text(encoding="utf-8")
    files.update(split_families((tdir / "families.tpl").read_text(encoding="utf-8")))
    for name, text in files.items():
        text = text.replace("@PKG@", pkg).replace("@MODID@", data["modid"])
        (base / f"{name}.java").write_text(text, encoding="utf-8", newline="\n")
    (base / "MoreCrops.java").write_text(registry_java(data), encoding="utf-8", newline="\n")
    return sorted(files) + ["MoreCrops"]


def gen_textures(data, assets, out):
    dest = out / "resources" / "assets" / data["modid"] / "textures" / "blocks" / data["textureDir"]
    dest.mkdir(parents=True, exist_ok=True)
    n = 0
    for c in ok_crops(data):
        for t in c["textures"]:
            src = Path(assets) / t["src"]
            if not src.is_file():
                raise DataError(f"{c['id']}: texture {src} not found")
            shutil.copyfile(src, dest / f"{c['id']}_{t['size']}.png")
            n += 1
    return n


def gen_lang(data, out):
    d = out / "lang"
    d.mkdir(parents=True, exist_ok=True)
    locs = {"en_US": {}}
    for c in ok_crops(data):
        key = f"crop.{data['modid']}.{c['id']}.name"
        locs["en_US"][key] = c["name"]
        for loc, val in (c.get("lang") or {}).items():
            locs.setdefault(loc, {})[key] = val
    for loc, kv in locs.items():
        (d / f"{loc}.crops.lang").write_text("".join(f"{k}={v}\n" for k, v in sorted(kv.items())),
                                             encoding="utf-8", newline="\n")
    return sorted(locs)


def expect_name(ref):
    """registry name a drop ref should produce in a trace (the item part of 'mod:name@meta')"""
    return None if ref.startswith("field:") else ref.split("@")[0]


def gen_scenarios(data, out):
    """One scenario per family: every crop of the family at its own spot, grown to full size, harvested (gain 31,
    repeated for crops with a low drop chance), the drops asserted."""
    t = data.get("test") or {}
    block = t.get("cropBlock", "ic2:crop")
    soil = t.get("soil", "minecraft:farmland")
    out.mkdir(parents=True, exist_ok=True)
    written = []
    by_fam = {}
    for c in ok_crops(data):
        by_fam.setdefault(c["family"], []).append(c)
    for fam, crops in sorted(by_fam.items()):
        steps = []
        for i, c in enumerate(crops):
            x = 8 + 8 * (i % 4)
            z = 8 + 8 * (i // 4)
            at = [x, 64, z]
            steps.append({"place_block": {"at": [x, 63, z], "block": soil}})
            steps.append({"place_block": {"at": at, "block": block}})
        steps.append({"tick": 2})
        asserts = []
        for i, c in enumerate(crops):
            x = 8 + 8 * (i % 4)
            z = 8 + 8 * (i // 4)
            at = [x, 64, z]
            dc = c.get("dropChance") or {}
            reps = 1 if "abs" not in dc and dc.get("mul", 1) >= 1 and c["tier"] <= 6 else 40
            steps.append({"invoke_tile": {"at": at, "method": "setID", "args": [c["numericId"]]}})
            steps.append({"invoke_tile": {"at": at, "method": "setGain", "args": [31]}})
            steps.append({"invoke_tile": {"at": at, "method": "setGrowth", "args": [31]}})
            for _ in range(reps):
                steps.append({"invoke_tile": {"at": at, "method": "setSize", "args": [c["maxSize"]]}})
                steps.append({"invoke_tile": {"at": at, "method": "harvest", "args": [True]}})
            key = f"drops:[{x},64,{z}]"
            steps.append({"tick": 1})
            steps.append({"sample": {"what": [key]}})
            exp = c.get("expect") or [e for e in (expect_name(d) for d in (c.get("dropsRandom") or c.get("drops"))) if e]
            asserts.append({"assert": {"expr": f"{key} != null && len({key}) >= 1",
                                       "message": f"{c['id']}: the harvest dropped something"}})
            if not exp:
                pass
            elif len(exp) == 1:
                asserts.append({"assert": {"expr": f"contains({key}, '{exp[0]}')",
                                           "message": f"{c['id']}: the drop is {exp[0]}"}})
            else:
                ors = " || ".join(f"contains({key}, '{e}')" for e in exp)
                asserts.append({"assert": {"expr": ors, "message": f"{c['id']}: the drop is one of {len(exp)} items"}})
        steps += asserts
        scen = {"schemaVersion": 1, "id": f"crops-{fam}-harvest",
                "description": f"Generated by cropgen: every {fam} crop is placed on crop sticks over farmland, set to its "
                               f"full size with gain 31, harvested, and the drops are asserted.",
                "world": "flat-void", "maxTicks": 400, "tolerance": {"*": {"abs": 0}}, "steps": steps}
        p = out / f"crops-{fam}-harvest.scenario.yaml"
        p.write_text(yaml.dump(scen, Dumper=NoAliasDumper, sort_keys=False, allow_unicode=True, width=200), encoding="utf-8", newline="\n")
        written.append(p.name)
    growth = growth_scenario(data, t, block, soil)
    if growth:
        p = out / "crops-growth.scenario.yaml"
        p.write_text(yaml.dump(growth, Dumper=NoAliasDumper, sort_keys=False, allow_unicode=True, width=200),
                     encoding="utf-8", newline="\n")
        written.append(p.name)
    ids = {"version": "forge-1.7.10", "blocks": {block: t.get("cropBlockName", "IC2:crop")}}
    (out / "ids-forge-1.7.10.yaml").write_text(yaml.safe_dump(ids, sort_keys=False), encoding="utf-8", newline="\n")
    return written


GROWABLE_GATES = ("lightMin", "humidityMax", "moonMin", "neighbor")


def growth_scenario(data, t, block, soil):
    """Plants the cheap crops (tier <= 4, conditions that a flat void world can meet) with growth stat 8 and lets
    real world ticks grow them: each must have a bigger size afterwards. A `neighbor` condition gets its block placed."""
    ticks = int(t.get("growthTicks", 40000))
    crops = []
    for c in ok_crops(data):
        gates = c.get("gates", [])
        # dry-only crops (fungus needing humidity <= 0) cannot grow on the hydrated farmland the scenario needs
        fam_gates_ok = c["family"] in ("flower", "sapling", "condition")
        if c["tier"] <= 4 and fam_gates_ok and all(any(k in g for k in GROWABLE_GATES) for g in gates):
            crops.append(c)
    if not crops:
        return None
    steps, asserts = [], []
    pos = {}
    for i, c in enumerate(crops):
        x, z = 8 + 8 * (i % 4), 8 + 8 * (i // 4)
        pos[c["id"]] = [x, 64, z]
        steps.append({"place_block": {"at": [x, 63, z], "block": soil}})
        # farmland without water nearby turns to dirt within a few thousand ticks and the crop breaks off:
        # a water source in a stone cup next to the soil keeps it wet and stays put
        for dx, dy, dz in ((-1, -1, 0), (-2, 0, 0), (-1, 0, -1), (-1, 0, 1)):
            steps.append({"place_block": {"at": [x + dx, 63 + dy, z + dz], "block": t.get("stone", "minecraft:stone")}})
        steps.append({"place_block": {"at": [x - 1, 63, z], "block": t.get("water", "minecraft:water")}})
        for g in c.get("gates", []):
            if "neighbor" in g:
                steps.append({"place_block": {"at": [x + 1, 64, z], "block": g["neighbor"]}})
        steps.append({"place_block": {"at": [x, 64, z], "block": block}})
    steps.append({"tick": 2})
    for c in crops:
        at = pos[c["id"]]
        steps.append({"invoke_tile": {"at": at, "method": "setID", "args": [c["numericId"]]}})
        steps.append({"invoke_tile": {"at": at, "method": "setSize", "args": [1]}})
        steps.append({"invoke_tile": {"at": at, "method": "setGrowth", "args": [int(t.get("growthStat", 8))]}})
    steps.append({"tick": ticks})
    for c in crops:
        at = pos[c["id"]]
        k = f"[{at[0]},{at[1]},{at[2]}]"
        steps.append({"sample": {"what": [f"call:{k}/getSize", f"call:{k}/getID"]}})
        asserts.append({"assert": {"expr": f"call:{k}/getSize >= 2",
                                   "message": f"{c['id']}: grew to size 2 or more in {ticks} ticks"}})
        asserts.append({"assert": {"expr": f"call:{k}/getID == {c['numericId']}",
                                   "message": f"{c['id']}: still the same crop"}})
    return {"schemaVersion": 1, "id": "crops-growth",
            "description": f"Generated by cropgen: {len(crops)} cheap crops are planted at size 1 and grow by real world ticks.",
            "world": "flat-void", "maxTicks": ticks + 1000, "tolerance": {"*": {"abs": 0}}, "steps": steps + asserts}


def report(data, out, java, tex, langs, scen):
    rows = [{"id": c["id"], "status": c["status"], "family": c.get("family"), "reason": c.get("reason"),
             "approx": c.get("approx")} for c in data["crops"]]
    summary = {s: sum(1 for c in data["crops"] if c["status"] == s) for s in STATUSES}
    rep = {"summary": summary, "classes": java, "textures": tex, "langs": langs, "scenarios": scen, "crops": rows}
    (out / "report.json").write_text(json.dumps(rep, indent=1, ensure_ascii=False), encoding="utf-8", newline="\n")
    return rep


def main(argv=None):
    ap = argparse.ArgumentParser(prog="cropgen")
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("generate")
    g.add_argument("--data", required=True)
    g.add_argument("--assets", required=True, help="root the texture 'src' paths are relative to")
    g.add_argument("--out", required=True)
    g.add_argument("--scenarios-out")
    a = ap.parse_args(argv)
    try:
        data = load(a.data)
        out = Path(a.out)
        out.mkdir(parents=True, exist_ok=True)
        java = gen_java(data, out)
        tex = gen_textures(data, a.assets, out)
        langs = gen_lang(data, out)
        scen = gen_scenarios(data, Path(a.scenarios_out)) if a.scenarios_out else []
        rep = report(data, out, java, tex, langs, scen)
    except DataError as e:
        print(f"cropgen: {e}", file=sys.stderr)
        return 2
    s = rep["summary"]
    print(f"cropgen: {s['ok']} crops generated, {s['skip']} skipped, {s['needs-porter']} need porter; "
          f"{tex} textures, {len(scen)} scenarios")
    return 0


if __name__ == "__main__":
    sys.exit(main())
