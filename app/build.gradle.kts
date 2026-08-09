import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The plugin's Kotlin root. Agent Deck compiles the plugin's `core/mobile` wire format from
 * the plugin's own files, so the phone and the IDE cannot disagree about a field name.
 */
val pluginSources = rootProject.layout.projectDirectory.dir("../src/main/kotlin")

/**
 * Exactly what the phone needs: the three protocol files, plus only what they need in order
 * to resolve. The server-only members of `core/mobile` — MobileTls, WebPushEncrypt,
 * MobileFleet, MobileHandover, MobilePresence — are deliberately absent; they mint
 * certificates or project IDE state, neither of which a phone does.
 */
val sharedProtocolFiles = listOf(
    "com/github/claudeagents/core/mobile/MobileProtocol.kt",
    "com/github/claudeagents/core/mobile/MobileTranscript.kt",
    // The `run` frame's body. Both ends read it through the same object on purpose: the phone
    // decoding "which conversations moved" differently from the plugin encoding it is how a
    // reader ends up on a page that never refreshes.
    "com/github/claudeagents/core/mobile/MobileBroadcast.kt",
    "com/github/claudeagents/core/mobile/MobilePairing.kt",
    "com/github/claudeagents/core/JsonExt.kt",
    "com/github/claudeagents/core/SessionAttention.kt",
    "com/github/claudeagents/core/Model.kt",
    // `SessionSummary.healthStats` is typed `HealthStat`. It used to be declared inside
    // `TranscriptHealth.kt`, so the phone had to compile the whole analyzer to name one data
    // class — and on 2026-08-02 that analyzer grew a call into `ToolCallPresentation`, which
    // needs `PersistedToolOutput`, which needs `ClaudeHome`, which cannot compile for Android
    // at all. Every Android build was broken for a week with no symptom, because nobody ran
    // one. `HealthStat` is a wire-format type and now lives in its own file; the analyzer is
    // not shared. `ArchitectureRulesTest` rule 16 fails when a listed file reaches for a
    // `core/` sibling that is not listed.
    "com/github/claudeagents/core/HealthStat.kt",
    // `Model.kt` derives SessionSummary.ACTIVE_TAIL_WINDOW_MS through Windows.tailWindowMs,
    // so the phone cannot resolve the file without it. Pure Kotlin, nothing to strip.
    "com/github/claudeagents/core/Windows.kt",
)

/**
 * `core/Accounts.kt` is the one transitive dependency that cannot compile for Android:
 * `java.nio.file.Files.readString` is a Java 11 method and is absent from `android.jar` at
 * every compileSdk (adding `desugar_jdk_libs_nio` to the compile classpath does not help —
 * the platform's own `java.*` wins), and the file additionally pulls in `ClaudeHome` and a
 * macOS keychain subprocess.
 *
 * `Model.kt` and `SessionAttention.kt` need exactly one symbol from it: `Accounts.DEFAULT_ID`.
 * So the build *lifts that literal out of the plugin's own source* rather than forking the
 * file or hand-copying the value — if the plugin ever changes it, the next Android build
 * changes with it, and if the declaration disappears the build fails by name.
 */
val accountsSourceFile = "com/github/claudeagents/core/Accounts.kt"

/**
 * `:shared-core` filters the plugin tree with `kotlin.include(...)` on its source set. That
 * does not survive here: AGP 9 removed `filter` from its `AndroidSourceDirectorySet`, its
 * built-in Kotlin support exposes no `main` Kotlin source set (only per-variant ones, which
 * hold `src/<variant>/kotlin` and nothing else), and AGP hands `src/main/kotlin` to the
 * compiler through a file collection that `SourceTask.include` does not filter — pointing a
 * raw srcDir at the plugin tree therefore drags in every `com.intellij` import in `actions/`.
 *
 * So the *selection* moves into a task instead of a source-set filter. This is still shared
 * source, not a duplicate: the files are read out of the plugin's tree on every build and
 * land in `build/`, which is gitignored and never edited. There is exactly one editable copy
 * of the wire format, and it is the plugin's.
 */
abstract class ExtractSharedProtocol : DefaultTask() {

    /** Declared so a plugin-side protocol edit re-runs this task and the compile after it. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Internal
    abstract val sourceRoot: DirectoryProperty

    @get:Input
    abstract val relativePaths: ListProperty<String>

    /** The file `Accounts.DEFAULT_ID` is lifted out of; see the comment on the pattern. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val accountsFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun extract() {
        val root = sourceRoot.get().asFile
        val out = outputDir.get().asFile
        val wanted = relativePaths.get()
        fs.sync {
            from(root) { include(wanted) }
            into(out)
        }
        // A pattern that matches nothing means the protocol moved or was renamed in the
        // plugin tree; without this the failure surfaces as a pile of unresolved references
        // far from the cause.
        val missing = wanted.filterNot { File(out, it).isFile }
        require(missing.isEmpty()) { "Shared protocol sources missing from the plugin tree: $missing" }

        val accountsText = accountsFile.get().asFile.readText()
        val defaultId = Regex("""const\s+val\s+DEFAULT_ID\s*=\s*"([^"]*)"""")
            .find(accountsText)?.groupValues?.get(1)
        require(defaultId != null) {
            "Accounts.DEFAULT_ID no longer declared in ${accountsFile.get().asFile}; " +
                "the Android build lifts that literal and cannot guess it."
        }
        File(out, "com/github/claudeagents/core/AccountsDefaultId.kt").writeText(
            """
            package com.github.claudeagents.core

            // GENERATED at build time from core/Accounts.kt — do not edit, do not commit.
            // Accounts.kt itself cannot compile for Android (java.nio.file.Files.readString);
            // Model.kt and SessionAttention.kt need only this one constant from it.
            object Accounts {
                const val DEFAULT_ID = "$defaultId"
            }

            """.trimIndent(),
        )
    }
}

val syncSharedProtocol = tasks.register<ExtractSharedProtocol>("syncSharedProtocol") {
    description = "Materialises the plugin's core/mobile wire format for the Android compiler."
    sourceRoot.set(pluginSources)
    relativePaths.set(sharedProtocolFiles)
    sourceFiles.from(pluginSources.asFileTree.matching { include(sharedProtocolFiles) })
    accountsFile.set(pluginSources.file(accountsSourceFile))
    outputDir.set(layout.buildDirectory.dir("generated/shared-protocol/kotlin"))
}

android {
    namespace = "dev.agentdeck.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.agentdeck.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        // `core/` reaches for java.nio.file; desugaring keeps the shared sources compiling
        // unmodified rather than forking a phone-only copy of them.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric inflates `Theme.AgentDeck` and the widget layout; without the resources
        // on the unit-test classpath every golden would be a blank frame.
        unitTests.isIncludeAndroidResources = true
    }
}

// The Variant API is the only route AGP 9 accepts for a task-produced source dir; it also
// carries the task dependency, which a plain `srcDir` would not.
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            syncSharedProtocol,
            ExtractSharedProtocol::outputDir,
        )
    }
}

/**
 * Republishes the APK to the download repository — see `scripts/publish-mobile.sh`.
 *
 * Wired to *every* `assemble` below, because a download that is one build behind is worse than
 * no download: the release page reads as current whatever it is holding. The gate is the token
 * and it is checked at execution time, so a build on a machine that cannot publish skips this
 * and is otherwise untouched — the common case, and it must never be a build failure.
 */
val publishApk = tasks.register<Exec>("publishApk") {
    group = "publishing"
    description = "Pushes mobile/ and uploads the built APK to its GitHub release."
    workingDir = rootProject.layout.projectDirectory.dir("..").asFile
    commandLine("./scripts/publish-mobile.sh")
    // A publish is a *side effect* of building, so it may not turn a good build red — an
    // assembleRelease with no signing config produces an APK no device installs, and the
    // script refuses it by name. That refusal is a useful thing to read and a terrible thing
    // to fail on. Run the script directly to have its exit code mean something.
    isIgnoreExitValue = true
}

// The gate is `enabled`, set here, rather than an `onlyIf` lambda: a lambda declared in a
// `.kts` captures the script object, and the configuration cache refuses to serialize one —
// "cannot serialize Gradle script object references". Reading the provider at configuration
// time is also what makes the variable a configuration-cache *input*, so exporting a token
// invalidates the cache rather than being ignored until the next clean build.
if (
    !providers.environmentVariable("GITHUB_TOKEN")
        .orElse(providers.environmentVariable("GH_TOKEN"))
        .isPresent
) {
    publishApk.configure { enabled = false }
}

// Only the two tasks that produce the app's own APK. `startsWith("assemble")` also catches
// the androidTest and unitTest variants, which would republish on every test run.
tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }
    .configureEach { finalizedBy(publishApk) }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Pinned to the last AndroidX line that compiles against API 35: the 2026 releases
    // require compileSdk 36+, and compileSdk 35 is the fixed target here.
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    // NotificationCompat, RemoteInput and ServiceCompat — the shade and the foreground
    // service are core-ktx APIs, and compose-bom does not carry them.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // EncryptedSharedPreferences for the device token; the store falls back to private
    // prefs when the keystore refuses (see SecureStore).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // QR pairing. Manual entry is a first-class path, so this is never the only way in.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Markdown for assistant turns, rendered by a real CommonMark+GFM parser rather than the
    // four regexes this used to be — agents emit tables, block quotes and links constantly and
    // all three arrived as raw punctuation. Apache-2.0, and the artifact declares *no* Compose
    // dependency (only `org.jetbrains:markdown`), so it cannot drag this build past
    // compileSdk 35. 0.35.0 is the last line built on Kotlin 2.1.x: 0.39+ is Kotlin 2.3, whose
    // metadata the 2.2.10 compiler AGP 9.3.1 embeds refuses to read.
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.35.0")

    // Allowed here: the zero-dependency rule is the *plugin's*, and `core/mobile` already
    // encodes through Gson on both sides.
    implementation("com.google.code.gson:gson:2.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    // MU-02: layout regressions fail the build with a diff image, on the JVM, with no device.
    // `unitTests.isIncludeAndroidResources` above is what lets Robolectric inflate the theme.
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
