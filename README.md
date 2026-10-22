# porter

Porter takes a Minecraft Forge mod made for one game version and rebuilds it for another; today that means mods for
**Forge 1.19.2** rebuilt for **Forge 1.7.10**. Each mod that has been ported comes with a ready-made recipe (a "port
profile"). With one command, porter reads your own copy of the original mod, converts it and builds a jar you can
drop into your `mods` folder.

> Status: **v0.2.0, early.** Two ports are available. Ports are unofficial: the original authors do not make or
> support them. See [Status and limits](#status-and-limits).

## Get a ported mod

You do not need to know Java or programming. You need:

- an internet connection and about **4 GB of free disk space**;
- 15 to 30 minutes for the first run (mostly downloads; later runs take a few minutes);
- a **Minecraft 1.7.10 with Forge 10.13.4.1614** to play the result (any launcher works: the official launcher with
  the [Forge installer](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.7.10.html), Prism, MultiMC...).

You do **not** need to install Java: porter downloads its own copy the first time if your computer has none.

### Windows

1. Download `porter-0.2.0.zip` from the [releases page](https://github.com/Chasik8/mc-porter/releases/latest).
2. Right-click it, choose **Extract All...**, and extract it to `C:\`. You now have `C:\porter-0.2.0\`.
   Use a short folder **without spaces** in its name (not your Desktop, not a folder like `C:\My Mods`): long or
   spaced paths break parts of the Minecraft build tools.
3. Open the [list of ports](ports/README.md), click your mod and read its page. It says which files to download
   (if any) and where from. Save them in your **Downloads** folder: porter looks there.
4. Open a Command Prompt (press <kbd>Win</kbd>+<kbd>R</kbd>, type `cmd`, press Enter) and run the port's
   `run.bat`. For Torchmaster:
   ```
   C:\porter-0.2.0\ports\torchmaster\run.bat
   ```
   Use a Command Prompt rather than double-clicking the file: the window then stays open and you can read the result.
5. Wait. At the end porter prints `Done. Your ported mod:` and the path of the jar, for example
   `C:\porter-0.2.0\output\torchmaster-0.1.0.jar`.
6. Copy that jar into the `mods` folder of your Minecraft 1.7.10 Forge instance (official launcher:
   `%APPDATA%\.minecraft\mods`), together with anything else the port's page lists (for example the base mod of an
   addon). Start the game.

### Linux (macOS should work too, but is untested)

```sh
curl -fLO https://github.com/Chasik8/mc-porter/releases/download/v0.2.0/porter-0.2.0.zip
unzip porter-0.2.0.zip -d ~ && cd ~/porter-0.2.0
# save the files the port's page asks for (if any) in ~/Downloads, then:
ports/torchmaster/run.sh
cp output/*.jar ~/.minecraft/mods/        # or the mods folder of your launcher's 1.7.10 instance
```

You need `curl` (or `wget`), `unzip` and `tar`. Everything porter downloads or builds goes into the `.porter` folder
inside `porter-0.2.0`; delete that folder and nothing is left behind.

### If something goes wrong

- **A file is missing or wrong:** porter names the file, links its download page and changes nothing else. The name
  must match exactly: if your browser saved `name (1).jar`, rename it, or give its path with `--input <name>=<path>`
  (the message shows the right `<name>`).
- **Anything else:** run the same command again; it continues where it stopped (failed downloads are retried).
- **Still stuck:** open an [issue](https://github.com/Chasik8/mc-porter/issues) and attach `port-report.md` and the
  log named in the error message. Never attach files of the original mod.

More detail: [Use a ready port](docs/guides/use-a-ready-port.md).

## Available ports

The list is in [ports/README.md](ports/README.md). Today: **Torchmaster** (a complete mod) and one addon that brings
features of a newer version of a tech mod back to its 1.7.10 version. Each port's page says what was checked and what
differs from the original.

## Why we do not publish the ported jars

The code inside a ported mod still belongs to the mod's original authors, and handing out copies of it would break
their copyright. So this project never publishes finished jars. Each port ships only a **recipe**: you download the
original mod yourself from its official page, and porter builds the port on your own computer, for your own use.
Please do not upload or share the jars you build, unless the port's page says the mod's licence allows it.

Mods with an open licence (Torchmaster, for example, is MIT) could legally be redistributed, but we keep one simple
rule for every port: recipes only, no jars.

## Port your own mod

Porter does the mechanical part of a port: it copies the mod's code, rewrites its Minecraft and Forge calls with about
900 tested rules, compiles the result and lists, one by one, the API differences it could not resolve. You resolve
each of them once, run again, and check the port against the original with scenario tests.

In short, to port a mod and send it to us:

1. Copy `ports/_template` to `ports/<yourmod>` and fill in its `*.port.yaml`: the original mod's file name, version
   and download page. `porter run ports/<yourmod> --check-inputs` prints the sha256 to put next to them.
2. Run `porter run ports/<yourmod>`. Open the `port-report.md` it writes: it lists what could not be converted.
3. For each item, add a rule or an adapter (the guide shows how) and run again, until the build succeeds.
4. Add a scenario test that compares your port with the original, or at least start the game with it.
5. Fork the repository on GitHub, add your `ports/<yourmod>` folder and one entry in `ports/catalog.yaml`, and open
   a pull request. CI builds and tests your port automatically; a maintainer reviews it.

- [Port your own mod](docs/guides/port-your-own-mod.md): step by step, from a template.
- [How porter works](docs/ARCHITECTURE.md): the pipeline, its parts, the design decisions.
- [Share your port](docs/guides/share-your-port.md) and [CONTRIBUTING.md](CONTRIBUTING.md): add your port to the list,
  contribute rules.

## Status and limits

- One version pair: Forge 1.19.2 → Forge 1.7.10. Other pairs need new extractors and generators.
- A port is as good as its checks. Every port page says what was verified (scenario tests that compare the port with
  the original on real servers), and every run writes a `port-report.md` listing everything that was **not** ported.
  Read it before you play.
- Visuals need a human: models more complex than cubes are drawn as textured boxes, and GUI looks are checked only
  where a port has GUI scenarios.
- Tested on Windows 10 and Linux (Ubuntu). macOS should work but is untested.
- Ports are not supported by the original mods' authors: report problems here, not to them.

## Licence

Porter is licensed under the Apache License 2.0 (`LICENSE`, `NOTICE`), copyright the mc-porter contributors. That
covers porter itself, not the mods it ports: a jar you build stays under its original mod's licence.

Porter ships no mod code and no Mojang or MCP mappings; it builds what it needs from official sources on your
machine. Minecraft is a trademark of Mojang Studios; porter is not affiliated with Mojang or Microsoft.
