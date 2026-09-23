plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "io.keepagent.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.keepagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.0-m1.4k"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

// javac on ART has no desktop JDK module image. Package the compile SDK's API
// stubs as its boot class path; every Android build already has this file.
val prepareJavaPlatform by tasks.registering(Copy::class) {
    from(android.sdkDirectory.resolve("platforms/android-${android.compileSdk}")) {
        include("android.jar", "core-for-system-modules.jar")
    }
    into(layout.buildDirectory.dir("generated/java-platform/java-platform"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/java-platform"))
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
        it.name.contains("Lint") || it.name.startsWith("lint")
}.configureEach {
    dependsOn(prepareJavaPlatform)
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("pytest==9.1.1")
            install("requests==2.34.2")
        }
    }
}

dependencies {
    implementation(project(":addons-api"))
    implementation(project(":core:events"))
    implementation(project(":core:settings"))
    implementation(project(":core:storage"))
    implementation(project(":core:host"))
    implementation(project(":core:llm"))
    implementation(project(":core:agent"))
    implementation(project(":core:fs"))
    implementation(project(":core:workspace"))
    implementation(project(":runtime-js"))
    implementation(project(":addons:tools-core"))
    implementation(project(":addons:provider-openai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Overrides Compose 1.7's older native path library with the 16 KiB-page
    // compatible stable AndroidX release.
    implementation(libs.androidx.graphics.path)
    implementation(libs.nb.javac.android)
    implementation(libs.r8)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Local git for the workspace panel (M1.4): no remotes, repo confined to
    // the workspace root. +3-4 MB to the APK; slf4j-nop silences JGit's logging.
    implementation(libs.jgit)
    runtimeOnly(libs.slf4j.nop)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(kotlin("test"))
}
