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
include(":runtime-js")
