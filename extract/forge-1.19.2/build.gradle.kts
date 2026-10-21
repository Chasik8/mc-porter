// extract:forge-1.19.2 — IR extractors for Forge 1.19.2 mod sources (Spoon, noclasspath).
dependencies {
    api(project(":core"))
    // Pinned here (not in the shared catalog) to keep the edit local to this module; same Spoon as :rewrite.
    implementation("fr.inria.gforge.spoon:spoon-core:11.5.0")
    implementation("tools.jackson.dataformat:jackson-dataformat-toml:3.2.3")
    implementation(libs.asm)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}
