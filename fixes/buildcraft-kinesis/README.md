# BuildCraft 7.1.23 kinesis fix (not a port)

A small bug-fix mod for the **original BuildCraft 7.1.23** on **Minecraft 1.7.10** (Forge 10.13.4.1614).
It is not a port and has nothing to do with porter's porting pipeline; it just lives in this repository.

Unofficial. BuildCraft belongs to its authors; this fix is not made, endorsed or supported by them, so please do not
report problems with it to the BuildCraft team.

## The bug

Kinesis pipes deliver power to BuildCraft machines only every other tick. A quarry that should get 1000 RF/t gets
about 500 RF/t on average, and the power that is not taken backs up through the kinesis line into the engines, which
sit with full buffers and stall.

Why: a BuildCraft machine accepts at most a fixed amount of RF per tick (1000 RF/t for the quarry) and remembers how
much it has received in the current tick. `TileBuildCraft.receiveEnergy` subtracts that amount also when it is only
asked "how much would you take?" (`simulate = true`). In one update a kinesis pipe first delivers power to the machine
and then asks it, with `simulate = true`, how much to request for the next tick. The machine has just used up its
limit, answers 0, and the pipe requests nothing for the next tick. So power is requested, and delivered, every second
tick.

## The fix

When `simulate` is true, the amount already received this tick is ignored. Real (non-simulated) receives keep the
per-tick limit, so machines still never take more than their normal maximum per tick.

The mod is a tiny coremod: at load time it inserts five bytecode instructions into
`buildcraft.core.lib.block.TileBuildCraft.receiveEnergy(ForgeDirection, int, boolean)`. If BuildCraft is not
installed, nothing happens. If the method does not look exactly like the one in BuildCraft 7.1.23 (another version,
or already changed by something else), the class is left untouched and a warning is written to the log.
On success the log has the line `Patched TileBuildCraft.receiveEnergy`.

Measured on a Forge 1.7.10 dedicated server (creative engine at 1280 RF/t → emerald kinesis pipe → two gold kinesis
pipes → quarry, 400 ticks, `test/run-bench.sh`):

| | delivered into the quarry | ticks with 0 RF |
|---|---|---|
| BuildCraft 7.1.23 | 410 RF/t on average (1000, 0, 1000, 0, ...) | 200 of 400 |
| with this fix | 1000 RF/t every tick | 0 of 400 |

## Install

1. Download `bckinesisfix-1.0.0.jar` from the
   [release page](https://github.com/Chasik8/mc-porter/releases/tag/bc-kinesis-fix-1.0.0).
2. Put it in the `mods` folder next to `buildcraft-7.1.23.jar`: your game's `mods` folder for single player, the
   server's `mods` folder for a server (the fix acts where the world is simulated).
3. Start the game. To remove it, delete the jar; worlds are not changed by it.

## Build it yourself

You need a JDK 8 and an installed Forge 1.7.10-10.13.4.1614 server (its `libraries/` folder provides the compile
classpath). No Gradle, no internet access:

```
FORGE_SERVER=<forge server dir> JAVA_HOME=<jdk8> ./build.sh     # -> build/bckinesisfix-1.0.0.jar
FORGE_SERVER=<forge server dir> JAVA_HOME=<jdk8> test/run-bench.sh <your buildcraft-7.1.23.jar>
```

`test/run-bench.sh` starts two dedicated servers (without and with the fix), builds the engine → kinesis → quarry line
with a small test-only mod (`test/src`, not part of the fix jar) and prints the delivered RF per tick.

## What is in the jar

Only our own code (three small classes: the loading plugin, the transformer, the mod-list entry), `mcmod.info` and
the licence. No BuildCraft code or classes are in this jar or in this repository; the fix edits BuildCraft's class
in memory while the game loads.

Licence: Apache License 2.0 (the repository's `LICENSE`), for this fix only. BuildCraft keeps its own licence.
