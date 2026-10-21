// graph: symbol graph of Minecraft across versions (jars + mappings -> matched symbols -> chain queries).
// See graph/README.md.
dependencies {
    api(project(":core"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    implementation(libs.mapping.io)
    // FabricMC Matcher core used headless (classpath, not module path). Its runtime deps are the GUI
    // decompilers (cfr/vineflower/procyon/jadx/javafx) which the classifiers never touch -> not transitive.
    implementation(libs.fabric.matcher) { isTransitive = false }
    implementation(libs.asm.util)
    implementation("org.slf4j:slf4j-api:2.0.19")
}
