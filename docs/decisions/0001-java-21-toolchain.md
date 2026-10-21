# 0001: Java 21 via a portable Temurin JDK 21

- Status: accepted

## Context
The porter tooling requires Java 21. The machine has portable JDKs 8, 17 and 25, none
on PATH, and nothing may be installed system-wide.

## Decision
Download the portable Temurin JDK 21 zip (21.0.12.1+1, sha256
`f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e`, verified against the
Adoptium API) into its own directory `<tools>\jdk21\jdk-21.0.12.1+1` and run
Gradle with `JAVA_HOME` pointing at it. CI uses `actions/setup-java` with Temurin 21, so local
and CI JDKs match.

The build declares `toolchain.languageVersion = 21` and `options.release = 21`, so running Gradle
on another JDK fails fast instead of silently compiling for another level. Toolchain
auto-provisioning is deliberately not configured (no hidden downloads).

Gradle: wrapper pinned to **9.8.1** (current at the time) with `distributionSha256Sum`.

## How to build

Git Bash:

    JAVA_HOME=/path/to/jdk-21.0.12.1+1 ./gradlew build
    JAVA_HOME=/path/to/jdk-21.0.12.1+1 scripts/check.sh   # build + example validation

PowerShell:

    $env:JAVA_HOME = '<tools>\jdk21\jdk-21.0.12.1+1'; .\gradlew.bat build

The CLI distribution is in `cli/build/install/porter` (`bin/porter`, `bin/porter.bat`).

## Rejected
Gradle on JDK 25 with `--release 21`: compiles fine, but tests would run on 25 and local runs
would differ from CI.

## Not covered
Building Minecraft mods needs JDK 8 (RFG / Forge 1.7.10, with Jabel) and JDK 17 (ForgeGradle 5 /
1.19.2). Those are separate Gradle builds with their own JDK (see 0003); the existing portable
JDKs 8 and 17 are for them.
