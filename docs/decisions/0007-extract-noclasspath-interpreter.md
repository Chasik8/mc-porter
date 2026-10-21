# 0007. extract: noclasspath Spoon + SRG/vanilla index + extraction interpreter

Status: accepted

## Context
`porter extract` needs a Spoon model of Forge 1.19.2 mod sources. The real target (a large tech mod) is a CFR
decompile of the production jar: class names are official (Mojang) names, but every Minecraft **member** is an
SRG name (`m_60913_`, `f_76320_`). A typed classpath would therefore have to be a "official classes + SRG members"
jar, i.e. a full ForgeGradle 5 setup (MC client download, decompile, remap; minutes of CPU and ~1 GB on a shared,
tight machine). Most declarative facts (registry ids, Properties chains, slot coordinates, structures) are also
not visible to plain pattern matching: the mod builds them through its own helpers, constructor chains, loops,
enum constants and fastutil maps.

## Decision
1. **Spoon in noclasspath mode** (default). Mod types are fully resolved (they are in the sources); Minecraft and
   Forge types are known by qualified name from imports, which is all extractors match on. `--classpath` stays
   available for a typed model when jars exist.
2. **Vanilla index from the real artifacts** (rule 1), built once into the cache by `VanillaIndexBuilder`
   (`--mc-data <dir>` or `PORTER_MC_1192`, default layout under `%PORTER_CACHE%\mappings-1.19.2`):
   - SRG → official member names: MCPConfig `1.19.2-20220805.130853` `joined.tsrg` (sha1 of the zip
     `f825e1ca07c31b1b3046fc459ba8823eed535818`) joined with Mojang `client.txt` (sha1 `8e8c9be5…`) by obf class,
     member and descriptor;
   - registry ids of static holders (Blocks, Items, MobEffects, SoundEvents, Fluids, ItemTags/BlockTags/FluidTags)
     read with ASM from the `<clinit>` of the 1.19.2 client jar (sha1 `055b30d8…`); Forge `Tags$Items/Blocks/Fluids`
     from `forge-1.19.2-43.5.2-universal.jar` (sha1 `2b054f1b…`); `BlockStateProperties`; AbstractContainerScreen
     default image size.
   The index is derived from Mojang mappings and is never committed (cache only). Without it SRG names stay as
   they are and vanilla references become `unknown`.
3. **Extraction interpreter** (`interp/`): a deterministic interpreter over the Spoon model that runs the mod's
   registration code (roots = methods with registration calls that nothing calls directly, plus static
   initialisers), constructors, menus, structure previews and encoders. Non-source API is modelled by small
   builtins (ResourceLocation, BlockPos incl. packed longs, Direction, BlockState, Properties recorders, registries,
   CompoundTag/FriendlyByteBuf recorders, JDK/fastutil collections). Anything not computable is an `Unknown` value
   with a reason; an undecidable condition never selects a branch (the statement is skipped and what it assigns
   becomes unknown; registration code runs both branches and flags the facts as conditional). Registration call
   sites never reached become IR `unknown` nodes.
4. Mod-specific frameworks are **pattern packs** (`packs/<mod>`), e.g. `packs/<mod>`: names of helper methods and how
   the mod's GUI/structure/recipe API maps to IR — no mod code or data. The mod's own registry helpers need no pack
   code at all (they end in `ForgeRegistries.X.register` and are interpreted).

## Rejected
- ForgeGradle 5 workspace for a typed classpath: heavy, and still would not resolve the SRG-named decompile
  without a matching SRG jar; it would not compute values either.
- Pure syntactic pattern matching: cannot follow a mod's constructor chains (`new BarrelBlock()` → `super("barrel",
  …)`), loops and helper indirections without guessing.

## Consequences
- Extraction quality depends on builtin coverage; gaps show up as `unknown` with reasons
  (`extract-report.json → topUnknownReasons`), which is the work list for new builtins/pack rules.
- `porter:legacyName` (1.7.10 unlocalized name) is derived by one documented convention (NS_CAMEL), marked with
  `porter:legacyNameSource`.
