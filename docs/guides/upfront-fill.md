# Filling the TODO bodies (`porter todo-batch`, `porter bodies check`)

After `porter shimgen` the members without a 1.7.10 counterpart still throw `porter:todo`. This step splits them
into one batch per worker, lets cheap workers fill the bodies in parallel, and compiles all of them in one javac.

## 1. Split

```
porter todo-batch --todo todo.tsv --table match-table.json --inventory api-inventory.json \
  --src <mod sources> --workers 8 --out todo-batch/
```

Writes `todo-batch/<n>.md` (one per worker) and `todo-batch/index.tsv` (worker, file, entries, weight, area).
All entries of one owner class go to the same worker; weights are balanced (TODO 1, HINT 0.5) and an area stays on
one worker where possible. Each entry has the key, the generated signature, plan, hint, top-3 candidates, use count
and up to two call-site lines of the mod (searched only in files of classes that reference the symbol).

## 2. Fill

Each worker writes only `rules/upfront/1.19.2__1.7.10/bodies/<area>-<n>.yaml` (format in `upfront-shimgen.md`) and,
if needed, generic helpers in `bodies/helpers/*.java`. A body that needs mod code is rejected: mod-specific code
stays in the profile overlay. A duplicate key is an error.

## 3. Check

```
porter bodies check --bodies rules/upfront/1.19.2__1.7.10/bodies --gen <shimgen out> \
  --cp <mcp jar>;<adapters> [--todo todo.tsv] [--helpers dir] [--report file]
```

Splices all bodies into the generated sources, runs ONE javac, lists failing keys with the compiler message and
sets `verified: compile` for the keys that compiled. Exit 0 when every filled body compiles. With an empty bodies
DB the report's unfilled count equals the TODO count.

## Rules for a batch

- Write bodies only to `rules/upfront/1.19.2__1.7.10/bodies/<area>-<n>.yaml` (one document per key, `verified: none`).
- Use the listed candidates and hint first; `self` is the 1.7.10 object; Java 8 only.
- Generic code only: no mod classes. A helper used by several bodies goes to `bodies/helpers/*.java`.
- An entry that cannot be filled is left out; keep a list of such keys with the reason.
- Run `porter bodies check` once for all batches, not per batch.
