// Pure Kotlin/JVM module: the agent loop (spec §4.1 `core-agent`).
// No Android dependencies — the turn loop, approval gate, and run state are
// unit-testable without a device.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":addons-api"))
    api(project(":core:events"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
