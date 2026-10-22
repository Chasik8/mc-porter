# RFC 007: `inputs` and `prepare` in the port config; `porter run`

- Status: accepted (implemented, release 20261021)
- Author: porter maintainers
- Contracts: port-config
- schemaVersion: 1 -> 1 (additive: two new optional top-level keys)

## Motivation
A player who wants a ported mod should not need to know porter's stages. Today the steps in front of `porter port`
(symbol graph, supertype table, Forge compile jars, the 1.7.10 project, extract, assets, the classpath file) are a
page of manual commands (`ports/torchmaster/README.md`), and nothing in a profile says which original files the
player must supply or how to recognise the right ones. Two things are missing in the config:

1. the list of original files (a mod jar, sometimes a source archive) with a download page, exact file name and
   checksum, so porter can say in plain words what is wrong;
2. the one-time commands a profile needs in front of the port.

## Change (port-config schema)
- `inputs` (optional array). Each item: `name` (key for `--input name=path`), `mod`, `version`, `kind`
  (`jar`, default, or `sources`: a .zip/.tar.gz of a source tree), `page` (https download page, never the file),
  `file` (exact file name), `sha256`, optional `url` (direct link, only for files of open-licence mods: porter fetches
  it itself when no file is given) and `optional` (the run works without it).
- `prepare` (optional array of `{name, args, jvmArgs}`): `porter` command lines `porter run` executes once before
  `porter port` (skipped when already done).
- Nothing else changes: `porter port` ignores both keys, every existing config stays valid.

## The command (not a contract, documented here for profile authors)
`porter run <port-dir> [--input name=path ...] [--verify]`; `<port-dir>` holds `<name>.port.yaml` (or pass the file).
1. Inputs: each is taken from `--input`, else from the port dir (`inputs/` or next to `run.sh`), else from the
   current directory, else downloaded from `url`. Its sha256 is checked; a missing or wrong file stops the run with
   the download page and the exact file name. Nothing is run before all inputs are right.
2. One-time setup, keyed by the port's `from`/`to` pair (Forge 1.19.2 -> 1.7.10 today): JDKs, symbol graph,
   Forge compile jars, supertype table, the 1.7.10 project (the port's `project/` directory over the GTNH template).
   Every download is pinned (sha1/sha256) and cached under `$PORTER_HOME` (default `.porter` next to the
   distribution); a finished step leaves a stamp and is not repeated.
3. `prepare` steps, then `porter port` (resumable), then the finished jar's path is printed.

Variables `porter run` defines for the config: `${PORTER_HOME}`, `${PORTER_CACHE}`, `${RUN_DIR}` (workspace of this
port: `$PORTER_HOME/runs/<name>`; the 1.7.10 project is `${RUN_DIR}/project`), `${GRADLE_USER_HOME}`,
`${BUILD_JAVA_HOME}`, `${PORT_DIR}` (the profile directory) and, per input, `${INPUT_<NAME>}` (the verified file)
and `${INPUT_<NAME>_DIR}` (kind `sources`: the unpacked tree).

## Examples
`contracts/examples/port-config/valid/with-inputs.port.yaml`, invalid `inputs-bad-hash-and-page.port.yaml`.

## Migration
None.
