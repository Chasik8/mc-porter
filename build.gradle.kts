// Root build: shared conventions for all Java modules of porter.
// Tooling runs on Java 21 (docs/decisions/0001-java-21-toolchain.md).

val containerProjects = setOf(":extract", ":generate", ":testkit")

allprojects {
    // Distinct coordinates for same-named projects (extract:forge-1.19.2 vs generate:forge-1.19.2),
    // otherwise Gradle treats them as one module and drops one from the CLI distribution.
    group = if (parent == null || parent == rootProject) "dev.porter" else "dev.porter.${parent!!.name}"
    version = "0.3.0"
}

subprojects {
    if (path in containerProjects) return@subprojects

    apply(plugin = "java-library")

    // Unique jar names: extract:forge-1.19.2 and generate:forge-1.19.2 must not collide in the CLI distribution.
    extensions.configure<BasePluginExtension> {
        archivesName.set("porter" + path.replace(':', '-'))
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("porter.repoRoot", rootProject.projectDir.absolutePath)
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }

    // Deterministic archives (rule 3 also applies to our own build outputs).
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
