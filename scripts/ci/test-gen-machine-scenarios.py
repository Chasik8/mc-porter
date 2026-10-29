#!/usr/bin/env python3
"""Self-test of gen-machine-scenarios.py: python scripts/ci/test-gen-machine-scenarios.py"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

here = Path(__file__).parent
spec = {"defaults": {"wait": 10}, "cases": [
    {"id": "a", "kind": "process", "block": "m:one", "water": 100,
     "items": [{"item": "minecraft:cobblestone", "count": 4, "slot": 0}], "expect_inv": ["sand"]},
    {"id": "t-{v}", "kind": "raw", "each": ["x", "y"], "post": [{"interact": {"at": [0, 0, 0], "holding": "m:{v}"}}]},
    {"id": "b", "kind": "raw", "pre": [{"place_block": {"at": [1, 0, 0], "block": "m:two"}}],
     "samples": ["call:@[1,0,0]/x"], "expect": ["call:@/y > 0"]}]}
with tempfile.TemporaryDirectory() as t:
    sp = Path(t) / "spec.json"
    sp.write_text(json.dumps(spec))
    out = Path(t) / "out"
    subprocess.check_call([sys.executable, str(here / "gen-machine-scenarios.py"), str(sp), str(out), "--prefix", "tst"])
    text = (out / "tst.scenario.yaml").read_text()
    steps = [json.loads(l[4:]) for l in text.splitlines() if l.startswith("  - ")]
    kinds = [list(s)[0] for s in steps]
    assert "fill_fluid" in kinds and kinds.count("insert_energy") == 8 and "assert" in kinds, kinds
    placed = [s["place_block"] for s in steps if "place_block" in s]
    assert placed[0]["at"] == [8, 64, 8] and placed[1]["at"] == [8 + 24 * 3 + 1, 64, 8], placed  # case b is the 4th (index 3): origin 80 + offset 1
    assert any("call:[80,64,8]/y > 0" in s["assert"]["expr"] for s in steps if "assert" in s)
    assert any("call:[81,64,8]/x" in w for s in steps if "sample" in s for w in s["sample"]["what"])
assert any(s.get("interact", {}).get("holding") == "m:y" for s in steps)
print("ok")
