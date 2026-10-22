# Port your own mod

For a mod nobody has ported yet. Porter does the mechanical part; you resolve what is left, and every rule you add
helps the next person. Read [How porter works](../ARCHITECTURE.md) first if you want the background. Commands below
are shown for Linux (`run.sh`, `bin/porter`); on Windows use `run.bat` and `bin\porter.bat` with backslashes.

## 0. What you need

- The mod's **1.19.2 sources**: your own code, an open-licence mod, or a mod whose licence or author allows you to
  port it. If you only have a jar, porter can decompile it on your machine (a `setup` step, see step 3), but only port
  mods you have the rights to port.
- The porter release, unpacked as in [Use a ready port](use-a-ready-port.md), and **one ready port run once** (for
  example `ports/torchmaster/run.sh`, 25 to 30 minutes the first time). That run does the one-time setup your port
  reuses: Java 21, the symbol graph, Forge, the 1.7.10 build environment, all under `.porter/`.
- To call `bin/porter` directly (step 1) you need Java 21 on `JAVA_HOME`. If porter downloaded its own, it is
  `.porter/jdk-21/jdk-21.0.12.1+1` (Windows: `set JAVA_HOME=C:\porter-0.2.0\.porter\jdk-21\jdk-21.0.12.1+1`).
  `run.sh` / `run.bat` find it by themselves.

## 1. Measure the coverage

From the porter folder:

```
bin/porter coverage --src <mod>/src/main/java --rules rules --graph .porter/cache/graph/out
```

The result is the share of the mod's distinct Minecraft/Forge API symbols that existing rules already resolve, and the
most frequent unresolved ones. High (above ~90%): a good candidate. Low: you will write many rules, and every one you
share helps the next port.

## 2. Make a profile from the template

Copy the folder `ports/_template` to `ports/mymod` (any lower-case name). In `ports/mymod/mymod.port.yaml` change
everything marked `CHANGE`:

- `name`, `description`, `mode` (`full`: the mod does not exist on 1.7.10; `delta`: an addon over a base that does);
- `closure.prefixes` (the mod's own packages) and `closure.seeds` (the classes the mod starts from: its `@Mod` class
  and every class that something finds by annotation, such as event subscribers), and `closure.cut` for
  registry-like hubs where the copy should stop;
- the generator unit's `--modid`, `--mod-name`, `--package`, and `build.jar` / `build.obfcheckPrefix`.

The comments in the template explain every key; the full schema is `contracts/port-config.schema.json`, and
`contracts/examples/port-config/valid/` and `ports/torchmaster/` are complete examples.

## 3. Tell porter where the sources come from

The `inputs:` entry names the original file and pins its checksum, so every run starts from exactly the same files.

- **Sources on GitHub (open licence):** use the archive of a commit, as `ports/torchmaster/torchmaster.port.yaml`
  does: `page:` the commit page, `url:` `https://github.com/<owner>/<repo>/archive/<commit>.zip`, `file:` a name of
  your choice.
- **Your own sources:** zip your project folder, the one that contains `src/` (zip the folder itself, not just
  `src/`: the template expects `src/main/java` and `src/main/resources` inside it). Put the zip into
  `ports/mymod/inputs/` and set `file:` to its name.
- **Only a jar:** declare it as an input with `kind: jar`, add a `setup` step that runs `porter decompile` on it, and
  point `closure.source` and the `extract` step at the decompiled `java/` folder.
  `contracts/examples/port-config/valid/delta-all-stages.port.yaml` and `contracts/rfc/009-profile-steps-and-patches.md`
  show the steps.

To get the checksum, leave the zeros in `sha256:` and run `ports/mymod/run.sh --check-inputs`. Porter answers that the
file is not the expected one and prints `This file has: <sha256>`; copy that value into `sha256:`. A new zip has a new
checksum, so repeat this whenever you change the sources.

## 4. Run it

```
ports/mymod/run.sh        (Windows: ports\mymod\run.bat)
```

`porter run` checks the inputs, runs the `prepare` steps (extract the declarative content, convert the textures) and
the port. The first run will most likely stop at the compile stage with errors: that is normal.

## 5. Read the report

Porter prints the path of `port-report.md` (for a profile made from the template:
`.porter/runs/mymod/project/build/porter-port/port-report.md`). It has the stage results, the coverage and the
**not-ported list**: every generator skip, dropped class, stubbed hub and unresolved symbol, each with a reason.

## 6. Resolve the distinct symbols

The compile stage writes `symbols.json` next to the report: the errors grouped by **distinct symbol** (one entry per API
member, with a count, most frequent first). Resolve each symbol **once**:

1. a **rule** (`rules/`, with a mandatory before/after test) when the mapping is general,
2. an **adapter** (a small 1.7.10 class in the shim) when the old API has no equivalent call,
3. a **counted edit** (`install.edits`) for a one-off in the mod's own code, with the reason.

Then run the same command again: it resumes from the first stage that has not finished (`--restart` starts over).
Repeat until the compile stage has 0 errors and the build passes. The rule format is in
[CONTRIBUTING.md](../../CONTRIBUTING.md#writing-rules) and `rewrite/README.md`.

## 7. Write scenarios

A scenario (`ports/mymod/scenarios/*.scenario.yaml`, schema `contracts/scenario.schema.json`) is a short list of steps:
"place this block, let 10 ticks pass, check the light level". The same scenario runs on a real server of the original
version and of the port, and the traces are compared, so you learn whether the port behaves like the original. Start
from `testkit/scenarios/` (the furnace one is the template) and keep each scenario to one behaviour. Running them needs
the two test servers (`testkit/README.md`); then use `porter test diff`, or `run.sh --verify` with the original jar.

## 8. Play it

Copy the jar from `output/` into a 1.7.10 Forge instance and check what the report lists as not ported. Then
[share your port](share-your-port.md).
