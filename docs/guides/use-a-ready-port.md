# Use a ready port

You want a mod on an older Minecraft version (today: Forge 1.19.2 mods on Forge 1.7.10), and somebody already wrote the
port profile for it. You do not need to know how porting works. You need an internet connection, about 4 GB of disk
space, and patience for the first run (it downloads and builds Minecraft's development environment).

## 1. Get porter

Download the latest `porter-<version>.zip` from the [releases page](https://github.com/Chasik8/mc-porter/releases) and
unpack it anywhere (the path should not contain spaces on Windows). You get a folder with `bin/`, `rules/` and `ports/`.

You do **not** need to install Java: the launcher fetches a Java 21 for itself if your computer has none.

## 2. Find your mod

Open `ports/README.md`: the table lists every mod that has a port, its status and how far it was checked. Each mod has its
own folder, `ports/<mod>/`, with a `README.md` that tells you:

- which files you must download, with the link to the download page of each file (porter never ships a mod),
- the one command to run, and how long it takes,
- what was verified, and what behaves differently from the original.

## 3. Download the files it asks for

Put them into `ports/<mod>/inputs/` (or leave them in your Downloads folder: porter looks there too). If a file is missing
or is not the one the port was made for, porter says so in plain words, names the download page and the exact file, and
changes nothing else:

```
The file torchmaster-19.2.93.jar is missing (Torchmaster 19.2.93).
   Download it from: https://modrinth.com/mod/torchmaster/version/19.2.93
   Then put it into .../ports/torchmaster/inputs (or your Downloads folder) and run again,
   or give its location with: --input original=<path to torchmaster-19.2.93.jar>
```

## 4. Run the one command

Windows (double-click or run in a terminal):

```
ports\torchmaster\run.bat
```

Linux / macOS:

```
ports/torchmaster/run.sh
```

The first run does all the one-time work: the Java 21 for porter, the symbol graph of the Minecraft versions, Forge's
development files, the 1.7.10 build environment. Later runs reuse them (a second mod of the same version pair needs only
its own part). All of it lives in the `.porter` folder next to `bin/`; delete that folder and nothing is left on your
computer. If something is interrupted, run the same command again: it continues where it stopped.

Useful options (after the command): `--input name=path` (where a file is), `--home <dir>` (where `.porter` lives),
`--out <dir>` (where the finished jar is copied, default `output/`), `--restart` (port again from the first step).

## 5. Put the jar into your mods folder

At the end porter prints the path of the finished jar, for example `output/torchmaster-0.1.0.jar`. Copy it into the
`mods` folder of a **Minecraft 1.7.10 with Forge 10.13.4.1614**. The mod's README lists what the port needs besides that
(most need nothing).

`work/port-report.md` (the path is printed too) lists everything that could not be ported, one line each. Read it before
you play: it is the honest list of what is missing.

## If something goes wrong

- The message says what to do for a wrong or missing file, a failed download (run again) or a failed build.
- The logs are in `.porter/logs/` and in the port's workspace (`.porter/runs/<mod>/project/build/porter-port/logs/`).
- Ask in an issue and attach `port-report.md` and the log named in the message. Do not attach anything copied from a mod
  whose licence does not allow it.
