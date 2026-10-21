# RFC 002: guide-book chapters (`guideChapter` IR concept)

- Status: proposed (not implemented; a private adapter is used meanwhile)
- Author: porter maintainers
- Contracts: ir
- schemaVersion: 1 -> 2

## Motivation
Mods ship in-game manuals whose content is data (titles, text keys, structure previews, recipe pages) but is
built in code. A port needs it ("book": GuideNH content from the mod's in-game wiki), and the IR has no concept, so the
extractor emits `unknown` nodes with a pack-specific extension.

Evidence (private reference port run on a large tech mod, counts only):

| | count |
|---|---|
| chapters (`unknown` + a pack-specific wiki-chapter extension) | 45 (41 item chapters, 4 multi-item "basic" chapters) |
| page objects | 171: header 56, text 50, multiblock preview 23, page break 19, recipe page 6, item list 1, runtime-built lists 16 |
| objects that fit the page model below | 155 (91 %) |
| text/header lang keys resolvable in the mod's lang files | 104 / 104 |
| multiblock previews referring to an IR `multiblock` node | 21 / 23 |

The structure is generic (vanilla-style guide books, Patchouli entries, GuideME/GuideNH pages have the same
parts): an entry tied to items, an icon, an ordered list of pages, each page a list of typed objects.

## Change
New node kind `guideChapter` (common fields: `kind`, `id`, `provenance`, `confidence`, `extensions`):

```json
{
  "kind": "guideChapter",
  "id": "examplemod:copper_press",
  "category": "examplemod:machines",
  "icon": "examplemod:copper_press",
  "items": ["examplemod:copper_press"],
  "titleKey": "guide.examplemod.copper_press.title",
  "pages": [
    [ { "kind": "header", "keys": ["guide.examplemod.copper_press.head"] },
      { "kind": "multiblock", "multiblock": "examplemod:big_press" } ],
    [ { "kind": "text", "keys": ["guide.examplemod.copper_press.body"] },
      { "kind": "recipes", "items": ["examplemod:copper_press"] } ]
  ],
  "provenance": { "file": "ModGuide.java", "line": 12, "version": "forge-1.19.2" }
}
```

Page object kinds: `header {keys[]}`, `text {keys[]}` (lang keys, resolved through the IR `lang` nodes),
`multiblock {multiblock}` (IR `multiblock` id), `recipes {items[]}` (recipes producing these items; an empty list
means "the chapter's items"), `itemList {items[] | tag}`, `image {texture}`. Page breaks are the page boundaries.
Anything else stays an `unknown` object inside `extensions["porter-extract:unmappedObjects"]` (never guessed).
Optional `category` / `parent` give the navigation tree.

Producers: extract packs (the reference pack's wiki builder now; Patchouli/GuideME JSON/Markdown later). Consumers: generators
(1.7.10: GuideNH pages, or a data file for a mod-local book), assets (lang keys stay as they are).

## Compatibility and migration
Additive (new kind). `extract` stops emitting the 45 `unknown` chapter nodes for the reference mod and emits `guideChapter`;
`generate` reports `guideChapter` as unsupported until a target generator exists.

## Open questions
- GuideNH page syntax must be checked against the real GuideNH jar (not available locally); rule 1: no
  invented API. Until then the 1.7.10 generator can only write a neutral data file.
- Runtime-built object lists (16 in the reference mod, built from registries at runtime) need either interpreter support or a
  runtime export, like multiblock validators.
