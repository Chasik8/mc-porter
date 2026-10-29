"""Tests of scengen on a synthetic IR (python -I -m unittest discover testkit/scengen)."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import scengen  # noqa: E402

FIX = HERE / "fixtures"


def build(only=None, known=None):
    nodes = scengen.load_nodes(FIX / "synthetic.ir.json")
    table = scengen.load_data(FIX / "machines.yaml")
    resolver = scengen.Resolver(scengen.load_data(FIX / "ids.yaml"), known, scengen.load_data(FIX / "tags.yaml"))
    return scengen.generate(nodes, table, resolver, only)


def asserts(steps):
    return [s["assert"]["expr"] for s in steps if "assert" in s]


class ScengenTest(unittest.TestCase):
    def setUp(self):
        self.cases, self.report, self.d = build()
        self.by_id = {c["id"]: c for c in self.cases}

    def test_menu_slots_split_inputs_and_outputs(self):
        menu = scengen.load_nodes(FIX / "synthetic.ir.json")[0]
        self.assertEqual(scengen.slots_from_menu(menu), ([1], [2]))
        self.assertEqual(scengen.slots_from_menu(None), ([], []))

    def test_process_cases_use_mapped_recipes_only(self):
        press = [c for c in self.cases if c.get("block") == "demo:press"]
        self.assertEqual([c["_recipe"] for c in press], ["demo:press_a", "demo:press_b", "demo:press_c"])
        skipped = {s["recipe"]: s["reason"] for s in self.report["skipped"] if s["machine"] == "demo:press"}
        self.assertIn("no item for tag forge:ores/unknown", skipped["demo:press_d"])
        self.assertIn("2 ingredients, 1 input slots", skipped["demo:press_e"])
        self.assertIn("unknown item demo:missing", skipped["demo:press_f"])

    def test_ingredients_go_to_input_slots_with_batch_and_tag_resolution(self):
        a, b, c = [x for x in self.cases if x.get("block") == "demo:press"]
        self.assertEqual(a["items"], [{"item": "demo:ore_a", "count": 2, "slot": 1}])
        self.assertEqual(b["items"], [{"item": "demo:ore_b", "count": 4, "slot": 1}])
        self.assertEqual(c["items"], [{"item": "demo:ore_c", "count": 2, "slot": 1}])
        self.assertEqual(a["expect"], ["(contains(item:@#2, 'demo:plate_a'))"])

    def test_voltage_from_tier_and_override(self):
        press = next(c for c in self.cases if c.get("block") == "demo:press")
        ebf = next(c for c in self.cases if c.get("block") == "demo:ebf")
        self.assertEqual(press["energy"], {"per": 128, "times": 20})
        self.assertEqual(ebf["energy"], {"per": 32, "times": 20})
        self.assertEqual(ebf["wait"], 400)  # blasting time 100 -> 4 x cookingTime beats the default 200

    def test_recipes_matched_by_ir_machines_field_and_fluid_results(self):
        washer = next(c for c in self.cases if c.get("block") == "demo:washer")
        self.assertEqual(washer["fluids"], [{"fluid": "minecraft:water", "amount": 100}])
        still = next(c for c in self.cases if c.get("block") == "demo:still")
        self.assertEqual(still["expect"], ["contains(fluid:@, 'demo:spirit')"])
        self.assertEqual(still["items"][0]["item"], "demo:ore_b")

    def test_result_is_asserted_in_the_output_slots_not_in_the_whole_dump(self):
        self.assertEqual(scengen.result_expect("m:quartz", False, [3, 4]), "(contains(item:@#3, 'm:quartz') || contains(item:@#4, 'm:quartz'))")
        self.assertEqual(scengen.result_expect("m:quartz", False, []), "contains(inv:@, 'm:quartz')")
        self.assertEqual(scengen.result_expect("m:spirit", True, [3]), "contains(fluid:@, 'm:spirit')")

    def test_machine_without_usable_recipe_is_reported(self):
        self.assertEqual(self.report["machines"]["demo:nomenu"]["cases"], 0)
        self.assertTrue(any(s["machine"] == "demo:nomenu" and s["recipe"] is None for s in self.report["skipped"]))

    def test_energy_cases(self):
        storage = self.by_id["storage-battery_mv"]
        self.assertEqual(storage["energy"], {"per": 128, "times": 40})
        self.assertEqual(storage["expect"], ["call:@/getStoredEU > 0"])
        bench = self.by_id["bench-bench"]
        self.assertEqual(bench["energy"]["per"], 512)
        self.assertEqual(bench["items"], [{"item": "alias:battery_empty", "slot": 1}])
        self.assertEqual(bench["expect"], ["contains(item:@#1, 'charge')"])
        station = self.by_id["station-station"]
        self.assertEqual(station["energy"], {"times": 0})
        self.assertEqual(station["items"][0]["item"], "alias:battery_full")
        gen = self.by_id["generator-gen"]
        self.assertEqual(gen["fluids"], [{"fluid": "demo:spirit", "amount": 1000}])
        self.assertEqual(gen["expect"], ["call:@/getEUProduction > 0"])

    def test_steps_of_a_case(self):
        steps = scengen.case_steps(self.by_id["storage-battery_mv"], self.d, 2)
        origin = [8 + 2 * 24, 64, 8]
        self.assertEqual(steps[0], {"place_block": {"at": origin, "block": "demo:battery_mv"}})
        self.assertEqual(steps[1], {"tick": 2})
        self.assertEqual(steps[2], {"insert_energy": {"at": origin, "amount": 128}})
        self.assertEqual(sum(1 for s in steps if "insert_energy" in s), 40)
        self.assertEqual(asserts(steps), ["call:[56,64,8]/getStoredEU > 0"])
        sample = next(s for s in steps if "sample" in s)
        self.assertEqual(sample["sample"]["what"], ["call:[56,64,8]/getStoredEU"])

    def test_items_go_in_before_the_power_run(self):
        case = {"id": "m", "block": "demo:m", "items": [{"item": "demo:ore", "slot": 1}],
                "energy": {"times": 3, "per": 128}, "wait": 10}
        steps = scengen.case_steps(case, self.d, 0)
        kinds = [next(iter(s)) for s in steps]
        self.assertLess(kinds.index("insert_item"), kinds.index("insert_energy"))
        self.assertEqual(kinds.count("insert_energy"), 3)

    def test_raw_case_offsets_and_placeholders(self):
        steps = scengen.case_steps(self.by_id["pipe"], self.d, 1)
        self.assertEqual(steps[0], {"place_block": {"at": [33, 64, 8], "block": "demomod:pipe"}})
        self.assertEqual(asserts(steps), ["block:[33,64,8] == 'demomod:pipe'"])  # '@[1,0,0]' -> offset of the origin

    def test_each_template_expands(self):
        cases = scengen.expand([{"id": "mug-{v}", "each": ["a", "b"], "post": [{"interact": {"at": [0, 0, 0], "holding": "m:mug_{v}"}}]}])
        self.assertEqual([c["id"] for c in cases], ["mug-a", "mug-b"])
        self.assertEqual(cases[1]["post"][0]["interact"]["holding"], "m:mug_b")

    def test_each_table_gives_fields_and_keeps_types(self):
        cases = scengen.expand([{"id": "d-{v}", "each": {"a": {"fx": ["x", "y"], "n": 3}, "b": {"fx": [], "n": 4}},
                                 "expect": "{v.fx}", "note": "n={v.n} for {v}"}])
        self.assertEqual([c["id"] for c in cases], ["d-a", "d-b"])
        self.assertEqual(cases[0]["expect"], ["x", "y"])
        self.assertEqual(cases[1]["expect"], [])
        self.assertEqual(cases[1]["note"], "n=4 for b")

    def test_expect_contains_makes_one_assert_per_value(self):
        case = {"id": "d", "samples": ["player:effects"], "expect_contains": {"key": "player:effects", "values": ["a", "b"]}, "expect": ["!contains(player:held, 'x')"]}
        steps = scengen.case_steps(case, scengen.DEFAULTS, 0)
        self.assertEqual(asserts(steps), ["contains(player:effects, 'a')", "contains(player:effects, 'b')", "!contains(player:held, 'x')"])

    def test_repeat_steps_and_first_cases_lead_in_their_own_scenario(self):
        steps = scengen.expand_steps([{"repeat": {"times": 3, "steps": [{"tick": 1}, {"repeat": {"times": 2, "steps": [{"tick": 2}]}}]}}])
        self.assertEqual(len(steps), 9)
        table = {"machines": [{"machine": "demo:a", "kind": "generator"}, {"machine": "demo:sun", "kind": "generator", "first": True},
                              {"machine": "demo:b", "kind": "generator"}]}
        cases, _, d = scengen.generate([], table, scengen.Resolver())
        with tempfile.TemporaryDirectory() as tmp:
            written = scengen.write(cases, d, tmp, 5, "g")
            self.assertEqual(written, ["g-00.scenario.yaml", "g-01.scenario.yaml"])
            self.assertIn("generator-sun", (Path(tmp) / written[0]).read_text(encoding="utf-8"))
            self.assertNotIn("generator-a", (Path(tmp) / written[0]).read_text(encoding="utf-8"))

    def test_place_box_and_ring_expand_to_place_block(self):
        box = scengen.expand_steps([{"place_box": {"from": [0, -2, 0], "to": [1, -1, 1], "block": "m:water"}}])
        self.assertEqual(len(box), 8)
        self.assertEqual(box[0], {"place_block": {"at": [0, -2, 0], "block": "m:water"}})
        ring = scengen.expand_steps([{"place_ring": {"y": 0, "radius": 2, "block": "m:log"}}])
        self.assertEqual(len(ring), 16)
        self.assertNotIn({"place_block": {"at": [0, 0, 0], "block": "m:log"}}, ring)
        case = {"id": "pool", "block": "m:fisher", "pre": [{"place_ring": {"y": 0, "radius": 1, "block": "m:plank"}}], "wait": 3}
        steps = scengen.case_steps(case, scengen.DEFAULTS, 0)
        self.assertEqual(sum(1 for s in steps if "place_block" in s), 9)
        self.assertEqual(steps[0]["place_block"]["at"], [7, 64, 7])

    def test_known_registry_filters_unmapped_ids(self):
        nodes = [{"kind": "recipe", "id": "x:r1", "recipeType": "blasting", "ingredients": [{"item": "minecraft:iron_ore"}],
                  "result": {"item": "minecraft:iron_ingot"}},
                 {"kind": "recipe", "id": "x:r2", "recipeType": "blasting", "ingredients": [{"item": "minecraft:raw_iron"}],
                  "result": {"item": "minecraft:iron_ingot"}},
                 {"kind": "menu", "id": "x:m", "slots": [{"index": 1, "x": 56, "y": 17}, {"index": 2, "x": 116, "y": 35}],
                  "bars": [{"id": "progress", "x": 79, "y": 34, "width": 24, "height": 16}]}]
        table = {"machines": [{"machine": "x:m", "handles": [{"type": "blasting"}]}]}
        cases, report, _ = scengen.generate(nodes, table, scengen.Resolver(None, {"minecraft:iron_ore", "minecraft:iron_ingot"}))
        self.assertEqual([c["_recipe"] for c in cases], ["x:r1"])
        self.assertEqual(report["skipped"][0]["reason"], "unknown item minecraft:raw_iron")

    def test_extra_recipes_extra_items_and_no_energy(self):
        nodes = scengen.load_nodes(FIX / "synthetic.ir.json")
        table = {"machines": [{"machine": "demo:own", "menu": "demo:press", "no_energy": True,
                               "extra_recipes": [{"id": "demo:own_r", "ingredients": [{"item": "demo:ore_a"}], "result": {"item": "demo:plate_a"}}],
                               "extra_items": [{"item": "demo:ore_b", "count": 8, "slot": 3}]}]}
        cases, _, _ = scengen.generate(nodes, table, scengen.Resolver(scengen.load_data(FIX / "ids.yaml")))
        self.assertEqual(len(cases), 1)
        self.assertEqual(cases[0]["energy"]["times"], 0)
        self.assertEqual([i["slot"] for i in cases[0]["items"]], [1, 3])
        steps = scengen.case_steps(cases[0], scengen.DEFAULTS | {"tiers": scengen.TIERS}, 0)
        self.assertFalse(any("insert_energy" in s for s in steps))

    def test_fallback_prefixes_rename_to_the_registered_name(self):
        r = scengen.Resolver(None, {"hostmod:tea", "spirit", "minecraft:log"},
                             None, [{"from": "examplemod:", "to": "hostmod:"}, {"from": "examplemod:", "to": ""}])
        self.assertEqual(r.item({"item": "examplemod:tea"}), ("hostmod:tea", None))
        self.assertEqual(r.item({"item": "minecraft:log"}), ("minecraft:log", None))
        self.assertEqual(r.fluid("examplemod:spirit"), ("spirit", None))
        self.assertEqual(r.fluid("minecraft:water"), ("minecraft:water", None))
        self.assertEqual(r.item({"item": "examplemod:nothing"}), (None, "unknown item examplemod:nothing"))

    def test_spread_is_even_and_deterministic(self):
        self.assertEqual(scengen.spread(list(range(10)), 3), [0, 4, 9])
        self.assertEqual(scengen.spread([1, 2], 5), [1, 2])
        self.assertEqual(scengen.spread([1, 2, 3], 1), [1])

    def test_unique_case_ids(self):
        nodes = [{"kind": "menu", "id": "x:m", "slots": [{"index": 1, "x": 56, "y": 17}, {"index": 2, "x": 116, "y": 35}],
                  "bars": [{"id": "progress", "x": 79, "y": 34, "width": 24, "height": 16}]},
                 {"kind": "recipe", "id": "a:r", "recipeType": "smelting", "ingredients": [{"item": "q:a"}], "result": {"item": "q:b"}},
                 {"kind": "recipe", "id": "b:r", "recipeType": "smelting", "ingredients": [{"item": "q:c"}], "result": {"item": "q:d"}}]
        cases, _, _ = scengen.generate(nodes, {"machines": [{"machine": "x:m", "handles": [{"type": "smelting"}]}]}, scengen.Resolver())
        self.assertEqual([c["id"] for c in cases], ["m-r", "m-r-2"])

    def test_written_scenarios_are_grouped_and_valid_json_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            written = scengen.write(self.cases, self.d, tmp, 4, "gen")
            self.assertGreater(len(written), 1)
            first = (Path(tmp) / written[0]).read_text(encoding="utf-8").splitlines()
            self.assertTrue(first[0].startswith("# generated by"))
            self.assertIn('id: "gen-00"', first)
            step_lines = [ln for ln in first if ln.startswith("  - ")]
            for ln in step_lines:
                step = json.loads(ln[4:])
                self.assertEqual(len(step), 1)

    def test_schema_lint_rejects_ids_the_scenario_contract_does_not_allow(self):
        schema = scengen.load_schema()
        self.assertIsNotNone(schema)
        good = [{"place_block": {"at": [1, 2, 3], "block": "demo:press"}}, {"tick": 3}]
        self.assertEqual(scengen.lint_steps(good, schema), [])
        bad = [{"place_block": {"at": [1, 2], "block": "Host:blockMachine"}}, {"nonsense": 1}]
        problems = scengen.lint_steps(bad, schema)
        self.assertEqual(len(problems), 3)
        case = {"id": "bad", "block": "Host:blockMachine", "wait": 1}
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                scengen.write([case], scengen.DEFAULTS, tmp, 1, "x")

    def test_cli_writes_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            rc = scengen.main(["--ir", str(FIX / "synthetic.ir.json"), "--machines", str(FIX / "machines.yaml"),
                               "--ids", str(FIX / "ids.yaml"), "--tags", str(FIX / "tags.yaml"), "--out", tmp,
                               "--group", "6", "--prefix", "demo", "--only", "demo:(press|ebf)"])
            self.assertEqual(rc, 0)
            report = json.loads((Path(tmp) / "scengen-report.json").read_text(encoding="utf-8"))
            self.assertEqual(sorted(report["machines"]), ["demo:ebf", "demo:press"])
            self.assertEqual(report["scenarios"], ["demo-00.scenario.yaml"])


if __name__ == "__main__":
    unittest.main()
