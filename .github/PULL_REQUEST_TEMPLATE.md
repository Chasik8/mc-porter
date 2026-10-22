## What

<!-- rules / adapter / mod profile / extractor / generator / testkit / docs -->

## Checks

- [ ] `scripts/check.sh` passes locally (build, all rule tests, contract examples, catalog, publish-check)
- [ ] New rules: one rule per distinct symbol, synthetic before/after tests, target API verified (`javap` / `porter graph query`), `provenance` set
- [ ] New adapters: added because a real port needed that symbol; no mod code
- [ ] Mod profile: `ports/catalog.yaml` entry updated and `porter catalog --write` run; no machine-specific paths (use `vars:` / env)
- [ ] Mod profile: `ports/<mod>/` has README (download *pages*, never files; one command; time; what was verified), `inputs:` with sha256, `run.sh` + `run.bat`; `ports/<mod>/run.sh` works from a clean folder
- [ ] Mod profile for a mod that is not open-licence: paste the output of `porter inventory` (run locally against your own copy of the original; CI cannot fetch it): category C (adapted code or assets) must be 0, and the profile has no `patches/`, `textures/` or images (CI checks that part)
- [ ] Jars only for open-licence mods (or with the author's permission linked in the catalog); the profile contains no code derived from a non-open mod

## Legal

- [ ] No decompiled code of any mod, no assets of non-open mods, no mapping files, no `private/` content, no jars
- [ ] If this came from porting a mod that is not open-licence: only curated, synthetic rules are included (no candidate logs, drafts, IR or traces of that mod)
- [ ] AI-assisted: yes / no (if yes, I verified every symbol)
