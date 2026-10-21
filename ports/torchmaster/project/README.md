# Torchmaster — unofficial 1.7.10 port

**This is an unofficial port.** It is not made, endorsed or supported by the author of Torchmaster.
Please do not report problems with this port to the Torchmaster issue tracker.

- Original mod: [Torchmaster](https://github.com/Xalcon/TorchMaster) by **xalcon**, branch `mc/1.19.2`
  (version 19.2.93, commit f21e231d790d04beb86aa35392826dccededf614), licensed under the MIT License.
- This port: Minecraft 1.7.10, Forge 10.13.4.1614. Code and assets are derived from the original and stay under
  the original MIT License (see `LICENSE`, copyright (c) 2022 Xalcon).
- Made with *porter*, a Forge 1.19.2 -> 1.7.10 porting pipeline (rewrite rules + 1.19.2-API adapters + generated
  registration data), as a demonstration.

## What works
- Mega Torch: blocks natural spawns of hostile mobs in a 64-block cube (configurable).
- Dread Lamp: blocks natural spawns of passive mobs in a 64-block cube (configurable).
- Feral Flare Lantern: places invisible light sources in dark spots around it; breaking it removes them.
- Frozen Pearl: removes invisible lights around the player.
- `/torchmaster torchdump|entitydump`, config file `config/torchmaster.cfg` (same keys as the 1.19.2 TOML).

## Known differences from the 1.19.2 mod
See the port report (`REPORT.md` of the porter demo run). In short: village sieges cannot be blocked (1.7.10 has no
siege event); mob spawners are never blocked (1.7.10 Forge only checks natural spawning; this matches the default
`blockOnlyNaturalSpawns=true`, setting it to false has no extra effect); block models are drawn
with the 1.7.10 block renderer (simple boxes and textures, no JSON models).
