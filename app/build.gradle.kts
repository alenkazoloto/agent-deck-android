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
    // The push wire: the trigger ids the phone's notification channels are keyed by, the
    // payload both ends read, and the register body the phone POSTs. Shared for the reason
    // MobileBroadcast is — a format declared twice is a format only one side can test, and
    // the half that gets it wrong here is the one that runs while nobody is looking.
    "com/github/claudeagents/core/mobile/MobilePush.kt",
    // The `agentdeck://` grammar. Shared because the *IDE* mints links into it as well — the
    // ⋯ menu's "Continue on phone" prints one as a QR — and a link built by hand on one side
    // of the wire is a second producer of a format only one side can test.
    "com/github/claudeagents/core/mobile/MobileDeepLink.kt",
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
val accountsSourceFile = "com/github/claudeagents/core/accounts/Accounts.kt"

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
        // The stub's package is read off the real file's own path, not written out here: the
        // copied `Model.kt`/`SessionAttention.kt` import `Accounts` by its plugin-side package,
        // so a `core/` reshuffle that moves Accounts.kt has to move the stub with it. It moved
        // once already (92f289d4, core/ → core/accounts/) and every Android build broke.
        val accountsDir = root.toPath().relativize(accountsFile.get().asFile.toPath())
            .parent.toString()
        File(out, "$accountsDir/AccountsDefaultId.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package ${accountsDir.replace(File.separatorChar, '.')}

                // GENERATED at build time from Accounts.kt — do not edit, do not commit.
                // Accounts.kt itself cannot compile for Android (java.nio.file.Files.readString);
                // Model.kt and SessionAttention.kt need only this one constant from it.
                object Accounts {
                    const val DEFAULT_ID = "$defaultId"
                }

                """.trimIndent(),
            )
        }
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

/**
 * Where a published build is downloaded from — **the one place that address is written**.
 *
 * `scripts/publish-mobile.sh` reads these two lines out of this file for the repository it
 * pushes to, and the app compiles them into `BuildConfig` for the repository it checks. Two
 * literals in two files is how an app ends up checking a repository nothing uploads to, which
 * looks exactly like "there has never been an update".
 */
val updateOwner = "alenkazoloto"
val updateRepo = "agent-deck-android"

android {
    namespace = "dev.agentdeck.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.agentdeck.companion"
        minSdk = 26
        targetSdk = 35
        // `versionCode` is what the installed app compares against the published manifest, so a
        // build published without bumping it reads as "up to date" on every phone that already
        // has one — the same trap the extension catalogue pays at `ExtensionVersions`. The
        // publish script says so out loud when the release it replaces carries the same number.
        // 6/"1.5" was published and then bumped past for no reason: `assembleDebug` is
        // `finalizedBy(publishApk)`, so the *build* had already made v1.5, and the manual
        // `publish-mobile.sh` after it said "replacing release v1.5" about that build. Read as a
        // foreign publish, it bought this bump. Harmless — a higher code always ships — and left
        // where it landed rather than moved back below what the mirror already serves.
        versionCode = 8
        versionName = "1.7"

        // `releases/latest/download/<asset>` is a fixed address that redirects to whatever the
        // newest release holds: no token, and none of `api.github.com`'s 60-per-hour-per-IP.
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/$updateOwner/$updateRepo/releases/latest/download/latest.json\"",
        )
        buildConfigField(
            "String",
            "UPDATE_RELEASES_URL",
            "\"https://github.com/$updateOwner/$updateRepo/releases/latest\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig carries the download address; AGP 8+ needs it asked for by name.
        buildConfig = true
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
    // Explicitly *ahead* of the BOM's managed 1.3.2 (MP-09). The component vocabulary this app
    // is judged against — ListItem, HorizontalDivider, AssistChip, LoadingIndicator — is what
    // every screen here hand-builds out of Column + Text instead. 1.4.0 declares
    // `minCompileSdk=35` and `minAndroidGradlePluginVersion=8.6.0` in its AAR metadata, both of
    // which this build already clears, and its kotlin-stdlib is 2.0.21 — readable by the 2.2.10
    // compiler AGP 9.3.1 embeds, so the metadata trap that pins the markdown renderer to 0.35.0
    // does not apply. 1.5.0-alpha declares minCompileSdk=37 and is correctly out of reach.
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    // NotificationCompat, RemoteInput and ServiceCompat — the shade and the foreground
    // service are core-ktx APIs, and compose-bom does not carry them.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    // ProcessLifecycleOwner: "the app came back to the foreground" is a process-scoped event and
    // there is no other way to observe it. LiveLink outlives every Activity, so an
    // Activity-scoped observer would miss the case it exists for (MP-03).
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // EncryptedSharedPreferences for the device token; the store falls back to private
    // prefs when the keystore refuses (see SecureStore).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // QR pairing. Manual entry is a first-class path, so this is never the only way in.
    //
    // The camera half is CameraX and the decode half is zxing's own `core`. It used to be
    // `com.journeyapps:zxing-android-embedded`, a wrapper at 5,931 stars whose last commit was
    // 2024-08-04 — checked 2026-08-22, unmaintained for two years and under the bar on both
    // clauses of rule 23. The *decoder* was never the stale part: `com.google.zxing:core` is
    // 34,072 stars, pushed 2026-08-21, and is what that wrapper wrapped, so this keeps the
    // decode path pairing already trusts and replaces only the Activity around it.
    //
    // ML Kit was the other candidate and is rejected on one line of its POM: it depends on
    // `com.google.android.gms:play-services-basement`. This app is sideloaded, local-first and
    // has no server in the middle; adding a GMS dependency to read a QR code is a worse trade
    // than writing the preview.
    //
    // CameraX is pinned to 1.5.3 for the same reason everything else here is: 1.6.x declares
    // `minCompileSdk=36` in its AAR metadata, and 1.5.3 declares 35.
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    // 34,072 GitHub stars, pushed 2026-08-21 — checked 2026-08-22.
    implementation("com.google.zxing:core:3.5.4")

    // Markdown for assistant turns, rendered by a real CommonMark+GFM parser rather than the
    // four regexes this used to be — agents emit tables, block quotes and links constantly and
    // all three arrived as raw punctuation. Apache-2.0, and the artifact declares *no* Compose
    // dependency (only `org.jetbrains:markdown`), so it cannot drag this build past
    // compileSdk 35. 0.35.0 is the last line built on Kotlin 2.1.x: 0.39+ is Kotlin 2.3, whose
    // metadata the 2.2.10 compiler AGP 9.3.1 embeds refuses to read.
    //
    // 1,047 GitHub stars, pushed 2026-08-18 — checked 2026-08-22. Below rule 23's bar and
    // grandfathered there by name: no CommonMark+GFM Compose renderer exists at any higher
    // popularity, the whole category tops out near 1k, and forcing this out ships a worse
    // transcript than it saves.
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.35.0")

    // Allowed here: the zero-dependency rule is the *plugin's*, and `core/mobile` already
    // encodes through Gson on both sides.
    // 24,228 GitHub stars, pushed 2026-08-14 — checked 2026-08-22.
    implementation("com.google.code.gson:gson:2.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 8,518 GitHub stars, pushed 2026-08-13 — checked 2026-08-22. Grandfathered on downloads:
    // JUnit 4's Maven Central volume is orders of magnitude past rule 23's bar, and a
    // 2000-era repository's star count is not the measure of it.
    testImplementation("junit:junit:4.13.2")

    // MU-02: layout regressions fail the build with a diff image, on the JVM, with no device.
    // `unitTests.isIncludeAndroidResources` above is what lets Robolectric inflate the theme.
    // 6,039 GitHub stars, pushed 2026-08-21 — checked 2026-08-22. Grandfathered on downloads:
    // the standard Android JVM test runtime, and the only reason these goldens need no device.
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // 1,018 GitHub stars, pushed 2026-08-16 — checked 2026-08-22. Below the bar, grandfathered,
    // test-only: it is what makes a layout regression fail a build instead of shipping.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    // 1,018 GitHub stars, pushed 2026-08-16 — checked 2026-08-22. Same artifact family.
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
