// Tier-1 add-on (spec §5.1): built-in file tools — read, write, edit, glob,
// grep. In-process Kotlin, no sandbox; file policy enforced by core-fs.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.keepagent.addons.toolscore"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":addons-api"))
    api(project(":core:host"))
    api(project(":core:fs"))
    api(libs.kotlinx.serialization.json)
}
