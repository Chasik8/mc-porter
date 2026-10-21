// generate:forge-1.7.10 — IR -> Forge 1.7.10 (10.13.4.1614) sources.
dependencies {
    api(project(":generate:common"))
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}
