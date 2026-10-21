rootProject.name = "porter"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // FabricMC Matcher core (graph module only; not on Maven Central). Restricted to that one artifact.
        maven("https://maven.fabricmc.net/") {
            content { includeModule("net.fabricmc", "matcher") }
        }
    }
}

// Gradle projects mirror the directory layout (docs/decisions/0003-gradle-layout-and-cli-spi.md).
// Container directories (extract/, generate/, testkit/) are plain parents without code.
// testkit/mod-1.19.2 and testkit/mod-1.7.10 are NOT part of this build: they are separate
// ForgeGradle / RetroFuturaGradle builds with their own JDKs (see docs/decisions/0003).
include(
    "core",
    "cli",
    "graph",
    "extract:forge-1.19.2",
    "generate:common",
    "generate:forge-1.7.10",
    "generate:forge-1.19.2",
    "rewrite",
    "rewrite:shim",
    "loop",
    "assets",
    "testkit:runner",
    "port",
)
