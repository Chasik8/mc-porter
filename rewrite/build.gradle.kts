// rewrite: Spoon-based rule engine (`porter rewrite`). Owner: rewriter. See rewrite/README.md.
dependencies {
    api(project(":core"))
    // Spoon pinned here (not in the shared catalog) to keep the edit local to this module.
    implementation("fr.inria.gforge.spoon:spoon-core:11.5.0")
}

// The 1.7.10 shim sources are shipped inside the rewriter jar so `porter rewrite` can copy (and relocate) them
// into the output tree of a ported mod.
val shimSources = project(":rewrite:shim").layout.projectDirectory.dir("src")
tasks.processResources {
    from(shimSources) {
        include("main/java/**/*.java", "world/java/**/*.java")
        into("porter/shim")
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    inputs.dir(rootProject.layout.projectDirectory.dir("rules")).withPathSensitivity(PathSensitivity.RELATIVE)
}
