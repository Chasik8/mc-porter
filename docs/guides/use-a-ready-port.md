# Use a ready port

You want a mod on an older Minecraft version (today: Forge 1.19.2 mods on Forge 1.7.10), and somebody already wrote
the port profile for it. You do not need to know how porting works. This page is the long version of the
[README quickstart](../../README.md#get-a-ported-mod).

You need an internet connection, about 4 GB of free disk space, 15 to 30 minutes for the first run, and a Minecraft
1.7.10 with Forge 10.13.4.1614 to play the result. You do **not** need to install Java.

## 1. Get porter

Download `porter-<version>.zip` from the [releases page](https://github.com/Chasik8/mc-porter/releases/latest) and
unpack it. You get a folder `porter-<version>` with `bin/`, `ports/`, `rules/` and a few others.

- **Windows:** extract it to `C:\` (right-click, **Extract All...**), so you get `C:\porter-0.3.0\`. Keep the path
  short and **without spaces**: the Minecraft build tools porter runs break on long or spaced paths, and the
  Desktop, or any folder inside your Windows user folder, often has both.
- **Linux / macOS:** `unzip porter-0.3.0.zip -d ~` gives `~/porter-0.3.0`. You need `curl` or `wget`, and `tar`.

If your computer has no Java 21, porter downloads one (about 200 MB, checksum-verified) into its own folder the first
time. It does not touch any Java you have installed.

## 2. Find your mod

Open [`ports/README.md`](../../ports/README.md): the table lists every port, its status and how far it was checked.
Each port has a page (`ports/<mod>/README.md`) that tells you:

- which files you must download, with the link to the official download page of each (porter never ships a mod),
- the command to run and how long it takes,
- what was verified, and what behaves differently from the original,
- what else to put into your `mods` folder (for an addon: the mod it extends).

## 3. Download the files it asks for

Some ports need nothing (Torchmaster fetches its open-licence source by itself). Others need the original mod's jars,
which you download from the official page linked in the port's README:

- On **CurseForge** file pages, click **Download** (not **Install**, which wants the CurseForge app). The download
  starts after a few seconds.
- Leave the files in your **Downloads** folder, or put them into `ports/<mod>/inputs/`; porter looks in both.
- Do not rename them. If your browser saved a second copy as `name (1).jar`, delete the duplicate or rename it back.

Porter checks every file against a checksum. If a file is missing or is not exactly the one the port was made for,
porter says so in plain words, names the download page and the exact file, and changes nothing:

```
The file torchmaster-19.2.93.jar is missing (Torchmaster 19.2.93).
   Download it from: https://modrinth.com/mod/torchmaster/version/19.2.93
   Then put it into .../ports/torchmaster/inputs (or your Downloads folder) and run again,
   or give its location with: --input original=<path to torchmaster-19.2.93.jar>
```

## 4. Run the one command

**Windows:** open a Command Prompt (<kbd>Win</kbd>+<kbd>R</kbd>, `cmd`, Enter) and type the path of the port's
`run.bat`, for example:

```
C:\porter-0.3.0\ports\torchmaster\run.bat
```

You can also double-click `run.bat`, but then the window closes as soon as porter finishes or fails, before you can
read what it said.

**Linux / macOS:**

```
~/porter-0.3.0/ports/torchmaster/run.sh
```

The first run does all the one-time work: Java for porter, the symbol graph of the Minecraft versions, Forge's
development files, the 1.7.10 build environment. Later runs reuse it (a second mod needs only its own part). All of it
lives in the `.porter` folder inside the porter folder; delete that folder and nothing is left on your computer. If a
run is interrupted, run the same command again: it continues where it stopped.

Options (add them after the command): `--input <name>=<path>` (where a file is), `--home <dir>` (where `.porter`
lives), `--out <dir>` (where the finished jar is copied, default `output/` in the porter folder), `--restart` (port
again from the first step), `--check-inputs` (only check the downloaded files).

## 5. Put the jar into your mods folder

At the end porter prints `Done. Your ported mod:` and the path of the jar, for example
`C:\porter-0.3.0\output\torchmaster-0.1.0.jar`. Copy it into the `mods` folder of a **Minecraft 1.7.10 with Forge
10.13.4.1614** (official launcher: `%APPDATA%\.minecraft\mods` on Windows, `~/.minecraft/mods` on Linux), together
with anything else the port's README lists.

Porter also prints the path of `port-report.md`, the honest list of everything that could not be ported, one line
each. Read it before you play.

The jar is for your own use: do not upload or share it unless the port's README says the mod's licence allows it. The
code inside belongs to the original mod's authors, which is why this project publishes recipes instead of jars (see the [README](../../README.md#why-we-do-not-publish-the-ported-jars)).

## If something goes wrong

- The message says what to do for a wrong or missing file, a failed download (run again) or a failed build.
- Logs: `.porter/logs/` and the `logs` folder next to `port-report.md` (both paths are printed).
- Ask in an [issue](https://github.com/Chasik8/mc-porter/issues) and attach `port-report.md` and the log named in the
  message. Do not attach anything copied from the original mod.
