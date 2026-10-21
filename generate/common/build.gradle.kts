// generate:common — `porter generate` command, generator SPI and shared helpers (IR index, names,
// deterministic output tree). Version generators (generate:forge-*) plug in via ServiceLoader.
dependencies {
    api(project(":core"))
    // JavaPoet (Apache-2.0): Java source emission for all version generators. Declared here, not in the
    // version catalog, to keep root-file edits minimal.
    api("com.squareup:javapoet:1.13.0")
}
