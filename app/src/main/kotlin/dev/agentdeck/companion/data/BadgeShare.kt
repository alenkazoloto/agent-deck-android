package dev.agentdeck.companion.data

import android.content.Intent
import com.github.claudeagents.core.mobile.MobileBadgeCard
import java.io.File

/**
 * A badge leaving the app through Android's share sheet: the desk's own share line as plain text, or
 * the desk's card — the PNG the machine draws for its share dialog — as a private file with a read grant.
 *
 * Either way the phone posts nowhere itself, and nothing but catalog wording and one number is in it.
 */
object BadgeShare {

    /** The chooser for [text]; [title] is the subject a mail app shows. */
    fun chooser(title: String, text: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, "Share badge").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The card's PNG under [root] (the shared exports directory, swept after 24 h), or null when the
     * machine drew none — a locked badge, an answer that is not Base64, or bytes that are not a PNG.
     * The signature is checked because the file is handed to other apps under the machine's word.
     */
    fun writeCard(root: File, card: MobileBadgeCard, nowMs: Long): File? {
        if (card.unavailable != null) return null
        val png = runCatching { java.util.Base64.getDecoder().decode(card.png) }.getOrNull() ?: return null
        if (png.size < PNG_SIGNATURE.size || !png.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) return null
        return ConversationShare.write(root, card.fileName, png, nowMs)
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
