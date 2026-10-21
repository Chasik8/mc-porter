# RFC 006: rule patterns, declaration edits, position and type preconditions, `$target(...)`

- Status: accepted (implemented)
- Author: porter maintainers
- Contracts: rule
- schemaVersion: 1 -> 1 (additive for existing rules; see "Versioning")

## Motivation
The first closure port (private reference port, 1.19.2 -> 1.7.10) needed 43 counted regex edits after the rewriter: places
the rule language could not express. Classified by the language feature they needed (private table):

| Needed | Edits |
|---|---|
| declaration-level edits (supertypes, members, field initializer, method body, member removal) | 10 |
| `extends` position of a type reference | 2 |
| statement removal / multi-statement patterns | 5 |
| expression shapes (nested calls, assignments in conditions, literal values) | 4 |
| nothing new (a plain call rule would have done) | 2 |
| decompiler artefacts of that one source (CFR typing) - stay counted edits | 20 |

No edit needed varargs matching or receiver-type tests through generics. `match.pattern` was already in the schema
("Spoon template for idioms") but never implemented; it is implemented here.

## Change (rule schema)
- `match.pattern` (existing field, now specified): Java code, one expression or one or more statements (text ending
  in `;` or `}`). Placeholders: `$x` used as an expression matches any expression (repeated uses must be the same
  code); `$s;` standing alone as a statement (also as an `if`/`else` branch) matches one statement or block;
  `T $v = ...` declares a local of the pattern: `$v` binds the local's name and later uses must use that local.
  Matching is on the AST: type names by simple name (generics erased), member names also through their SRG
  aliases (`--graph`), casts only when the pattern writes them, parentheses never. Lambdas, loops, `switch` and
  anonymous classes are not supported inside patterns (they never match).
- `replace` is no longer `minLength: 1`: an empty replacement is allowed for statement patterns (the statements and
  their lines are removed). Other rules with an empty replacement are reported as unsupported.
- New selector `match.declaration`: `a.b.C` (nested `a.b.C$D`), `a.b.C#field`, `a.b.C#method(P, ...)` or
  `a.b.C#<init>(P, ...)`, a declaration in the rewritten sources.
- New top-level `edit` (required with `match.declaration`, forbidden otherwise; `replace` is then not required):
  `addSupertypes` (to `implements` / interface `extends`, clause created when missing), `addMembers` (after the
  opening brace), `appendMembers` (after the last member), `initializer` (field), `body` (method/constructor),
  `remove: true` (member). Texts are inserted verbatim.
- `$target(a.b.C)` in `replace` or `edit` texts: a name of the target version that `--relocate` must not rename (an
  addon whose host mod keeps the same package on both versions: the ported copy and the host class share the FQN).

## Change (rewriter vocabulary, rewrite/README.md)
- Preconditions `position:extends` / `position:implements` for `type` rules (the reference is the superclass /
  a super-interface of a declared type) and `type:$x:<fqn>` (the expression bound to `$x` is provably a `<fqn>`).
- An expression pattern that matches a node owns it: call/field/type rules on the same node do not apply there
  (rules inside the placeholders still do). Statement patterns are tried on every statement list, in priority order.
- Rule tests of declaration rules: `before` is the whole declaration of the owner's top-level type, placed in the
  owner's package; each test is rewritten on its own.

## Versioning
Existing rule files stay valid and unchanged (`replace` is still required for every non-declaration rule through the
`allOf` condition). Readers of the old schema reject `declaration`/`edit` rules, so these rules need the porter
version that implements this RFC; `schemaVersion` stays 1 like RFC 001-005.

## Measured effect (private reference port)
43 counted edits -> 20 (the decompiler artefacts). 23 edits are rules now: 6 generic public rules cover 7 edits
(`rules/composite/1.19.2__1.7.10/loot.rule.yaml`, `chat.rule.yaml`,
`rules/packs/adapters/1.19.2__1.7.10/adapters-idioms.rule.yaml`); 14 mod-specific rules cover 16 edits and stay
with the private port profile.

## Rejected
- Generic decompiler-repair rules (retyping CFR's `Object` locals and for-each variables, generic casts): they
  depend on javac's view of the code, not on an API change between versions; the error-driven `castfix` pass is
  the place for them.
- Wildcard owners in `declaration` (e.g. "every method taking event X"): one edit would use it.
- Spoon's own `spoon.pattern` matcher: it compares qualified references, which noclasspath models of decompiled
  code do not resolve reliably.
