// rewrite:shim — the thin 1.7.10 shim that rewritten mods compile against:
// coordinates (BlockPos), directions (Direction) and small world helpers. Targets Java 8 because it ends up inside
// 1.7.10 mods.
//
// Source sets:
//   main     — pure Java, no dependencies (BlockPos, Direction).
//   world    — static helpers over the 1.7.10 World/TileEntity API.
//   adapters — the generic 1.19.2-API adapter pack (dev.porter.shim.mc, .mc.client; docs/decisions/0008): what the
//              closure port of a mod still calls of the 1.19.2 API, implemented on 1.7.10. Shipped as sources by
//              the port module (install stage of `porter port`, relocated to the mod's shim package).
// world and adapters compile only when a deobfuscated (MCP) 1.7.10 Minecraft+Forge jar is available:
// -Pporter.mc1710Jar=<jar> or env PORTER_MC1710_JAR (e.g. RFG's build/rfg/recompiled_minecraft-1.7.10.jar).
// Without it they are skipped (CI).
plugins {
    `java-library`
}

val mc1710Jar: String? = (findProperty("porter.mc1710Jar") as String?) ?: providers.environmentVariable("PORTER_MC1710_JAR").orNull
val hasMc1710: Boolean = mc1710Jar != null && file(mc1710Jar).isFile

val world: SourceSet = sourceSets.create("world") {
    compileClasspath += sourceSets.main.get().output
}
val adapters: SourceSet = sourceSets.create("adapters") {
    compileClasspath += sourceSets.main.get().output
}

dependencies {
    if (hasMc1710) {
        "worldCompileOnly"(files(mc1710Jar))
        "adaptersCompileOnly"(files(mc1710Jar))
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Overrides the root convention (release 21): the shim lives inside Java 8 mods.
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}

tasks.named<JavaCompile>("compileWorldJava") {
    // Needs a 1.7.10 MCP jar (-Pporter.mc1710Jar / PORTER_MC1710_JAR); skipped otherwise.
    enabled = hasMc1710
}
tasks.named<JavaCompile>("compileAdaptersJava") {
    enabled = hasMc1710
}

tasks.jar {
    from(world.output)
}

// Tests run on the tooling JDK and only touch the Java 8 main source set.
tasks.named<JavaCompile>("compileTestJava") {
    options.release.set(21)
    // the dependency-free part of the networking bridge (adapters/mc/net) is unit-tested without Minecraft
    source(fileTree("src/adapters/java") { include("dev/porter/shim/mc/net/*.java") })
}
