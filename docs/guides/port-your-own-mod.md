# Port your own mod

For a mod nobody has ported yet. Expect real work: porter does the mechanical part, you resolve what is left. Start with a
cheap check of how big the job is.

You need the mod's **sources** (your own code, an open-licence mod, or a decompile of a jar you are allowed to decompile)
for the version it is written for, and the porter release (see [Use a ready port](use-a-ready-port.md)).

## 1. Measure the coverage

```
bin/porter coverage --src <mod>/src/main/java --rules rules --graph .porter/cache/graph/out
```

(run any ready port once first: it builds the symbol graph in `.porter/cache`). The result is the share of the mod's
Minecraft/Forge API symbols that existing rules already resolve. High (above ~90%): a good candidate. Low: you will write
many rules, and every one you share helps the next person.

## 2. Make a profile from the template

```
cp -r ports/_template ports/mymod        (Windows: copy the folder)
```

Edit `ports/mymod/mymod.port.yaml`:

- `name`, `mode` (`full`: the mod does not exist on the target version; `delta`: it is an addon over a base that does),
- `inputs`: where the sources come from (a source archive with a download page and its sha256, or `url` for an open-licence
  mod),
- `closure.prefixes` (the mod's own packages) and `closure.seeds` (the classes the mod starts from: its `@Mod` class and
  every class something finds by annotation), `closure.cut` for registry-like hubs,
- `prepare` (extract + assets steps, already in the template) and the generator unit's `--modid` and `--package`.

The template's comments say what each key does; the schema is `contracts/port-config.schema.json`.

## 3. Run it

```
ports/mymod/run.sh        (Windows: ports\mymod\run.bat)
```

`porter run` checks the inputs, does the one-time setup and runs the port. The first run will most likely stop at the compile
stage with errors: that is normal.

## 4. Read `port-report.md`

It is at `.porter/runs/mymod/project/build/porter-port/port-report.md`. It has the stage results, the coverage and the
**not-ported list**: every generator skip, dropped class, stubbed hub and unresolved symbol, each with a reason. Nothing is
dropped silently.

## 5. Resolve the distinct symbols

The compile stage writes `symbols.json` next to the report: the errors grouped by **distinct symbol** (one entry per API
member, with a count). Resolve each symbol **once**:

1. a **rule** (`rules/`, a before/after test is mandatory) when the mapping is general,
2. an **adapter** (a small class in the shim) when the old API has no equivalent call,
3. a **counted edit** (`install.edits`) for a one-off in the mod's own code, with the reason.

Then run the same command again: it resumes from the stage that changed (`--restart` starts over). Repeat until compile
has 0 errors and the build passes. Details and the rule format: `CONTRIBUTING.md` and `rewrite/README.md`.

## 6. Write scenarios

A scenario (`ports/mymod/scenarios/*.scenario.yaml`, schema `contracts/scenario.schema.json`) is a short list of steps
("place this block, let 10 ticks pass, check the light level"). The same scenario runs on a real server of the original
version and of the ported version and the traces are compared, so you learn whether the port behaves like the original.
Start from `testkit/scenarios/` (the furnace one is the template) and keep each scenario to one behaviour. Run them with
`porter test diff` (setup of the two test servers: `testkit/README.md`; `porter run --verify` uses the same setup).

## 7. Play it

Copy the jar from `output/` into a 1.7.10 Forge instance and look at what the report lists as not ported. Then
[share your port](share-your-port.md).
