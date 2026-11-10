plugins {
    id("com.android.application") version "9.3.1" apply false
    // AGP 9 ships Kotlin support built in — `org.jetbrains.kotlin.android` is *rejected*.
    // The Compose compiler is still a separate plugin and must match the Kotlin version
    // AGP embeds (9.3.1 → 2.2.10); a mismatch fails the build with a version error.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
