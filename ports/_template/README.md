# <Mod name> <version> on Minecraft 1.7.10

Copy of `ports/_template`: replace this text following `docs/guides/port-your-own-mod.md` and
`docs/guides/share-your-port.md` (`ports/torchmaster/README.md` is a complete example). A port README has:

- what the mod is, its original author and licence, and that the port is unofficial and not supported by the author;
- **why there is no jar to download**, for example: "We don't publish the finished jars of ported mods: the code
  inside belongs to the original authors, and redistributing it would break their copyright. Instead each port ships
  a recipe; you download the original mod yourself from its official page, and porter builds the port on your
  computer.";
- which files to download (links to the official download pages, never to the files), and their sha256;
- the one command (`run.bat` / `run.sh`), the expected time, and what else goes into the `mods` folder;
- what was verified, and what differs from the original.
