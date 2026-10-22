# RFC 010: kept hub members, source constructors, registry holders

- Status: accepted (implemented on p5/barrels)
- Contracts: port-config (additive: `install.stubs.keep`); IR: two extractor extensions (`porter-extract:*`, free-form
  by design, no schema change)

## Motivation
A delta port that ports a mod's logic whole (the closure) still had hand-written files: hub replacements (the cut
registries and service singletons the closure reads), the wiring of generated content to the ported classes (a GUI
handler edited by hand, rules from registry fields to the generated content fields written by hand) and constructors
of logic classes that the generator called with its own signature. Each of them is derivable from the source:

| hand-written before | derived now |
|---|---|
| hub class replacing a stub because a few members need a value | the generated stub keeps listed members; their body/initializer comes from a rule declaration edit (RFC 006) |
| `new CupItem(registryName, unlocalizedName, textureName)` contract for shared logic classes | the registry's own construction `new CupItem("cup_tea", Drinks.TEA)` (`--source-constructors`) |
| GUI handler of generated menus edited to delegate ported containers | `--gui-delegate <class>` |
| rules `ModItems#CUP -> Content.itemCup` | generated from the registry holder fields (`porter-extract:holders`) |

## Change
- port-config `install.stubs.keep`: list of `a.b.Hub#member` (source FQN, member name: field, method - all overloads -,
  or nested type). A kept member is copied as rewritten, whether the closure references it or not; calls of kept
  methods do not make an initializer "hub-local". Typical pairing: a declaration rule `edit: body`/`initializer`.
- Hub stubs (no contract change): a constructor that starts with `super(args)` keeps that call (only when the stub keeps
  a superclass), members naming nested types the stub drops are dropped, initializers calling the hub's own stubbed
  methods by qualified name are dropped, and a hub whose type a rule redirects elsewhere gets no stub.
- IR extension `porter-extract:newArgs` (item, block): the source arguments of the registry's `new Logic(...)` call,
  fully qualified, only when every argument is a literal, a static field / enum constant or a nested `new` of those.
  `porter select` relocates the names in them.
- IR extension `porter-extract:holders` (item, block, fluid, effect): the mod's static fields that hold the registered
  object after the registration code ran (`a.b.ModItems#CUP`).
- `porter generate --source-constructors`: logic classes are constructed with `newArgs` (no shared base/stub), names set
  after construction. `--gui-delegate <fqn>`: menus with a logic class (and the delegate's own ids) open through the
  delegate's static `handles/server/client`. Every run with holders in the IR writes `porter-rules/holders.rule.yaml`
  (not installed into the project; for `porter rewrite --rules`).

## Versioning
Additive. Configs without `keep` and IR without the extensions behave as before; the generator options are off by
default.
