#!/usr/bin/env python3
"""Tests of cropgen: python scripts/cropgen/test_cropgen.py"""
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import cropgen  # noqa: E402

SAMPLE = HERE / "examples" / "sample-crops.yaml"


def assets(root, data):
    for c in data["crops"]:
        for t in c.get("textures", []):
            p = Path(root) / t["src"]
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b"\x89PNG-" + c["id"].encode() + str(t["size"]).encode())


class CropgenTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="cropgen-"))
        self.data = yaml.safe_load(SAMPLE.read_text(encoding="utf-8"))
        self.assets = self.tmp / "assets"
        assets(self.assets, self.data)

    def run_gen(self, data=None, scen=True):
        data = data or self.data
        d = self.tmp / "data.yaml"
        d.write_text(yaml.safe_dump(data, allow_unicode=True), encoding="utf-8")
        args = ["generate", "--data", str(d), "--assets", str(self.assets), "--out", str(self.tmp / "out")]
        if scen:
            args += ["--scenarios-out", str(self.tmp / "scen")]
        return cropgen.main(args)

    def test_generates_everything(self):
        self.assertEqual(self.run_gen(), 0)
        out = self.tmp / "out"
        pkg = out / "overlay" / "example" / "crops"
        for n in ("CropSpec", "Gate", "MoreCropBase", "FlowerCrop", "SaplingCrop", "OreCrop", "FungusCrop",
                  "ConditionCrop", "MoreCrops"):
            self.assertTrue((pkg / f"{n}.java").is_file(), n)
            text = (pkg / f"{n}.java").read_text(encoding="utf-8")
            self.assertIn("package example.crops;", text)
            self.assertEqual(text.count("{"), text.count("}"), n)
            self.assertNotIn("@PKG@", text)
            self.assertNotIn("@MODID@", text)
        reg = (pkg / "MoreCrops.java").read_text(encoding="utf-8")
        self.assertIn('new FlowerCrop(', reg)
        self.assertIn('new CropSpec("sample_flower")', reg)
        self.assertIn('.props(2, 1, 1, 0, 5, 1)', reg)
        self.assertIn(', 3, "oreIron", "blockIron")', reg)
        self.assertIn('Gate.neighbor(2, "minecraft:glowstone")', reg)
        self.assertIn('Gate.humidityMin(4)', reg)
        self.assertIn('.rule(3, 3, 2000)', reg)
        self.assertIn('.dropChanceMul(0.5f)', reg)
        self.assertIn('3000', reg)
        self.assertNotIn("sample_water_thing", reg)
        self.assertIn('"examplecrops:crops/sample_flower_4"', reg)
        tex = out / "resources" / "assets" / "examplecrops" / "textures" / "blocks" / "crops"
        self.assertEqual(len(list(tex.glob("*.png"))), 11)
        self.assertEqual((tex / "sample_ore_3.png").read_bytes(), b"\x89PNG-sample_ore3")
        lang = (out / "lang" / "en_US.crops.lang").read_text(encoding="utf-8")
        self.assertIn("crop.examplecrops.sample_flower.name=Sample Flower", lang)
        self.assertIn("Пример цветка", (out / "lang" / "ru_RU.crops.lang").read_text(encoding="utf-8"))
        rep = json.loads((out / "report.json").read_text(encoding="utf-8"))
        self.assertEqual(rep["summary"], {"ok": 3, "skip": 1, "needs-porter": 0})

    def test_scenarios(self):
        self.assertEqual(self.run_gen(), 0)
        files = sorted(p.name for p in (self.tmp / "scen").iterdir())
        self.assertEqual(files, ["crops-condition-harvest.scenario.yaml", "crops-flower-harvest.scenario.yaml", "crops-growth.scenario.yaml",
                                 "crops-ore-harvest.scenario.yaml", "ids-forge-1.7.10.yaml"])
        sc = yaml.safe_load((self.tmp / "scen" / "crops-flower-harvest.scenario.yaml").read_text(encoding="utf-8"))
        self.assertEqual(sc["schemaVersion"], 1)
        raw = (self.tmp / "scen" / "crops-flower-harvest.scenario.yaml").read_text(encoding="utf-8")
        self.assertNotIn("&id", raw)
        self.assertNotIn("*id", raw)
        kinds = [list(s)[0] for s in sc["steps"]]
        self.assertIn("invoke_tile", kinds)
        self.assertEqual(kinds.count("assert"), 2)
        ids = yaml.safe_load((self.tmp / "scen" / "ids-forge-1.7.10.yaml").read_text(encoding="utf-8"))
        self.assertEqual(ids["blocks"], {"ic2:crop": "IC2:crop"})
        # the ore crop (drop chance 0.5) is harvested repeatedly
        so = yaml.safe_load((self.tmp / "scen" / "crops-ore-harvest.scenario.yaml").read_text(encoding="utf-8"))
        harvests = [s for s in so["steps"] if "invoke_tile" in s and s["invoke_tile"]["method"] == "harvest"]
        self.assertEqual(len(harvests), 40)

    def test_deterministic(self):
        self.run_gen()
        a = (self.tmp / "out" / "overlay" / "example" / "crops" / "MoreCrops.java").read_text(encoding="utf-8")
        self.run_gen()
        b = (self.tmp / "out" / "overlay" / "example" / "crops" / "MoreCrops.java").read_text(encoding="utf-8")
        self.assertEqual(a, b)

    def test_rejects_bad_data(self):
        d = copy.deepcopy(self.data)
        d["crops"][0]["family"] = "tree"
        self.assertEqual(self.run_gen(d), 2)
        d = copy.deepcopy(self.data)
        d["crops"][1]["id"] = d["crops"][0]["id"]
        self.assertEqual(self.run_gen(d), 2)
        d = copy.deepcopy(self.data)
        d["crops"][0]["textures"] = d["crops"][0]["textures"][:2]
        self.assertEqual(self.run_gen(d), 2)
        d = copy.deepcopy(self.data)
        d["crops"][0]["drops"] = []
        self.assertEqual(self.run_gen(d), 2)
        d = copy.deepcopy(self.data)
        d["crops"][0]["stats"] = [1, 2]
        self.assertEqual(self.run_gen(d), 2)
        d = copy.deepcopy(self.data)
        d["format"] = "other"
        self.assertEqual(self.run_gen(d), 2)

    def test_missing_texture_file(self):
        (self.assets / "ore" / "2.png").unlink()
        self.assertEqual(self.run_gen(), 2)

    def test_numeric_ids_stable_and_unique(self):
        d = copy.deepcopy(self.data)
        d["crops"][1]["numericId"] = 3000
        self.assertEqual(self.run_gen(d), 2)
        loaded = cropgen.load(SAMPLE)
        self.assertEqual([c.get("numericId") for c in loaded["crops"] if c["status"] == "ok"], [3000, 3001, 3002])

    def test_gate_java(self):
        self.assertEqual(cropgen.gate_java({"moonMin": 0.15, "fromSize": 3}), "Gate.moonMin(3, 0.15f)")
        self.assertEqual(cropgen.gate_java({"lightMin": 12}), "Gate.lightMin(12)")
        with self.assertRaises(cropgen.DataError):
            cropgen.gate_java({"rain": 1})


if __name__ == "__main__":
    unittest.main()
