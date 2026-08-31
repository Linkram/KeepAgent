// Pure Kotlin/JVM module: the Addon API v1 contract (spec §5).
// No Android dependencies — this is the stable surface add-ons build against.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
