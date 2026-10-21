// generate:forge-1.19.2 — IR -> Forge 1.19.2 (43.x, official mappings) sources for round-trip.
dependencies {
    api(project(":generate:common"))
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}
