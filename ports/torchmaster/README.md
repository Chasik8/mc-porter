# Port profile: TorchMaster 19.2.93 (Forge 1.19.2) -> Forge 1.7.10

An unofficial, full-mode port of [Torchmaster](https://github.com/Xalcon/TorchMaster) by **xalcon** (MIT): the
mod does not exist on 1.7.10, so every class is ported. It is porter's public demo: everything needed to reproduce
it is open (the mod, porter's rules and adapters), and the result keeps the MIT licence and credits the author.
The port is not supported by the original author.

| | |
|---|---|
| Original | branch `mc/1.19.2`, commit `f21e231d790d04beb86aa35392826dccededf614` (version 19.2.93); release jar for the 1.19.2 side of the scenarios: `torchmaster-19.2.93.jar` (Modrinth, sha1 `678890ce4af28f05004a2097b83a96b401331bd8`) |
| Size | 28 classes, 1,257 lines, 373 distinct Minecraft/Forge API symbols |
| Ported | all 28 classes, 0 counted edits, 1 wiring class (`overlay/`: the 1.7.10 `@Mod` entry point) |
| Scenarios | 6 differential scenarios (`scenarios/`), all pass on both versions with equal traces |

## Files

- `torchmaster.port.yaml` — the port config (`porter port --config`).
- `overlay/` — the 1.7.10 `@Mod` class: runs the ported 1.19.2 mod constructor inside FML's lifecycle
  (`ModLifecycle` adapter) and registers the generated recipes.
- `project/` — files to put into the 1.7.10 project: `gradle.properties` (mod id, Jabel), `dependencies.gradle`
  (no dependencies + the `porterClasspath` task), `LICENSE` (the original MIT licence), `README.md` (unofficial-port
  notice), `src/main/resources/mcmod.info` (credits).
- `scenarios/` — testkit scenarios: registry, Mega Torch and Dread Lamp spawn blocking (`spawn_check` probe),
  Feral Flare Lantern in a void world and above a floor, light levels (`light` probe).

## Reproduce

Environment: `PORTER_CACHE` (porter's cache), `TM_SRC` (a checkout of the original at the commit above),
`TM_PROJECT` (the 1.7.10 project), `TM_ORIGINAL_JAR` (the 1.19.2 release jar), `GRADLE_USER_HOME`,
`BUILD_JAVA_HOME` (JDK 21+ for the RFG build), `PORTER_TESTKIT_ENV` (testkit launch configuration).

1. Once per machine: the symbol graph (`porter graph build ...`, see the main README), the 1.19.2 mojmap compile
   jar and server install of the testkit (`testkit/mod-1.19.2/setup.sh`), and the full 1.19.2 supertype table:
   ```sh
   python -I rewrite/scripts/typefacts_from_jars.py "$PORTER_CACHE/typefacts-1.19.2/forge-1.19.2-43.5.2.tsv" \
     "$PORTER_CACHE/forge-1.19.2-compile/mc-forge-1.19.2-client-mojmap.jar" <forge-1.19.2-43.5.2-universal.jar> \
     --prefix net.minecraft. --prefix net.minecraftforge. --prefix com.mojang.
   ```
2. The project: the GTNH ExampleMod 1.7.10 template with `project/` copied over it; then the declarative content
   and the classpath:
   ```sh
   porter extract --src "$TM_SRC" --version 1.19.2 --mc-data "$PORTER_CACHE/mappings-1.19.2" --out "$TM_PROJECT/build/porter-ir"
   porter assets --src "$TM_SRC/src/main/resources" --from 1.19.2 --to 1.7.10 --out "$TM_PROJECT/build/porter-assets" \
     $(for f in "$TM_PROJECT"/build/porter-ir/*.ir.json; do printf -- '--ir %s ' "$f"; done) --legacy-names NS_CAMEL
   (cd "$TM_PROJECT" && ./gradlew porterClasspath)
   ```
3. `porter port --config ports/torchmaster/torchmaster.port.yaml` (generate, closure, rewrite, install, compile,
   build, test); `<project>/build/porter-port/port-report.md` has the result.

## What differs from the 1.19.2 mod

- Village sieges are never blocked (1.7.10 Forge has no siege event).
- Mob spawners are never blocked: 1.7.10 Forge only checks natural spawning (this is the default
  `blockOnlyNaturalSpawns=true` behaviour; `false` has no extra effect).
- Blocks render with the 1.7.10 block renderer: the generated textures on the block's box (the Mega Torch box comes
  from its 1.19.2 shape); the Dread Lamp and lantern JSON models are not converted.
- The config file is `config/torchmaster.cfg` (1.7.10 format, same keys and comments as the 1.19.2 TOML).
