# Shim skeletons, bodies DB and TODO list (`porter shimgen`)

`porter shimgen` writes Java 8 sources for the platform members a mod uses that have no 1.7.10 counterpart yet.
Every member carries its symbol key, so a body can be filled once and survives regeneration.

## Generated member

```java
    /** porter:upfront method:a.b.World#isLoaded(La/b/Pos;)Z
     *  plan TODO; candidates: blockExists 0.65, chunkExists 0.58 */
    public static boolean isLoaded(World self, Pos pos) {
        // porter:body method:a.b.World#isLoaded(La/b/Pos;)Z
        throw new UnsupportedOperationException("porter:todo isLoaded");
        // porter:end
    }
```

The text between `porter:body` and `porter:end` is the only part a body replaces. Generated files live in package
`dev.porter.shim.gen.<package of the 1.19.2 class without the leading two segments>` and are relocated by the install
like `dev.porter.shim`.

* A type that is a mirror and has no shim becomes `public class <Simple>` (one file per top-level type; nested
  types become static nested classes).
* A mirror type that already has a hand-written shim is never touched: the missing members go to
  `<Shim>.members.java`, a fragment for review.
* A type that maps to a 1.7.10 class and misses a member becomes `<Simple>Ext`, static helpers with `self` first
  (a constructor becomes `new<Simple>`, a field becomes a getter/setter pair).
* Members the shim sources already have (same name, arity and parameter simple types) are never generated.

## Bodies DB

`rules/upfront/1.19.2__1.7.10/bodies/<area>-<n>.yaml`, YAML documents separated by `---`, one per key; a key occurs
once in the whole directory.

```yaml
key: "method:a.b.World#isLoaded(La/b/Pos;)Z"
body: "return self.blockExists(pos.getX(), pos.getY(), pos.getZ());"
imports: []
verified: compile        # compile | scenario | none
```

`body` is statements without braces; `imports` are fully qualified names.

## `todo.tsv`

Tab separated, header line `key owner signature plan hint alternatives uses area`, sorted by area, owner, key. One
row per generated member whose body is still a TODO (HINT rows included, with the lineage target in `hint`).
Overloads that collapse to one Java signature after mapping are generated once; every key has its own row and the
later ones carry `same signature as <key>` in `hint`.

## Command

```
porter shimgen --table match-table.json --concepts concepts.yaml \
  --shims rewrite/shim/src/adapters/java --shims rewrite/shim/src/main/java \
  --bodies rules/upfront/1.19.2__1.7.10/bodies --out <dir> --todo <todo.tsv> [--hierarchy classes.tsv]
```
