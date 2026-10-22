# Share your port

A port helps everybody who owns the same mod, and every rule you wrote helps the next port. This page is the short
version; [CONTRIBUTING.md](../../CONTRIBUTING.md) has the details.

## What goes where

```
ports/<mod>/
  README.md             what the mod is, author and licence, which files to download (links to the download pages),
                        why there is no jar, the one command, expected time, what was verified, what differs
  <mod>.port.yaml       the port config, with inputs: (page, file name, sha256 of every original file)
  run.sh, run.bat       copies of ports/_template/run.sh and run.bat (they start porter)
  scenarios/            *.scenario.yaml checks (vanilla blocks or the mod's own ids)
  overlay/, project/    small hand-written files the port needs (an entry class, gradle.properties), if any
  rules/                rules only this port needs, if any (generic Minecraft/Forge rules go to rules/)
ports/catalog.yaml      one entry for the mod and version pair
```

`ports/README.md` lists all ports. Its table is generated from `catalog.yaml`: run `bin/porter catalog --write`, never
edit the table by hand.

## Licence rules (not optional)

- **Never publish the mod's files.** The README links to the official download pages; it never contains or links to
  the jar itself. `inputs[].url` (a direct download link) is allowed only for open-licence files.
- **Never publish the ported jar,** not even for an open-licence mod. The project keeps one rule for every port:
  everybody builds the port from their own copy of the original, which is what `porter run` is for. Say so in the
  port's README (copy the paragraph from `ports/torchmaster/README.md`). The catalog's `distributable` field only
  records whether the licence would allow sharing (`true` for open licences, or for a closed one with the author's
  written permission in `permission:`).
- **A profile contains nothing derived from a non-open mod:** class names, seeds and hub prefixes are fine; decompiled
  code, edits or patches that quote the mod's code, its IR or traces, assets and mapping files are not.
- Credit the original author in the README, say the port is unofficial and not supported by them, and keep the
  original licence file with the port when the licence asks for it.

## What CI checks on your pull request

- `scripts/check.sh` and `scripts/publish-check.sh` (build, every rule test, contract examples, catalog, publish
  safety).
- For every `ports/<mod>/` you add or change: `porter run` of your port on a clean Linux runner, then an automatic
  **smoke scenario** (the server starts with your jar, every block and item registers, no exceptions in the log), then
  your own server scenarios. They run against the original 1.19.2 mod (a differential test) only when the original jar
  has a direct `url`; otherwise they run on the port alone.
- At most **one CI run per hour per contributor**. A throttled run is red with the time you may push again.

CI can only build a port whose required inputs have a direct `url:` (Modrinth CDN, GitHub release or archive,
CurseForge files CDN), so only open-licence ports. A download *page* fails with an explanation. A port without such
links (a mod that is not open-licence) is skipped by CI, says so in the run summary, and is verified by the maintainer
locally before merging.

No scenarios yet? Add `smokeOnly: true` to the catalog entry, and remove it when you add scenarios (CI checks both
ways).

## Steps

1. Add the catalog entry (`id`, mod name/version/url, `from`/`to`, `mode`, `status`, `licence`, `distributable`,
   `profile`). `status` is honest: `compiles`, `builds`, `scenarios` (scenario tests pass) or `playable` (you played it).
2. `bin/porter catalog --write` to refresh the table in `ports/README.md`.
3. Run `scripts/check.sh` (needs a JDK 21 in `JAVA_HOME` and a clone of the repository) and
   `scripts/publish-check.sh`. Both must pass.
4. Open a pull request with the template: what you ported, how it was verified, the licence checklist. For a mod that
   is not open-licence, paste the output of `porter inventory` (see CONTRIBUTING.md).

Reviewers look at: the licence column, that the README links to download pages and the checksums match, that the
profile runs from a clean folder with one command, and that the scenarios mean something.
