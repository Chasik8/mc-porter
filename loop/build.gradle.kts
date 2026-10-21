// loop: compile / classify / fix loop, javac error classifier, candidate log and rule drafts.
dependencies {
    api(project(":core"))
    implementation(project(":rewrite")) // Spoon rule engine (RewriterRuleEngine)
    implementation(project(":graph"))   // SymbolGraph query API (task context)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}
