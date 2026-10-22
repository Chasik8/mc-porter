# IC2 Classic Extras: steam power, barrels and drinks, guide book for Minecraft 1.7.10

An addon for **IC2 Classic 1.2.6** (Minecraft 1.7.10, Forge) that brings features of IC2 Classic 2.1.3.4
(Minecraft 1.19.2) to the old version. You build it yourself, on your own computer, from your own copies of the two
IC2 Classic jars, with one command.

> **Notice.** This is an unofficial project. It is not affiliated with, endorsed by or supported by the authors of
> IC2 Classic (do not report its bugs to them). IC2 Classic is **All Rights Reserved** by its authors. This repository
> contains **no IC2 Classic code, textures, texts or other assets**, and no jar. Everything derived from IC2 Classic
> is produced on your machine from the files you downloaded, stays there, and **the resulting jar must not be
> redistributed** (not uploaded, not shared). It is for your own use next to your own copy of IC2 Classic.

## What you get

- **Steam reactor** GUI with water and steam tanks, heat and output.
- **Fuel Boiler** (multiblock), **Steam Tunnel** (multiblock), **Steam Turbine**, steam valves.
- **Barrels**, beer, rum and whisky, potion brewing, glass cups and mugs, the Shaky effect.
- **Refinery** (whisky and the other alcohol recipes).
- A **guide book** with chapters for these features.
- **NotEnoughItems** recipe pages for all of it.

## What to download

Two files, from the IC2 Classic page on CurseForge (the checksums must match; patched or repacked jars are refused):

| File | Download page | sha256 |
|---|---|---|
| `IC2Classic-1.19.2-2.1.3.4.jar` | https://www.curseforge.com/minecraft/mc-mods/ic2-classic/files/8545995 | `f0a99733ef33491eca418831d681a79902250d341650f652d06f8b88d3627f83` |
| `IC2Classic-1.7.10-1.2.6.jar` | https://www.curseforge.com/minecraft/mc-mods/ic2-classic/files/8014397 | `05637e89c67d28a6248251158848ca42aa6ff830e84dd093ada56d637d57e5c4` |

On each page click **Download** (not **Install**, which wants the CurseForge app); the jar downloads after a few
seconds. Keep the file names as they are (a second copy saved as `... (1).jar` is not found).

Everything else (Java, Minecraft and Forge build files, the decompiler, the NotEnoughItems and CodeChicken libraries
for compiling) is downloaded automatically. You need about 4 GB of free disk space and an internet connection for the
first run. Windows is the tested system.

## Why there is no jar to download

IC2 Classic is All Rights Reserved, and this addon is built from its code. Publishing the finished jar would
redistribute that code and break its authors' copyright. So this folder holds only a recipe, and porter builds the
addon on your computer from the two jars you downloaded yourself. The result is for your own use, next to your own
copy of IC2 Classic: do not upload or share it.

## The one command

If both jars are in your Downloads folder, run the port's launcher with no arguments, from the unpacked porter folder
(see the [README](../../README.md#get-a-ported-mod) for the full steps):

```
ports\ic2c-steam\run.bat        # Windows, in a Command Prompt
ports/ic2c-steam/run.sh         # Linux
```

If they are somewhere else, give both paths, the 1.19.2 jar first:

```
ports\ic2c-steam\run.bat D:\mods\IC2Classic-1.19.2-2.1.3.4.jar D:\mods\IC2Classic-1.7.10-1.2.6.jar
ports/ic2c-steam/run.sh  ~/mods/IC2Classic-1.19.2-2.1.3.4.jar ~/mods/IC2Classic-1.7.10-1.2.6.jar
```

It prints where the finished jar is (`output/ic2cextras-0.1.0.jar`). To play, the `mods` folder of your Minecraft
1.7.10 with Forge 10.13.4.1614 needs:

- `IC2Classic-1.7.10-1.2.6.jar` (the base mod: this is an addon to it),
- the `ic2cextras-0.1.0.jar` you built,
- optional, for the recipe pages: NotEnoughItems for 1.7.10 with CodeChickenCore.

Maintainers: set `JAVA8_HOME` to a Java 8 to run the decompiler on it; the decompiled sources then match the
maintainer's byte for byte. Without it porter's own Java is used.

Time measured on a Windows 10 machine: the first run takes about **15 to 18 minutes** (the one-time setup is most of
it: Java, Minecraft/Forge data, the symbol graph, decompiling both jars); a re-run takes about **4 minutes**.

## What is verified

- The build is green: compile, Gradle build, headless NEI check, reobfuscation check of the jar.
- **8 scenarios** (`scenarios/`) pass on real servers: 6 differential ones run on IC2 Classic 1.19.2 and on this addon
  with IC2 Classic 1.2.6 and compare the traces **tick for tick** (barrel beer, barrel rum, barrel potion, fuel boiler
  heat-up, steam tunnel single and in stages), plus 2 that run on 1.7.10 only (refinery whisky, steam turbine).
- The comparison needs the 1.19.2 server side of IC2 Classic (and its dependency Carbon Config), so maintainers run
  it, not the one command. CI does not build this port (the files have no direct download link and the licence
  forbids mirroring them); the maintainer verifies it locally.

## Known gaps

- Cold drinks (iced and cold teas and coffees) are not ported.
- The `steam_turbine` and `valve_pushing` recipe types are not ported.
- Camouflage visuals (camouflaged blocks) are not ported.
- The reactor fluid port block uses the plain top texture of the steam reactor chamber.
- Not tested in long play sessions; report problems here, not to the IC2 Classic authors.

## How it works

Porter decompiles your two jars on your machine (CFR, pinned version), extracts the declarative content (blocks,
items, recipes, GUIs, multiblocks) into its intermediate representation, copies the logic as a dependency closure of
seed classes and rewrites it with the public rule packs plus the rules in this folder, generates stubs for the classes
the closure stops at, and builds the addon project in `project/`. See `ic2c-steam.port.yaml` for every step.

| Path | What |
|---|---|
| `ic2c-steam.port.yaml` | the port config (all stages, the two inputs with sha256) |
| `run.bat`, `run.sh` | the one command |
| `extract-pack/` | extractor patterns for IC2 Classic's registration code (hand-written, names IC2 Classic classes) |
| `select/` | unit selection specs, hand-checked recipe data, lists of logic files |
| `rules/` | rewrite rules and the id map of objects that already exist in IC2 Classic 1.2.6 |
| `edits/` | counted call-site edits |
| `overlays/adapters/` | hand-written 1.7.10 adapters (interop with IC2 Classic 1.2.6) |
| `project/` | the addon's own hand-written files: mod class, guide book, build files, lang |
| `scenarios/` | the differential and 1.7.10 scenarios |
| `tools/inventory.sh` | maintainer: `porter inventory`, the legal inventory of this folder |

Nothing in the folder is adapted from IC2 Classic's code or assets (inventory category C is 0; the maintainer runs
`porter inventory` locally before every change to this folder, because CI cannot fetch the original).

## Maintainers: reproduce and verify

```
tools/inventory.sh <run dir of a profile run>        # LEGAL-INVENTORY.md; category C must stay 0
porter test diff --scenario scenarios/diff --a <1.19.2 jar> --a-mods <Carbon Config 1.19.2-2.0.2 jar> ^
  --a-ids scenarios/ids-forge-1.19.2.yaml --b <addon jar> --b-mods <1.7.10 jar> ^
  --b-ids scenarios/ids-forge-1.7.10.yaml --ir scenarios/multiblocks.ir.json
porter test run --version forge-1.7.10 --scenario scenarios/run/refinery-whisky.scenario.yaml --mod <addon jar> ^
  --mods <1.7.10 jar> --ids scenarios/ids-forge-1.7.10.yaml --ir scenarios/multiblocks.ir.json
```

Carbon Config 1.19.2-2.0.2 (CurseForge project carbon-config, sha256
`32f07194e3d48682a50779e7b1cd7b19b725c114beab0abc4738876cc63b35b4`) is the 1.19.2 side's dependency.
`scenarios/multiblocks.ir.json` holds the two test structures (fuel boiler, steam tunnel). Known: in one batched
launch the steam tunnel scenario after another tunnel scenario can hit an NPE of the addon when the world is reset;
alone it matches.
