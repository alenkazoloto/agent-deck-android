/*
 * Agent Deck — the Android companion.
 *
 * Deliberately a *standalone* Gradle build: this directory is never included from the
 * plugin's root `settings.gradle.kts`. The plugin's build, its ~3000-test suite and its
 * screenshot loop must never pay the Android Gradle Plugin's configuration cost, and an
 * agent running `./gradlew test` at the repo root must not need an Android SDK.
 *
 * The two builds meet in exactly one place: `app/build.gradle.kts` points a source dir at
 * the plugin's own `core/` tree, so the wire format is *the same files*, not a copy.
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
