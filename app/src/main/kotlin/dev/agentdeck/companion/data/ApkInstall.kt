package dev.agentdeck.companion.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * The handoff to Android's package installer.
 *
 * The app downloads; it does not install. `ACTION_VIEW` on a `content://` URI is the whole of
 * what a sideloaded app may do — the user sees the system's own install screen, with the
 * package name and the permissions on it, and can say no. There is no silent path here and this
 * does not want one.
 *
 * Two things can refuse before that screen appears, and each has its own way out:
 *
 * - **"Install unknown apps" is off for this app.** Android 8 replaced the one global setting
 *   with a per-app grant, so this is not a permission the manifest can hold — `canRequestPackageInstalls`
 *   is a question about a setting only the user can flip, and [requestPermission] opens the page
 *   that flips it.
 * - **The APK is signed by a different key than the installed app.** The installer refuses, and
 *   it is right to: a debug-signed build cannot replace a release-signed one on the same device.
 *   Nothing here can pre-empt that, so the release page stays one tap away for a clean reinstall.
 */
object ApkInstall {

    /** The `authorities` in the manifest's provider. Both ends must say the same string. */
    private fun authority(context: Context): String = "${context.packageName}.updates"

    fun dir(context: Context): File = File(context.cacheDir, AppUpdate.DOWNLOAD_DIR)

    /** Where [release] lands. Named by version, so two attempts at one build reuse the file. */
    fun target(context: Context, release: UpdateRelease): File =
        File(dir(context), "agent-deck-${release.versionCode}.apk")

    /** Drops every download that is not [keep]. A cache dir full of old APKs is our litter. */
    fun sweep(context: Context, keep: File?) {
        dir(context).listFiles()?.forEach { file ->
            if (file != keep) file.delete()
        }
    }

    fun canInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun requestPermission(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
    )

    /** Hands the file to the installer. False means no activity took it, which the caller says. */
    fun launch(context: Context, apk: File): Boolean {
        val uri = runCatching { FileProvider.getUriForFile(context, authority(context), apk) }
            .getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, intent)
    }

    /** The way out when anything above refuses: the release page, in the browser. */
    fun openPage(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        return start(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess
}
