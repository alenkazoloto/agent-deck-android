package dev.agentdeck.companion.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileDeepLink
import dev.agentdeck.companion.MainActivity
import dev.agentdeck.companion.R

/**
 * The runtime half of the launcher's long-press menu — "New chat" is static
 * (`res/xml/shortcuts.xml`) because it names a destination the build already knew.
 *
 * Exactly one dynamic shortcut, and it is deliberately not a top-five list: a launcher sheet
 * showing five stale conversation titles is five chances to open the wrong one, and the fleet
 * — which is a tap away and always current — is the list. What a shortcut buys over that is
 * the *one* thread you were just in.
 */
object AppShortcuts {

    private const val RECENT_ID = "recent-thread"

    /** Labels are what a launcher sheet has room for, not what a title bar has. */
    private const val SHORT_LABEL_CHARS = 24
    private const val LONG_LABEL_CHARS = 48

    /**
     * Publishes [row] as the "most recent thread" shortcut, or withdraws it when there is none.
     *
     * Null means the phone is unpaired or the machine has no conversations — and a shortcut
     * left behind then opens a conversation on a machine this phone no longer talks to.
     */
    fun publishRecent(context: Context, row: MobileFleetRow?) {
        val link = row?.let {
            MobileDeepLink.conversation(it.key, it.title, it.vendor, it.projectPath)
        }
        if (row == null || link == null) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(RECENT_ID))
            return
        }
        val label = row.title.ifBlank { row.projectPath.substringAfterLast('/') }
            .ifBlank { "Conversation" }
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(link))
        // Failures are swallowed on purpose: `pushDynamicShortcut` is rate-limited by the
        // launcher and throws when a background app exceeds it, and a shortcut that could not
        // be refreshed is not a reason to take down the fleet that was refreshing it.
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(
                context,
                ShortcutInfoCompat.Builder(context, RECENT_ID)
                    .setShortLabel(label.take(SHORT_LABEL_CHARS))
                    .setLongLabel(label.take(LONG_LABEL_CHARS))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_conversation))
                    .setIntent(intent)
                    .build(),
            )
        }
    }
}
