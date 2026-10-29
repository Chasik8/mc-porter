# Bodies DB

`<area>-<n>.yaml`, YAML documents separated by `---`, one per symbol key; a key occurs once in the whole directory
(a duplicate is an error). `body` is statements without braces, `imports` are fully qualified names,
`verified` is `compile | scenario | none`. `porter bodies check` sets `compile` after one javac over all bodies.
Helpers shared by bodies go to `helpers/*.java` (generic code only). Bodies must not reference mod code.
Format details: docs/guides/upfront-shimgen.md.
