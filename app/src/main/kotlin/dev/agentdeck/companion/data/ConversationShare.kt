package dev.agentdeck.companion.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.github.claudeagents.core.mobile.MobileSessionExport
import java.io.File

/**
 * A conversation leaving the app through Android's share sheet (M3): the machine's `/export`
 * text written to a private cache file and offered as a `content://` URI with a read grant.
 *
 * A file rather than `EXTRA_TEXT` because a long chat is megabytes, which a Binder transaction
 * refuses, and because "save to Files" and mail apps want an attachment with the desk's name.
 * Each export gets its own directory so a second share never rewrites a file a recipient is
 * still reading; everything older than [RETAIN_MS] is swept, since the recipient's grant cannot
 * be observed ending.
 */
object ConversationShare {

    const val DIR = "exports"
    const val RETAIN_MS = 24L * 60 * 60 * 1000
    private const val FALLBACK_NAME = "conversation.txt"
    private const val MAX_NAME = 120

    /** The provider the manifest declares; `file_paths.xml` exposes [DIR] beside the updates. */
    private fun authority(context: Context): String = "${context.packageName}.updates"

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    /**
     * The machine's name reduced to one plain file name: no separators, no leading dot, nothing a
     * recipient could read as a path — the name arrives over the wire, so it is not trusted.
     */
    fun safeName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .trimStart('.', '-')
            .take(MAX_NAME)
        if (cleaned.isBlank() || cleaned.trim('.', '-', '_').isEmpty()) return FALLBACK_NAME
        return if ('.' in cleaned) cleaned else "$cleaned.txt"
    }

    /** Drops every export directory written before [nowMs] − [RETAIN_MS]. */
    fun sweep(root: File, nowMs: Long) {
        root.listFiles()?.forEach { entry ->
            if (nowMs - entry.lastModified() >= RETAIN_MS) entry.deleteRecursively()
        }
    }

    /** Writes [export] under [root]/<nowMs>/ and returns the file; sweeps expired ones first. */
    fun write(root: File, export: MobileSessionExport, nowMs: Long): File = write(root, export.fileName, export.text, nowMs)

    /** The same private, swept, one-directory-per-share file for any text the machine renders (the usage CSV). */
    fun write(root: File, fileName: String, text: String, nowMs: Long): File {
        sweep(root, nowMs)
        var slot = File(root, nowMs.toString())
        var n = 1
        while (slot.exists()) slot = File(root, "$nowMs-${n++}")
        check(slot.mkdirs()) { "Could not prepare the file to share." }
        return File(slot, safeName(fileName)).apply { writeText(text) }
    }

    /** The chooser for [file]; the recipient may read this one URI and nothing else. */
    fun chooser(context: Context, file: File, title: String, mime: String = "text/plain", label: String = "Share conversation"): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser only forwards a grant it can see on its own ClipData.
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
