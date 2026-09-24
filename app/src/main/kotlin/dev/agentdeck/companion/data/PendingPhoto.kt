package dev.agentdeck.companion.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.claudeagents.core.mobile.MobileAttachment
import java.io.ByteArrayOutputStream

/** A photo the machine has already accepted, waiting for the prompt that will name it. */
data class PendingPhoto(
    val attachmentId: String,
    /** What the chip says — a size, because a phone photo has no name worth showing. */
    val label: String,
)

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
