// core: IR models, contract loading/validation, CLI SPI and run reports.
dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.yaml)
    api(libs.picocli)
    implementation(libs.json.schema.validator)
}

// contracts/*.schema.json are the single source of truth; they are bundled into the jar.
val contractsDir = rootProject.layout.projectDirectory.dir("contracts")
tasks.processResources {
    from(contractsDir) {
        include("*.schema.json")
        into("porter/contracts")
    }
}

tasks.test {
    // ContractExamplesTest walks contracts/examples.
    inputs.dir(contractsDir).withPathSensitivity(PathSensitivity.RELATIVE)
}
