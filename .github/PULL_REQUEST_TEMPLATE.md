## What

<!-- rules / adapter / mod profile / extractor / generator / testkit / docs -->

## Checks

- [ ] `scripts/check.sh` passes locally (build, all rule tests, contract examples, catalog, publish-check)
- [ ] New rules: one rule per distinct symbol, synthetic before/after tests, target API verified (`javap` / `porter graph query`), `provenance` set
- [ ] New adapters: added because a real port needed that symbol; no mod code
- [ ] Mod profile: `ports/catalog.yaml` entry updated; no machine-specific paths (use `vars:` / env)

## Legal

- [ ] No decompiled code of any mod, no assets of non-open mods, no mapping files, no `private/` content, no jars
- [ ] If this came from porting a mod that is not open-licence: only curated, synthetic rules are included (no candidate logs, drafts, IR or traces of that mod)
- [ ] AI-assisted: yes / no (if yes, I verified every symbol)
