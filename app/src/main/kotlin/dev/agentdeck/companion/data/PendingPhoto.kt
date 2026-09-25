package dev.agentdeck.companion.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.claudeagents.core.mobile.MobileAttachment
import java.io.ByteArrayOutputStream

/**
 * A photo or text file the machine has already accepted, waiting for the prompt that will name it.
 * The name predates text files; the machine keeps both under the same id and the same limits.
 */
data class PendingPhoto(
    val attachmentId: String,
    /** What the chip says — a size for a photo, which has no name worth showing; the file's name and size for a text file. */
    val label: String,
)

/** What a text file's chip says and how the phone words its own refusals, before the machine is asked. */
object TextFileAttachment {

    /** "notes.md · 4 KB"; a long name keeps its start and its extension so two files stay tellable apart. */
    fun label(name: String, bytes: Int, fallback: String = "Text file"): String {
        val shown = if (name.length > MAX_NAME) name.take(MAX_NAME - 8) + "…" + name.takeLast(7) else name
        val size = when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> "${maxOf(1, bytes / 1024)} KB"
        }
        return "${shown.ifBlank { fallback }} · $size"
    }

    /** The sentence for a PDF this phone will not upload, or null when the machine may be asked. */
    fun pdfRefusal(bytes: ByteArray): String? = when {
        bytes.isEmpty() -> "That file is empty or could not be read"
        bytes.size > MobileAttachment.MAX_PDF_BYTES ->
            "That PDF is over ${MobileAttachment.MAX_PDF_BYTES / (1024 * 1024)} MB — only smaller PDFs can be attached"
        else -> null
    }

    /** The sentence for a ZIP archive this phone will not upload, or null when the machine may be asked. */
    fun zipRefusal(bytes: ByteArray): String? = when {
        bytes.isEmpty() -> "That file is empty or could not be read"
        bytes.size > MobileAttachment.MAX_ZIP_BYTES ->
            "That archive is over ${MobileAttachment.MAX_ZIP_BYTES / (1024 * 1024)} MB — only smaller archives can be attached"
        else -> null
    }

    /** The sentence for a gzip file this phone will not upload, or null when the machine may be asked. */
    fun gzipRefusal(bytes: ByteArray): String? = when {
        bytes.isEmpty() -> "That file is empty or could not be read"
        bytes.size > MobileAttachment.MAX_GZIP_BYTES ->
            "That file is over ${MobileAttachment.MAX_GZIP_BYTES / (1024 * 1024)} MB — only smaller gzip files can be attached"
        else -> null
    }

    /** The sentence for a tar archive this phone will not upload, or null when the machine may be asked. */
    fun tarRefusal(bytes: ByteArray): String? = when {
        bytes.isEmpty() -> "That file is empty or could not be read"
        bytes.size > MobileAttachment.MAX_TAR_BYTES ->
            "That file is over ${MobileAttachment.MAX_TAR_BYTES / (1024 * 1024)} MB — only smaller tar archives can be attached"
        else -> null
    }

    /** The sentence for an xz file this phone will not upload, or null when the machine may be asked. */
    fun xzRefusal(bytes: ByteArray): String? = when {
        bytes.isEmpty() -> "That file is empty or could not be read"
        bytes.size > MobileAttachment.MAX_XZ_BYTES ->
            "That file is over ${MobileAttachment.MAX_XZ_BYTES / (1024 * 1024)} MB — only smaller xz files can be attached"
        else -> null
    }

    /** The sentence for a bzip2 file this phone will not upload, or null when the machine may be asked. */
    fun bzip2Refusal(bytes: ByteArray): String? = when {
        bytes.isEmpty() -> "That file is empty or could not be read"
        bytes.size > MobileAttachment.MAX_BZIP2_BYTES ->
            "That file is over ${MobileAttachment.MAX_BZIP2_BYTES / (1024 * 1024)} MB — only smaller bzip2 files can be attached"
        else -> null
    }

    /** The sentence for a file this phone will not upload, or null when the machine may be asked. */
    fun refusal(bytes: ByteArray, pdfSupported: Boolean = false, zipSupported: Boolean = false, gzipSupported: Boolean = false, tarSupported: Boolean = false, xzSupported: Boolean = false, bzip2Supported: Boolean = false): String? = when {
        bytes.isEmpty() -> "That file is empty or could not be read"
        bytes.size > MobileAttachment.MAX_TEXT_BYTES ->
            "That file is over ${MobileAttachment.MAX_TEXT_BYTES / (1024 * 1024)} MB — only smaller text files can be attached"
        !MobileAttachment.looksLikeText(bytes) -> {
            // "UTF-8 text, a PDF or a ZIP archive" / "photos, text files, PDFs and ZIP archives": what this machine takes, and no more.
            val kinds = listOfNotNull(
                ("a PDF" to "PDFs").takeIf { pdfSupported },
                ("a ZIP archive" to "ZIP archives").takeIf { zipSupported },
                ("a gzip file" to "gzip files").takeIf { gzipSupported },
                ("a tar archive" to "tar archives").takeIf { tarSupported },
                ("an xz file" to "xz files").takeIf { xzSupported },
                ("a bzip2 file" to "bzip2 files").takeIf { bzip2Supported },
            )
            val accepted = listOf("UTF-8 text" to "text files") + kinds
            "That file is not ${joinWords(accepted.map { it.first }, "or")} — only photos${if (accepted.size > 1) ", " else " and "}${joinWords(accepted.map { it.second }, "and")} can be attached"
        }
        else -> null
    }

    /** "a", "a or b", "a, b or c". */
    private fun joinWords(words: List<String>, last: String): String =
        if (words.size == 1) words[0] else words.dropLast(1).joinToString(", ") + " $last " + words.last()

    private const val MAX_NAME = 28
}

/**
 * Turning what a camera or a picker handed us into something [MobileAttachment.MAX_ATTACH_BYTES]
 * will accept.
 *
 * The re-encode is the phone's job and not the machine's, for two reasons the plan names and one
 * it does not. A 12-megapixel JPEG is twelve megabytes of detail no model reads; sending it costs
 * the user's cellular data, not the machine's; and doing the shrink here means the cap the
 * machine enforces is a *check* on a client that already tried, rather than the first thing
 * every ordinary photo meets.
 *
 * Pure apart from [BitmapFactory], so a Robolectric test can drive it with real bytes.
 */
object PhotoEncoder {

    /** The longest edge a re-encode aims for before it starts lowering quality. */
    const val MAX_EDGE = 2048

    private val QUALITY_LADDER = intArrayOf(85, 70, 55, 40)

    /**
     * A JPEG within the cap, or null when even the last rung is too large.
     *
     * Null is a real answer the caller renders as a sentence naming both numbers — never a
     * silent drop, and never a send that goes without the photo the user watched it accept.
     */
    fun encode(source: ByteArray, cap: Int = MobileAttachment.MAX_ATTACH_BYTES): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        // Powers of two only: `inSampleSize` rounds down to one anyway, and asking for a size the
        // decoder will not honour is how a "resized" image arrives at its original dimensions.
        var sample = 1
        while (longest / sample > MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            source,
            0,
            source.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        try {
            for (quality in QUALITY_LADDER) {
                val out = ByteArrayOutputStream()
                if (!decoded.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                val bytes = out.toByteArray()
                if (bytes.size <= cap) return bytes
            }
        } finally {
            decoded.recycle()
        }
        return null
    }

    /** "1.4 MB" — what the chip says, since a picked photo has no name worth showing. */
    fun label(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> String.format("Photo · %.1f MB", bytes / (1024.0 * 1024.0))
        else -> "Photo · ${bytes / 1024} KB"
    }
}
