# Share your port back

A port helps everybody who owns the same mod, and every rule you wrote helps the next port. This page is the short version;
`CONTRIBUTING.md` has the details.

## What goes where

```
ports/<mod>/
  README.md             what the mod is, author and licence, which files to download (links to download pages),
                        the one command, expected time, what was verified
  <mod>.port.yaml       the port config, with inputs: (page, file name, sha256 of every original file)
  run.sh, run.bat       copies of ports/_template/run.sh and run.bat (they start porter)
  scenarios/            *.scenario.yaml checks, vanilla blocks or the mod's own ids
  overlay/, project/    small hand-written files the port needs (an entry class, gradle.properties), if any
ports/catalog.yaml      one entry for the mod and version pair
```

`ports/README.md` lists all ports: it is generated from `catalog.yaml` with `bin/porter catalog --write`.

## Licence rules (they are not optional)

- **Never publish a mod's files.** The README links to the download pages; it never contains or links to the jar itself,
  and `inputs[].url` (a direct link) is allowed only for open-licence files.
- **A ported jar may be published only** for open-licence mods (MIT, Apache-2.0, GPL, CC-BY ...) or with the original
  author's written permission (`distributable: true` plus `permission: <link>` in the catalog). Otherwise everybody builds
  it from their own copy of the original: that is what `porter run` is for.
- **A profile must not contain code derived from a non-open mod**: class names, seeds and hub prefixes are fine; decompiled
  code, counted edits that quote the mod's code, IR or traces of it, assets, mapping files are not. Keep those in a local
  overlay directory that is not in the repository.
- Credit the author in the README and keep the original licence file with the port.

## What CI checks on your pull request

`porter run` of your port on a clean runner, its scenarios on dedicated servers and an automatic smoke scenario (the server
starts with the mod, every block and item registers, no exceptions in the log). Details and the one-run-per-hour limit:
`CONTRIBUTING.md`, "CI for ports". Two things to do in the profile:

- put a **direct file link** in `inputs[].url` (Modrinth CDN, GitHub release/archive, CurseForge files CDN), only for
  open-licence files; the download page stays in `page:`. A page link fails CI with an explanation.
- no scenario files yet? Add `smokeOnly: true` to the catalog entry (and remove it as soon as you add scenarios).

## Steps

1. Add the catalog entry (`id`, mod name/version/url, `from`/`to`, `mode`, `status`, `licence`, `distributable`, `profile`).
   `status` is honest: `compiles`, `builds`, `scenarios` (scenario tests pass) or `playable` (you played it).
2. `bin/porter catalog --write` to refresh `ports/README.md`.
3. `scripts/check.sh` (build, tests, contract examples, catalog) and `scripts/publish-check.sh` (it fails on mapping files,
   decompiled code, jars, local paths, e-mail addresses). Both must pass.
4. Open a pull request with the template: what you ported, how it was verified, the licence checklist.

Reviewers look at: the licence column, that the README's download links are pages and the checksums match, that the profile
runs from a clean folder with one command, and that the scenarios mean something.
