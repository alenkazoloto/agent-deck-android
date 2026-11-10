/* Android is a separate Gradle build so host verification needs no Android SDK.
 * app/build.gradle.kts extracts selected shared sources and resources from the host checkout.
 */
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

rootProject.name = "agent-deck"
include(":app")
