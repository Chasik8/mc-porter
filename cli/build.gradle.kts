// cli: the single `porter` binary. Subcommands are discovered via ServiceLoader
// (dev.porter.core.cli.PorterSubcommand); missing ones are stubs.
plugins {
    application
}

dependencies {
    implementation(project(":core"))
    runtimeOnly(libs.slf4j.nop)
    // Modules contribute subcommands at runtime.
    runtimeOnly(project(":graph"))
    runtimeOnly(project(":extract:forge-1.19.2"))
    runtimeOnly(project(":generate:forge-1.7.10"))
    runtimeOnly(project(":generate:forge-1.19.2"))
    runtimeOnly(project(":rewrite"))
    runtimeOnly(project(":loop"))
    runtimeOnly(project(":assets"))
    runtimeOnly(project(":testkit:runner"))
    runtimeOnly(project(":port"))
}

application {
    applicationName = "porter"
    mainClass.set("dev.porter.cli.PorterMain")
    // Spoon needs a deep stack on large decompiled classes; heap: override with PORTER_OPTS=-Xmx...
    // Free-ratio flags: the heap shrinks after the full GC porter runs before starting a child JVM (Proc.releaseHeap),
    // so `porter port` does not hold its peak heap while rewrite/decompile/Gradle run (one run stays near 2 GB, not 3).
    applicationDefaultJvmArgs = listOf("-Xss16m", "-Xmx2g", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
            "-Djdk.attach.allowAttachSelf=true")
}

// porter-<version>.zip / install dir: launchers (bin/porter, bin/porter.bat), jars, plus the public rule packs,
// contract schemas and examples (port-config templates), the catalog and the docs a user needs.
distributions {
    main {
        contents {
            from(rootProject.layout.projectDirectory) {
                include("README.md", "CHANGELOG.md", "CONTRIBUTING.md", "LICENSE", "NOTICE")
                include("rules/**", "contracts/*.schema.json", "contracts/examples/**", "ports/**", "docs/release/**")
                include("scripts/run-port.bat", "docs/guides/**", "docs/ARCHITECTURE.md")
                exclude("**/*.sh")
            }
            // the launchers must stay executable in the zip, whatever system built it
            from(rootProject.layout.projectDirectory) {
                include("scripts/run-port.sh", "ports/*/run.sh")
                filePermissions { unix("rwxr-xr-x") }
            }
        }
    }
}

tasks.test {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts")).withPathSensitivity(PathSensitivity.RELATIVE)
}

// `gradlew build` also produces cli/build/install/porter (used by scripts/check.sh and CI).
tasks.named("build") { dependsOn(tasks.named("installDist")) }
