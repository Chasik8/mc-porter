# IC2 Guide (in-game book)

Item `ic2cextras:guideBook` ("IC2 Guide" / «Справочник IC2»). Recipe: shapeless, vanilla book + any `ingotCopper`
(IC2C copper). Right click opens it; shift + right click on a multiblock controller toggles a client-side projection of
the structure (config `guide.guideHologram`, default on).

Code: `ic2cextras.book` (item, registration, `Structures` = adapter from the porter-generated multiblock definitions
`ic2cextras.ported.steam.gen.multiblock.Ic2cextrasMultiblocks`) and `ic2cextras.book.client` (GUI, layout, preview, hologram).
Carried over from the hand port (the hand port, 8522e0f); the dev screenshot hook was not carried over.

## Adding a page

1. Add an entry to `src/main/resources/assets/ic2cextras/guide/guide.json` (or ship another `guide/guide.json` for the
   `ic2cextras` domain in a resource pack: all files are read and categories with the same `id` are merged; an entry with
   an existing `id` replaces the old one).
2. Add its texts to `lang/en_US.lang` and `lang/ru_RU.lang`.

```json
{ "id": "steam", "title": "ic2cextras.guide.cat.steam", "entries": [
  { "id": "steam_tunnel",              // title key defaults to ic2cextras.guide.<id>
    "icon": "ic2cextras:steam_tunnel",  // item spec, see below
    "pages": [
      { "type": "text", "text": "ic2cextras.guide.steam_tunnel.text" },
      { "type": "multiblock", "structure": "ic2cextras:steam_tunnel" },
      { "type": "recipe", "items": ["ic2cextras:steam_tunnel", "ic2:steamReactor"] }
    ] } ] }
```

Page types:

| type | fields | shows |
|---|---|---|
| `text` | `text`: lang key | word-wrapped text, flows over as many page sides as needed |
| `recipe` | `items`: list of item specs | every real recipe for those items from `CraftingManager` (vanilla shaped/shapeless, Forge ore recipes, IC2C AdvRecipe/AdvShapelessRecipe) and the furnace; 2 per side, ore-dict alternatives cycle every second; clicking an ingredient that has its own entry opens it |
| `multiblock` | `structure`: `MultiblockDef.id` (e.g. `ic2cextras:fuel_boiler`), optional `title` lang key | a full spread: 3D preview (drag/zoom/layers/valve toggle/reset) on the left, parts with counts and a layer map on the right. Rendered from `ic2cextras.book.Structures.all()` (converted from the generated `Ic2cextrasMultiblocks`), so new structures only need this one line |

Item specs: `modid:registryName[@meta]` (e.g. `ic2cextras:glass_whisky`, `minecraft:potion@8194`) or
`ic2:<field of ic2.core.Ic2Items>[@meta]` (e.g. `ic2:reactorSteamVent`). Unknown items/structures/page types are logged
and skipped, never crash the book.

## Text format (.lang)

A .lang value is one line, so escapes are used inside the value:

- `\n` line break, `\n\n` empty line
- `\p` start a new page side
- a line starting with `## ` is a sub-heading (bold, blue); it is never left alone at the bottom of a side
- `§l`, `§o`, `§9` ... Minecraft formatting codes work
- long texts can be split over several keys: `key`, `key.2`, `key.3`, ... are joined with a line break
- avoid `%d`/`%f`-like sequences in texts (the 1.7.10 lang loader rewrites them)
