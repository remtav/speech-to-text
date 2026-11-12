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
        // sherpa-onnx is not published to a Maven repository: its AAR is fetched
        // from the GitHub release by app/build.gradle.kts (task fetchNativeDeps).
        flatDir { dirs("app/libs") }
    }
}
rootProject.name = "dictee"
include(":app")
