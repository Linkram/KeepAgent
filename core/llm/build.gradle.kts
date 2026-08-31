// Pure Kotlin/JVM module: LLM provider abstraction + OpenAI-compatible client.
// No Android dependencies — usable from unit tests and the agent loop alike.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":addons-api"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
}
