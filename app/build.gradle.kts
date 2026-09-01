plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.keepagent.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.keepagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0-m1.4"
        vectorDrawables { useSupportLibrary = true }
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Local git for the workspace panel (M1.4): no remotes, repo confined to
    // the workspace root. +3-4 MB to the APK; slf4j-nop silences JGit's logging.
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
