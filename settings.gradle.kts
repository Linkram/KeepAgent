pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "keepagent"

include(":app")
include(":addons-api")
include(":core:events")
include(":core:settings")
include(":core:storage")
include(":core:host")
include(":core:llm")
include(":core:fs")
include(":core:workspace")
include(":core:agent")
include(":addons:provider-openai")
include(":addons:tools-core")
include(":runtime-js")
