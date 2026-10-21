# 0008. Closure port, adapter pack and one `porter port` command

Status: accepted (2026-10-20). Supersedes the earlier "thin shim only" design for closure ports.

## Context
The first real end-to-end port (a large mod's steam subsystem, 1.19.2 → 1.7.10, run privately) did not work
through the per-concept pipeline alone: 74% of the remaining javac errors were the mod's own framework classes
that the subsystem depends on. What worked was the maintainer's algorithm:

1. pick the top-level classes to port (seeds);
2. **copy recursively** every class of the mod they reference (unknown on the target side);
3. classes that are **known on the target side and semantically identical** are left alone;
4. **known but different** classes are copied too; every remaining difference shows up as a compile error and is
   resolved **once per distinct symbol** (a rule, an adapter or a counted edit), then the deterministic pipeline
   re-runs. Progress = number of distinct unresolved symbols, not lines.

To keep the copy finite the walk stops at *hubs* (registries, networking, ...): referenced hub classes become
generated stubs that keep only the members the closure uses (inert bodies). Measured on the reference port: uncut closure
1,877 classes; cut 281 classes + 45 hub stubs; 610 → 0 distinct symbols in 14 rounds.

That pipeline was driven by private shell/Python glue. This decision moves the generic parts into porter.

## Decision
- New module `port` with the building blocks as Java code and CLI subcommands: `closure` (walk + frontier +
  identical-API check), `stubs` (hub stubs), `passes` (srgmoj, java8, tector, edits, imports), `castfix`
  (decompiler cast artefacts, error-driven), `symbols` (errors by distinct symbol), `coverage` (reuse metric),
  `obfcheck` (reobf collision check), `drafts` (draft rules → issue markdown).
- One end-to-end command, `porter port --config <name>.port.yaml`, with stages generate → closure → rewrite →
  install → loop → compile → build → test, resumable (`<work>/port-state.json`), writing
  `<work>/port-report.{json,md}` with an explicit **not-ported** list (generator skips, dropped classes, inert hub
  stubs, unresolved symbols). The config format is a new contract, `contracts/port-config.schema.json` (RFC 004,
  additive).
- Two modes: **delta** (an addon over a base that exists on the target version — `closure.known` names the base's
  sources, identical classes are left to it) and **full** (the mod does not exist on the target; everything is
  ported, there is no base).
- **Adapter pack.** The original design limited the 1.7.10 shim to coordinates/directions. For closure ports that is not
  enough: ported code keeps calling the 1.19.2 API (block states, block entities, menus, capabilities). The
  generic adapters written once per distinct symbol (`dev.porter.shim.mc`, `.mc.client`, 81 classes) are now
  public in `rewrite/shim/src/adapters/java`, shipped inside the porter jar and copied (relocated to the mod's shim
  package) by the install stage when `install.adapters: bundled`. The matching rules live in
  `rules/packs/adapters/1.19.2__1.7.10/adapters-*.rule.yaml` (an opt-in pack: it changes how 1.19.2 types are rewritten, so it is used together with the adapter pack, not by a plain rewrite) (374 rules). Adapters contain no mod code.
- Mod-specific parts (seed list, hub list, hand-written hub overrides, counted edits, mod-to-mod bridges) stay in
  the per-port config and its private directories.

## Rejected
- Keeping the glue as bundled Python scripts: an extra runtime for users, and the logic is small enough to port
  (verified byte-for-byte against the Python on the reference port).
- A generic "facade" emulating all of Level/BlockEntity/Menu up front: adapters are added per distinct symbol that a
  real port needs, never speculatively.

## Consequences
- Public rule tests cover the adapter rules (text-level); the adapters themselves compile only with a 1.7.10 MCP
  jar (`-Pporter.mc1710Jar`), like the `world` helpers.
- The `coverage` command gives a number before any AI work: the share of a mod's distinct API symbols that existing
  rules resolve.
