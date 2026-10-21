# graph — Minecraft symbol graph across versions

Contract: `contracts/symbol-graph.schema.json`.

```
porter graph build [--versions 1.19.2,1.18.2,1.16.5,1.12.2,1.7.10] [--cache <dir>] [--out <dir>] [--offline] [--no-matcher]
porter graph query <symbol> --from 1.19.2 --to 1.7.10 [--graph <dir>] [--out <file.jsonl>]
porter graph query --batch <specs.txt> --from 1.19.2 --to 1.7.10 [--graph <dir>]
```

* Cache: `--cache`, else `$PORTER_CACHE/graph`, else `~/.cache/porter/graph`. Downloads go to `<cache>/dl/<artifact>/`.
* Output: `--out`, else `<cache>/out`. **On this machine the shared, ready-built graph is
  `%PORTER_CACHE%\graph\out\`** (`PORTER_CACHE=%PORTER_CACHE%`).
* JVM: the build runs with a 2 GB heap (`JAVA_OPTS="-Xmx2g -XX:ActiveProcessorCount=2"`), a full build takes ~7.5 min on
  this machine (4 threads, 2 used by Matcher).
* Symbol specs: `a.b.C`, `a.b.C#name` (all overloads/fields of that name), `a.b.C#name(JVM-desc)ret`,
  `a.b.C#name(BlockPos, int)` (parameter types by simple or full name). Owners may use `/`, may be a simple class
  name; member names may be given in any namespace of `--from` (mojmap, srg `m_7702_`, mcp, obf).
* Output: one contract `queryResult` per resolved symbol (pretty JSON if exactly one, else JSON Lines), validated
  against the contract before printing. Run report: `reports/runs/graph-query.json` / `graph-build.json`.

Java API for other modules (rewriter, loop): `dev.porter.graph.query.SymbolGraph`
```java
SymbolGraph g = SymbolGraph.open(Path.of("$PORTER_CACHE/graph/out"));   // lazy, load once, thread-safe
for (SymbolRef r : g.resolve("1.19.2", "net.minecraft.world.level.Level#getBlockEntity")) {
    QueryResult q = g.query(r, "1.7.10");      // q.path(), q.status(), q.confidence(), q.results(), q.toJson()
}
g.info(ref).names();   // all namespace names of a symbol, g.info(ref).params(): Parchment/MCP parameter names
```

## Output directory

| File | Content |
|---|---|
| `symbols-<v>.graph.json` | contract `graph` document: one version, every non-synthetic class/method/field of its client jar with names in all namespaces (`obf`, `mojmap`, `srg`, `mcp`), JVM access flags, parameter names (Parchment 1.19.2, MCP 1.16.5/1.12.2/1.7.10); `edges: []` |
| `edges-<newer>__<older>.graph.json` | contract `graph` document: both versions + one edge per symbol of the newer version (`removed` when unmatched) |
| `manifest.json` | chain, canonical namespaces, per-pair statistics (not a contract) |
| `hierarchy-<v>.tsv` | class, super class, interfaces (canonical names) — not a contract; `SymbolGraph.supertypes()` |
| `mappings/<v>.tiny` | merged mappings of the version, Tiny v2 (the normalised internal format), namespaces `obf` + the rest |

Canonical namespace (names in `symbolRef`): `mojmap` for 1.19.2, 1.18.2, 1.16.5; `mcp` for 1.12.2, 1.7.10. Edges point
newer -> older; queries upwards use the inverted edges (`split` <-> `merged`) and report `removed` when an older
symbol has no counterpart. Files are deterministic (same inputs -> byte-identical outputs, see tests).

Mojang's mappings may not be redistributed "complete and unmodified": the built graph lives only in the local cache,
never in git.

## Sources (verified 2026-10-18)

All inputs are listed with URL and published sha1 in `src/main/resources/dev/porter/graph/sources.json`; the build
verifies every file against it (downloads are only parsed, never executed). Nothing is invented: every URL below was
fetched and checksum-checked.

| Version | Input | Source | sha1 |
|---|---|---|---|
| all | client jars + client mappings | Mojang `piston-meta.mojang.com/mc/game/version_manifest_v2.json` -> per-version json -> `downloads.client` / `downloads.client_mappings` | in sources.json (== version json) |
| 1.19.2 | client.jar | piston-data …/055b30d8…/client.jar | 055b30d860ead928cba3849ba920c88b6950b654 |
| 1.19.2 | Mojang mappings | piston-data …/8e8c9be5…/client.txt | 8e8c9be5dc27802caba47053d4fdea328f7f89bd |
| 1.19.2 | SRG (`m_`/`f_` ids, Forge production names) | `maven.minecraftforge.net` `de.oceanlabs.mcp:mcp_config:1.19.2-20220805.130853@zip` (`config/joined.tsrg`, tsrg2). Same version as `forge-1.19.2-43.4.0-userdev` `config.json` | f825e1ca07c31b1b3046fc459ba8823eed535818 |
| 1.19.2 | Parchment (parameter names) | `maven.parchmentmc.org` `org.parchmentmc.data:parchment-1.19.2:2022.11.27@zip` (latest release for 1.19.2) | df6e33532eda7e5482c734c9241a15ff46d8d211 |
| 1.18.2 | client.jar / Mojang mappings | piston-data …/2e9a3e31…/client.jar, …/a661c6a5…/client.txt | 2e9a3e31…, a661c6a5… |
| 1.16.5 | client.jar / Mojang mappings | piston-data …/37fd3c90…/client.jar, …/374c6b78…/client.txt | 37fd3c90…, 374c6b78… |
| 1.16.5 | SRG (obf -> srg, MCP class names) | `de.oceanlabs.mcp:mcp_config:1.16.5-20210115.111550@zip` (`config/joined.tsrg`); version taken from `forge-1.16.5-36.2.39-userdev` `config.json` | a81464c686be42933aacac7898f8c0d50edccf51 |
| 1.16.5 | MCP names | `de.oceanlabs.mcp:mcp_snapshot:20210309-1.16.5@zip` — **there is no `mcp_stable` for 1.16.5**; this is the last MCP export (MCP ended in 2021) | f6273df8817a92d41cf417a1a5935c08e09035ec |
| 1.12.2 | client.jar | piston-data …/0f275bc1…/client.jar (no Mojang mappings exist for 1.12.2) | 0f275bc1547d01fa5f56ba34bdc87d981ee12daf |
| 1.12.2 | SRG | `de.oceanlabs.mcp:mcp:1.12.2:srg@zip` (`joined.srg`) | 9a583f5bceeb1c1771c8a3a899eee5d7fa2b2e68 |
| 1.12.2 | MCP names | `de.oceanlabs.mcp:mcp_stable:39-1.12@zip` | eead02d7aea31dcd0e2080cac702720a0b979a6e |
| 1.7.10 | client.jar | launcher.mojang.com …/e80d9b3b…/client.jar; **reused** from the RFG cache `~/.gradle/caches/retro_futura_gradle/mc-vanilla/1.7.10/client.jar` (same sha1) | e80d9b3bf5085002218d4be59e668bac718abbc6 |
| 1.7.10 | SRG | `de.oceanlabs.mcp:mcp:1.7.10:srg@zip` (`joined.srg`); byte-identical to RFG's `caches/minecraft/de/oceanlabs/mcp/mcp_stable/12/joined.srg` and Forge 10.13.4.1614 `conf/packaged.srg` (sha1 4a4a3501…) | 10b95bb342f55d681025bf2d698c893d9c4b4201 |
| 1.7.10 | MCP names | `de.oceanlabs.mcp:mcp_stable:12-1.7.10@zip`; its csv files are byte-identical to RFG's unpacked `mcp_stable/12/*.csv` used by RFG 1.7.10 projects | 690fe46a9208004f0336c0378b223ffd5f9b2cae |

Libraries: `net.fabricmc:mapping-io:0.7.1` (Maven Central), `net.fabricmc:matcher:0.1.0` (FabricMC maven
`https://maven.fabricmc.net/`, restricted in settings.gradle.kts to that single module; not on Central), ASM 9.7.1.

## How the graph is built

1. **Load** (per version, `load/VersionLoader`): all mapping files are read with mapping-io into one
   `MemoryMappingTree` (src `obf`; ProGuard -> `mojmap`, SRG/TSRG/TSRG2 -> `srg`, MCP csv -> `mcp`), written as Tiny v2,
   then the client jar is walked with ASM and every non-synthetic, non-bridge class/method/field is named in all
   namespaces (inherited override names are looked up through the jar hierarchy; unmapped inner classes inherit the
   outer name).
2. **Name anchors** (`match/AnchorMatcher`), per adjacent pair, in the name world both versions share:
   - mojmap pairs (1.19.2↔1.18.2↔1.16.5): same class name; same member name + descriptor;
   - SRG pairs (1.16.5↔1.12.2↔1.7.10): same MCP class name; same SRG id (`func_N_x`/`field_N_x` — MCP kept these
     ids across versions on purpose); that is also the **stitch**: 1.16.5 carries both mojmap and srg/mcp names, so
     1.19.2 mojmap names reach 1.7.10 MCP names through it;
   - renamed classes by member votes (reciprocal best, ≥ half of both classes' keys);
   - same human name (mojmap / MCP), no overloads on either side, other descriptor -> signature change
     (e.g. `World.getTileEntity(III)` ↔ `getTileEntity(BlockPos)`, where MCP gave the 1.8 method a new SRG id);
   - members whose id moved to another class.
3. **Bytecode matching** (`match/MatcherBridge`): the FabricMC Matcher core runs headless as a library on both
   obfuscated jars, the anchors are applied as fixed matches, then its classifiers (class/method/field signatures,
   hierarchy, call graph in/out, field reads/writes, string and numeric constants, instruction similarity) run
   iteratively (classes -> methods/fields -> classes, levels Initial..Extra, thresholds 0.85/0.085 as in Matcher).
   Matcher-only matches get evidence `bytecode-similarity`, `matcher` and a confidence from Matcher's similarity;
   anchors that Matcher also derives get `bytecode-similarity` added.
4. **Edges** (`match/EdgeBuilder`): class: same / renamed (same package) / moved; member: signature_changed if the
   descriptor differs after translating its types through the class matches, moved if declared in another class,
   renamed if the name or owner name differs, else same; unmatched -> removed. Names are compared in each version's
   canonical namespace, so crossing mojmap (1.16.5) -> MCP (1.12.2) shows as renamed/moved — exactly what a source
   rewrite has to change.
5. **Query** (`query/SymbolGraph`): follows edges hop by hop through every branch; status = worst along the path
   (same < renamed < moved < signature_changed < split|merged < removed), confidence = minimum; `missing` when the
   symbol is unknown.

## Quality (evaluated on a large private mod)

Latest (2026-10-19): golden precision 0.90 (0 wrong targets, 10 "removed" misses); coverage of the Minecraft members
used by a large 1.19.2 tech mod: 100% answered, 27% mapped to a 1.7.10 symbol (42% methods / 71% fields for the
ported subsystem); full build 7.5 min. Re-run:

```
java -cp "cli/build/install/porter/lib/*" dev.porter.graph.eval.Eval golden   <graph> graph/golden.json [report.json]
java -cp "cli/build/install/porter/lib/*" dev.porter.graph.eval.Eval coverage <graph> 1.19.2 1.7.10 <mod.jar> <out-dir> [focus-regex]
```

* `golden.json`: 100 hand-picked 1.19.2 -> 1.7.10 correspondences (World/Level, Block, BlockEntity, ItemStack/Item,
  NBT, Container/Menu, GUI, network), generated and checked by `tools/golden.py` against the real mapping files
  (both ends must exist; descriptors come from the mappings; `chain` says whether MCP's own SRG ids link the pair
  (`srg-chain`/`class-chain`) or the link is a known port correspondence whose id changed (`manual`); `to: null` =
  no counterpart, expected `removed`). Precision = correct / answered.
* coverage: `eval/UsedSymbols` scans a mod jar's bytecode (calls, field accesses, overrides of Minecraft members;
  srg/mcp/mojmap names) and queries each used symbol to the target version.
