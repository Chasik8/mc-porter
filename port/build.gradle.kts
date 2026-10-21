// port: `porter port` (end-to-end, config-driven, resumable) and its building blocks: dependency closure,
// hub stubs, source passes, cast fixes, distinct-symbol aggregation, coverage, reobf check. Owner: release.
dependencies {
    api(project(":core"))
    implementation(project(":rewrite")) // rules, type facts, graph rules (coverage)
    implementation(libs.asm)
}

// porter's generic adapter pack (rewrite/shim/src/adapters) is shipped inside this jar; the install stage copies
// it, relocated to the port's shim package, into the target project.
val adapterSources = project(":rewrite:shim").layout.projectDirectory.dir("src/adapters/java")
val adapterIndex = tasks.register("adapterIndex") {
    val root = adapterSources.asFile
    val outFile = layout.buildDirectory.file("generated/adapter-index/porter/adapters/index.txt").get().asFile
    inputs.dir(root).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outFile.parentFile.parentFile.parentFile)
    doLast {
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        outFile.parentFile.mkdirs()
        outFile.writeText(files.joinToString("\n", postfix = "\n"))
    }
}
tasks.processResources {
    from(adapterSources) {
        include("**/*.java")
        into("porter/adapters")
    }
    from(adapterIndex)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}
