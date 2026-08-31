plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.keepagent.runtime.js"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                cppFlags("")
                abiFilters("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
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
        aidl = true
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
