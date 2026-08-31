// Tier-1 add-on (spec §5.1): OpenAI-compatible LLM provider.
// Registers the `llm.provider` capability; endpoint config comes from the
// user's model profile in settings (baseUrl / apiKey / model).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.keepagent.addons.provideropenai"
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
    api(project(":core:llm"))
    api(project(":core:settings"))
}
