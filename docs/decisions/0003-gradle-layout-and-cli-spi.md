# 0003: Gradle layout and the subcommand SPI

- Status: accepted

## Gradle projects
Projects are laid out as follows:

| Gradle path | Directory | Owner |
|---|---|---|
| `:core` | core/ | core |
| `:cli` | cli/ | core |
| `:graph` | graph/ | graph |
| `:extract:forge-1.19.2` | extract/forge-1.19.2/ | extract |
| `:generate:forge-1.7.10`, `:generate:forge-1.19.2` | generate/... | generate |
| `:rewrite` | rewrite/ | rewriter |
| `:loop` | loop/ | loop |
| `:assets` | assets/ | assets |
| `:testkit:runner` | testkit/runner/ | testkit |

`extract`, `generate` and `testkit` are plain container projects. Conventions (Java 21, UTF-8,
JUnit 6, reproducible archives, `-Xlint:all`) come from the root `build.gradle.kts`; versions live
in `gradle/libs.versions.toml`. Every module depends on `:core` (`api`). Jar names are
`porter-<path>` and group ids include the parent (`dev.porter.extract`), because
`extract:forge-1.19.2` and `generate:forge-1.19.2` would otherwise collide as one module.
Base Java package per module: `dev.porter.<module>` (`dev.porter.extract.forge1192`, ...).

New per-version modules (e.g. `extract:forge-1.12.2`) are added to `settings.gradle.kts` and as
`runtimeOnly` to `cli/build.gradle.kts` by a maintainer on request.

**Minecraft test mods** (`testkit/mod-1.19.2`, `testkit/mod-1.7.10`) and generated mod projects
are *not* part of this build: they need ForgeGradle/RFG and JDK 17/8. They are standalone Gradle
builds with their own wrapper and settings file, driven by `testkit:runner` / `loop` as external
processes.

## CLI SPI
One binary, `porter` (`:cli`, main class `dev.porter.cli.PorterMain`). Modules contribute
subcommands through `java.util.ServiceLoader`:

1. implement `dev.porter.core.cli.PorterSubcommand` on a picocli `@Command` class implementing
   `Callable<Integer>`; `path()` returns e.g. `"graph build"`;
2. list it in `src/main/resources/META-INF/services/dev.porter.core.cli.PorterSubcommand`;
3. write a report with `dev.porter.core.cli.RunReport` and return an `ExitCodes` value.

Every path in `KnownCommands.PATHS` without a provider is a stub: it prints "not implemented",
writes `reports/runs/<path>.json` with status `not_implemented` and exits 3. Two providers for
one path, or an unknown path, abort CLI start-up. Do not use picocli `mixinStandardHelpOptions`
in subcommands: it claims `--version`, which `extract`/`generate`/`test roundtrip` use for the
Minecraft version; declare `@Option(names = {"-h", "--help"}, usageHelp = true)` instead.

Exit codes: 0 ok, 1 failed (negative result), 2 usage, 3 not implemented, 4 internal error.
Reports: `<reports-dir>/runs/<command-with-dashes>.json` (`--reports-dir`, `PORTER_REPORTS_DIR`,
default `reports/` relative to the working directory); `reports/runs/` is git-ignored. The report
shape (`command, status, exitCode, startedAt, finishedAt, args, details`) is not a frozen
contract; modules put their metrics under `details`.

This keeps ownership clean: each contributor edits only their module, and the CLI wiring does not change
when a command gets implemented.
