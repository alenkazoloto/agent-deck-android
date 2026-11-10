package dev.agentdeck.companion.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat

/**
 * The `ACTION_SEND` half of [Sharing] — everything that needs a [Context], kept out of the
 * pure file so the composing rules stay testable on the JVM.
 *
 * Runs off the main thread: a shared file is read here, and the provider behind a
 * `content://` URI is another app.
 */
object ShareIntent {

    fun read(context: Context, intent: Intent?): SharedInput? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        // Text wins over a stream when both are present: a share carrying both is a link with
        // a preview image, and the link is the part the agent can act on.
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { text ->
            return Sharing.compose(subject, text.toString())
        }
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: return null
        return Sharing.compose(subject ?: displayName(context, uri), readText(context, uri))
    }

    /**
     * Bounded on purpose. A share sheet will hand over a 2 GB recording as happily as a log
     * file, and `readText()` on one is an OOM in an app the user only wanted to paste into.
     */
    private fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val reader = stream.reader()
            val buffer = CharArray(4096)
            buildString {
                while (length <= Sharing.MAX_CHARS) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    appendRange(buffer, 0, read)
                }
            }
        }
    }.getOrNull()

    /** The provider's own name for the file — `lastPathSegment` is a row id on most of them. */
    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}
