package dev.agentdeck.companion.data

import com.github.claudeagents.core.int
import com.github.claudeagents.core.long
import com.github.claudeagents.core.str
import com.google.gson.JsonObject
import java.net.URI

/**
 * One published build of this app, as the release's `latest.json` describes it.
 *
 * **Why a manifest asset and not the GitHub API.** `releases/latest/download/latest.json` is a
 * fixed address that redirects to whatever the newest release holds, served by the same CDN as
 * the APK: no token, no `api.github.com` rate limit (60/hour per IP, shared by every phone
 * behind one NAT), and no release payload to parse for the one asset we want.
 * `scripts/publish-mobile.sh` writes it in the same run that uploads the APK, and uploads it
 * **after** the APK — so a manifest that exists always has its download behind it.
 *
 * **[versionCode] is the comparison, never [versionName].** It is the integer Android itself
 * orders installs by, so there is no version-string grammar to get wrong here: the plugin's
 * own `core/ExtensionVersions` exists because a release *tag* is all the extension route has,
 * and this route does not have that problem.
 */
data class UpdateRelease(
    val versionName: String,
    val versionCode: Long,
    /** `release` or `debug` — said out loud, because a debug build is not a release artifact. */
    val variant: String,
    val apkName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** Absent in a manifest written before this field existed; present, it must match. */
    val sha256: String?,
    val minSdk: Int,
    val releaseUrl: String,
) {

    val label: String get() = versionName.ifBlank { versionCode.toString() }

    companion object {

        fun fromJson(o: JsonObject): UpdateRelease? {
            val versionCode = o.long("versionCode")
            val apkUrl = o.str("url").orEmpty()
            // Every one of these is required to *act*: a manifest missing any of them can only
            // produce a control that fails when pressed, and "no update known" is the honest
            // reading of a manifest this app cannot use.
            if (versionCode <= 0) return null
            if (!AppUpdate.isTrustedApkUrl(apkUrl)) return null
            val versionName = o.str("versionName")?.takeIf { it.isNotBlank() } ?: return null
            return UpdateRelease(
                versionName = versionName,
                versionCode = versionCode,
                variant = o.str("variant").orEmpty().ifBlank { "release" },
                apkName = o.str("apk")?.takeIf { it.isNotBlank() } ?: apkUrl.substringAfterLast('/'),
                apkUrl = apkUrl,
                sizeBytes = o.long("sizeBytes"),
                sha256 = o.str("sha256")?.takeIf { it.isNotBlank() },
                // A manifest that names no floor cannot refuse a phone; 0 offers it to everyone,
                // which is what every build before this field did anyway.
                minSdk = o.int("minSdk") ?: 0,
                releaseUrl = o.str("releaseUrl").orEmpty(),
            )
        }
    }
}

/**
 * What the app knows about a newer build of itself, and what it is doing about it.
 *
 * All of it is one object on [dev.agentdeck.companion.DeckState] so that the banner and the
 * Settings rows read the same fact: two surfaces deriving "is there an update" separately is
 * how one of them ends up offering a download the other says is already installed.
 */
data class UpdateState(
    /** The newest published build, whatever it is relative to this one. Null until a check lands. */
    val release: UpdateRelease? = null,
    val installedCode: Long = 0,
    val installedName: String = "",
    val checking: Boolean = false,
    /** 0..100 while a download is running, null when none is. */
    val downloadPercent: Int? = null,
    /** Absolute path of a verified APK waiting for Android's installer. */
    val readyApk: String? = null,
    /** The last failure, verbatim and sticky: a check that failed silently is a check nobody made. */
    val error: String? = null,
    val checkedAtMs: Long = 0,
    /** The newest [UpdateRelease.versionCode] the user has waved away in the banner. */
    val dismissedCode: Long = 0,
) {

    /** A strictly newer build exists. A downgrade is never an update, however new the release is. */
    val available: Boolean get() = release != null && release.versionCode > installedCode

    val busy: Boolean get() = checking || downloadPercent != null
}

/**
 * The update decisions, out of the Composables and off the network, so a JVM test holds them.
 *
 * The app is sideloaded — there is no store to notice a new build for it — so this is the whole
 * of how a phone ever leaves the version it was handed. That makes the wrong answers expensive
 * in both directions: an update the app misses strands the user on a build the machine has
 * moved past, and an update it invents is a download that cannot install.
 */
object AppUpdate {

    /**
     * Twelve hours between automatic checks. The manifest is a few hundred bytes, but the check
     * runs on whatever connection the phone has, and an app that reaches the network every time
     * it is opened is one whose battery screen names it.
     */
    const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

    /** Where a downloaded APK is kept, under the cache dir the FileProvider exports. */
    const val DOWNLOAD_DIR = "updates"

    fun shouldCheck(nowMs: Long, lastCheckedAtMs: Long): Boolean =
        // A clock that moved backwards (timezone, NTP, a user setting the date) would otherwise
        // park the next check up to twelve hours in the future for no reason.
        lastCheckedAtMs <= 0 || nowMs < lastCheckedAtMs || nowMs - lastCheckedAtMs >= CHECK_INTERVAL_MS

    /**
     * Whether a manifest's download address is one this app will fetch and hand to the package
     * installer.
     *
     * The manifest is the *only* input to an install, so an unpinned `url` field is a route from
     * "whatever answers that address" straight into Android's installer. Releases live on
     * `github.com`, which redirects to `objects.githubusercontent.com` for the bytes; both are
     * accepted, nothing else is, and the scheme must be TLS.
     */
    fun isTrustedApkUrl(url: String): Boolean {
        val host = runCatching { URI(url) }.getOrNull()
            ?.takeIf { it.scheme.equals("https", ignoreCase = true) }
            ?.host?.lowercase() ?: return false
        return host == "github.com" ||
            host == "githubusercontent.com" ||
            host.endsWith(".github.com") ||
            host.endsWith(".githubusercontent.com")
    }

    /**
     * A downloaded file is what it claimed to be. An absent [expected] is a manifest from before
     * the field existed, not a failure — the installer still verifies the signature, which is the
     * check that decides whether the APK may replace this app at all.
     */
    fun digestMatches(expected: String?, actual: String): Boolean =
        expected.isNullOrBlank() || expected.trim().equals(actual.trim(), ignoreCase = true)

    /** True when this phone is too old for the published build, so nothing may be offered. */
    fun tooOld(release: UpdateRelease?, sdkInt: Int): Boolean =
        release != null && release.minSdk > sdkInt

    /**
     * The banner's whole condition. It appears **only** while it changes a decision: an update
     * exists, this phone can install it, the user has not waved this version away, and notices
     * are on.
     *
     * A download in flight or a file waiting for the installer overrides all four. That work was
     * asked for — by this user, from this screen — and a progress bar that vanishes because the
     * *announcement* was switched off is a download with nowhere left to report.
     */
    fun bannerWorthy(state: UpdateState, notices: Boolean, sdkInt: Int): Boolean =
        state.downloadPercent != null ||
            state.readyApk != null ||
            (
                notices &&
                    state.available &&
                    !tooOld(state.release, sdkInt) &&
                    (state.release?.versionCode ?: 0) > state.dismissedCode
                )

    /**
     * The one sentence Settings and the banner both read, so neither can describe a state the
     * other is not in.
     */
    fun status(state: UpdateState, sdkInt: Int): String {
        val release = state.release
        return when {
            state.downloadPercent != null -> "Downloading ${release?.label.orEmpty()} — ${state.downloadPercent}%".trim()
            state.checking -> "Checking…"
            // Above "Downloaded", not below it: the failure that survives a *successful*
            // download is the installer refusing to open, and reporting that state as "Android's
            // installer takes it from here" is the one sentence the user must not be told.
            state.error != null -> state.error
            state.readyApk != null -> "Downloaded. Android's installer takes it from here."
            release == null -> "Not checked yet."
            tooOld(release, sdkInt) ->
                "${release.label} needs Android API ${release.minSdk}; this phone is on $sdkInt."
            state.available && release.variant == "debug" ->
                "${release.label} is available — a debug build, signed with the debug key."
            state.available -> "${release.label} is available."
            else -> "Up to date."
        }
    }

    /** Bytes as the download line says them. Kept here so the banner and Settings agree. */
    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
        else -> "${bytes / 1024} KB"
    }
}
