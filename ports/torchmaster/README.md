# Torchmaster 19.2.93 on Minecraft 1.7.10

An unofficial port of [Torchmaster](https://github.com/Xalcon/TorchMaster) (Mega Torch, Dread Lamp, Feral Flare Lantern,
Frozen Pearl: they stop monsters from spawning around them) from Forge 1.19.2 to **Forge 1.7.10**. The mod does not exist
on 1.7.10, so every class is ported. It is porter's public demonstration: everything it needs is open.

| | |
|---|---|
| Original author | **xalcon** |
| Licence | MIT. The port keeps it and credits the author (`project/LICENSE`, `project/src/main/resources/mcmod.info`). It is not supported by the original author: do not report its bugs to them. |
| Ported jar | may be shared (MIT), but you can also build it yourself in the time below |
| Result | `output/torchmaster-0.1.0.jar` for Forge 10.13.4.1614 on Minecraft 1.7.10, no other mods needed |

## What to download

Nothing is required: the mod's source (MIT) at version 19.2.93 is fetched by porter itself from
[GitHub](https://github.com/Xalcon/TorchMaster/tree/f21e231d790d04beb86aa35392826dccededf614) and checked against its
checksum.

Optional, only for contributors who re-run the scenario checks (`--verify`, needs the testkit servers, see `testkit/README.md`): the original release jar
[`torchmaster-19.2.93.jar`](https://modrinth.com/mod/torchmaster/version/19.2.93) from Modrinth (sha256
`9c82c26524491013fb4c81809331b3a2ff5eb9ff76a969d5f8b6fdbf179d4e30`). Put it into this folder's `inputs/` or your
Downloads folder, or pass `--input original=<path>`.

## Run it

```
ports/torchmaster/run.sh        # Linux, macOS
ports\torchmaster\run.bat       # Windows
```

It fetches a Java 21 for itself if you have none, downloads and builds everything else under `.porter/`, ports the mod
and prints where the jar is. Mostly it is downloads and the Minecraft build; the port itself takes a few minutes. Later
runs take about a minute.

Measured on first runs from an empty folder (the 1.7.10 build environment is most of it):

| System | Time | Downloaded | Disk used by `.porter/` |
|---|---|---|---|
| Windows 10 (includes fetching Java 21) | 25 min | about 2.5 GB (network counter, may include other traffic) | 3.1 GB |
| Linux, Ubuntu in WSL (includes fetching Java 21) | 30 min | about 1.2 GB (network counter) | 3.1 GB |

Of that, about 6 min is the symbol graph, 1.5 min Forge, the rest Gradle fetching Minecraft 1.7.10 and its Java 8
toolchain; the port itself (generate to build) is about 1.5 min. A second run takes seconds.

## What was verified

- All 28 classes of the mod are ported, 0 counted edits, one hand-written wiring class (`overlay/`: the 1.7.10 `@Mod` entry
  point). 373 distinct Minecraft/Forge API symbols, all resolved by shared rules and adapters.
- 6 differential scenarios (`scenarios/`) pass with equal traces on a real 1.19.2 server (the original) and a real 1.7.10
  server (the port): registry, Mega Torch and Dread Lamp spawn blocking, Feral Flare Lantern in a void world and above a
  floor, light levels.
- Not played in a real game yet (status `scenarios` in the catalog).

## What differs from the 1.19.2 mod

- Village sieges are never blocked (1.7.10 Forge has no siege event).
- Mob spawners are never blocked: 1.7.10 Forge only checks natural spawning (the default `blockOnlyNaturalSpawns=true`
  behaviour; `false` has no extra effect).
- Blocks render with the 1.7.10 block renderer: generated textures on the block's box (the Mega Torch box comes from its
  1.19.2 shape); the Dread Lamp and lantern JSON models are not converted.
- The config file is `config/torchmaster.cfg` (1.7.10 format, same keys and comments as the 1.19.2 TOML).

The complete list is in `port-report.md` (path printed at the end of the run).

## Files here

- `torchmaster.port.yaml`: the port config (inputs, prepare steps, stages).
- `overlay/`: the 1.7.10 `@Mod` class. `project/`: files copied over the GTNH project template (mod id, licence, credits).
- `scenarios/`: the checks listed above. `run.sh`, `run.bat`: the one command.
