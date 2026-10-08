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

rootProject.name = "gaiadesk-embed-android"
// The library (net.gaiadesk:embed-android) and its two examples.
include(":embed", ":examples:compose", ":examples:views")
