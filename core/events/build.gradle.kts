// Pure Kotlin/JVM module: the event stream (SharedFlow + JSONL log).
// No Android dependencies — consumable by the app and by pure-JVM core
// modules (core:agent).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
