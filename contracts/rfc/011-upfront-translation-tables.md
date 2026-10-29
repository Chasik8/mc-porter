# RFC 011: upfront translation tables (api-inventory and match table)

- Status: proposed (formats only; the stage that writes them is not implemented yet)
- Contracts: two new contracts, additive: `upfront-inventory` (`contracts/upfront-inventory.schema.json`, suffix
  `.api-inventory.json`) and `upfront-match-table` (`contracts/upfront-match-table.schema.json`, suffix
  `.match-table.json`). No existing contract changes.
- Code: `port/src/main/java/dev/porter/port/upfront/model/` (`SymbolKey`, `Kind`, `ApiSymbol`, `ApiInventory`,
  `Plan`, `Target`, `Alt`, `MatchRow`, `MatchTable`).

## Motivation
Today a port run discovers its symbols one compile error at a time: rewrite, javac, one rule per symbol, re-run.
The upfront stage decides, before any port run, what every 1.19.2 platform symbol a mod references becomes on the
target. Its output is data: an inventory of symbols, and a table with one row per symbol. Rules, shim skeletons and
a batch of TODO bodies are generated from that table. The other pieces (matcher, concept tables, emitter, shim
generator) are separate changes that code against the formats fixed here.

## Symbol key
One text form for every symbol, used as the identity in the inventory, the match table and the bodies DB:

```
type:net.minecraft.world.item.ItemStack
method:net.minecraft.world.level.Level#getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;
field:net.minecraft.world.entity.Entity#tickCount:I
```

- Owners are mojmap binary names with dots; nested classes use `$`. Descriptors are JVM descriptors (slashes).
- `type:` has no member part. `method:` names the method (constructors are `<init>`); `field:` separates name and
  descriptor with the last-free `:` after the `#`.
- `SymbolKey.parse` and `toString` are inverses. Descriptors are checked with a regular expression, not a full parser.

## api-inventory.json
Fields: `schemaVersion` (1), `from`, `scope`, `sources {jar, srcRoot, classes}`, `symbols[]`, `summary
{types, members, byOrigin}`. Each symbol: `key`, `uses`, `origin` (`bytecode` and/or `source`), `access` (JVM flags,
-1 when unknown), `declaredIn` (the class that declares the member, found by walking the 1.19.2 hierarchy up from
the referenced owner), `refClasses`, and `signature` (optional, nullable). `ApiInventory.write` sorts symbols by key
so two runs write identical bytes.

## match-table
One row per symbol. TSV columns, in order:

```
key kind uses area resolvedBy plan method confidence t_owner t_name t_desc t_static shim ruleId note
```

`kind` is the key's kind (also derivable from the key, kept for the TSV reader). `plan` is one of `MAP`, `EXPAND`,
`MIRROR`, `REGISTRY`, `HINT`, `TODO`. `resolvedBy` is `rule`, `graph` or `none`. `method` is one of the matcher's
method names (`lineage`, `lineage-concept`, `lineage-sig`, `scored:<owner-source>`, `same-fqn`, `simple-name`,
`registry`, `mirror`, `none:<reason>`).

The JSON document is `{schemaVersion, rows[]}`. Each row has the same fields as the TSV, except that the target is
nested as `target {owner, name, desc, isStatic}` (or absent for rows without one), and that rows also carry
`features` (named scores) and `alternatives` (at most three `{owner, name, desc, score}`). `kind` is not in the JSON
row: the key holds it.

Both writers sort rows by area (null first), owner, name, descriptor and kind. Null text is an empty TSV cell.
Tabs and newlines in text are written as spaces. The TSV reader does not restore features or alternatives.

## Compatibility
Additive. The schema files and the two `Contract` ids are new; `schemaVersion` is 1 for both. A later version adds
optional fields; removing or renaming a field needs a new RFC.
