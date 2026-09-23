package dev.agentdeck.companion.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.agentdeck.companion.MainActivity
import dev.agentdeck.companion.Navigation
import dev.agentdeck.companion.R
import dev.agentdeck.companion.Screen
import dev.agentdeck.companion.data.NotifyTrigger

/**
 * The shade. Channels, one notification per blocked conversation, a summary over the group,
 * and the two things a user can do without opening the app.
 *
 * **Why a channel per trigger.** "Needs you" is an interruption and "finished" is news; a
 * single channel forces one importance on both, and the user's only remaining control is to
 * silence the app. Three channels put that decision in Android's own settings, which is where
 * every other app on the phone has taught them to look.
 *
 * **Why the reply action exists.** The whole premise is answering an agent without going to
 * the desk. A shade reply travels the same [dev.agentdeck.companion.data.BridgeClient] send as
 * the composer, including the rule that a refusal keeps the text — see [NotifyReceiver].
 */
object DeckNotifications {

    const val GROUP = "dev.agentdeck.companion.AGENTS"
    const val ONGOING_CHANNEL = "connection"
    const val ONGOING_ID = 1
    private const val SUMMARY_ID = 2

    /** The `RemoteInput` result key a shade reply arrives under. */
    const val REPLY_KEY = "reply"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        NotifyTrigger.entries.forEach { trigger ->
            val importance = if (trigger == NotifyTrigger.FINISHED) NotificationManager.IMPORTANCE_LOW
            else NotificationManager.IMPORTANCE_HIGH
            manager.createNotificationChannel(
                NotificationChannel(trigger.id, trigger.title, importance).apply {
                    description = trigger.description
                },
            )
        }
        manager.createNotificationChannel(
            NotificationChannel(
                ONGOING_CHANNEL,
                "Staying connected",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "The ongoing row shown while Agent Deck holds the connection open."
            },
        )
    }

    /** Android 13+ gates posting behind a runtime permission; below that, posting always works. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Applies one [AlertPlan]. Posting and cancelling are one call because a plan that only
     * cancelled would leave a summary claiming rows that are gone.
     */
    fun apply(context: Context, plan: AlertPlan) {
        if (!allowed(context)) return
        val manager = NotificationManagerCompat.from(context)
        ensureChannels(context)
        plan.cancel.forEach { key -> manager.cancel(idOf(key)) }
        plan.post.forEach { alert ->
            manager.notify(idOf(alert.key), build(context, alert))
        }
        val summary = AttentionAlerts.summary(plan.seen)
        if (summary == null) manager.cancel(SUMMARY_ID)
        else manager.notify(SUMMARY_ID, buildSummary(context, summary, plan.seen.size))
    }

    fun cancelAll(context: Context, keys: Collection<String>) {
        val manager = NotificationManagerCompat.from(context)
        keys.forEach { manager.cancel(idOf(it)) }
        manager.cancel(SUMMARY_ID)
    }

    private fun build(context: Context, alert: Alert): Notification {
        val builder = NotificationCompat.Builder(context, alert.trigger.id)
            .setSmallIcon(R.drawable.ic_stat_deck)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setSubText(alert.trigger.title)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(context, alert))

        // Only where they mean something: an agent that has stopped cannot be stopped, and
        // replying to a failed run is how the desktop restarts it, so both stay on "needs you".
        if (alert.trigger != NotifyTrigger.FINISHED) {
            builder.addAction(replyAction(context, alert))
        }
        if (alert.trigger == NotifyTrigger.NEEDS_YOU) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_deck,
                    "Stop",
                    broadcast(context, NotifyReceiver.ACTION_STOP, alert, mutable = false),
                ).build(),
            )
        }
        return builder.build()
    }

    /**
     * The one row on the shade when several agents are waiting. Android only collapses a group
     * once a summary exists; without it five blocked agents are five separate buzzes, which is
     * the state this app would otherwise train people to swipe away without reading.
     */
    private fun buildSummary(context: Context, summary: String, count: Int): Notification =
        NotificationCompat.Builder(context, NotifyTrigger.NEEDS_YOU.id)
            .setSmallIcon(R.drawable.ic_stat_deck)
            .setContentTitle(if (count == 1) "1 agent" else "$count agents")
            .setContentText(summary)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(
                activity(context, Navigation.link(Screen.Fleet).orEmpty(), SUMMARY_ID),
            )
            .build()

    private fun openIntent(context: Context, alert: Alert): PendingIntent {
        val link = Navigation.link(
            Screen.Conversation(alert.key, alert.title, alert.vendor, alert.projectPath),
        ).orEmpty()
        return activity(context, link, idOf(alert.key))
    }

    private fun activity(context: Context, link: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(link)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun replyAction(context: Context, alert: Alert): NotificationCompat.Action {
        val input = RemoteInput.Builder(REPLY_KEY)
            .setLabel("Reply to ${alert.vendor.name.lowercase().replaceFirstChar(Char::uppercase)}")
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stat_deck,
            "Reply",
            broadcast(context, NotifyReceiver.ACTION_REPLY, alert, mutable = true),
        )
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
    }

    /**
     * A reply's `PendingIntent` must be **mutable**: `RemoteInput` fills the typed text into
     * the intent as it fires, and an immutable one arrives with the extra missing — a Reply
     * button that always sends nothing.
     */
    private fun broadcast(
        context: Context,
        action: String,
        alert: Alert,
        mutable: Boolean,
    ): PendingIntent {
        val intent = Intent(context, NotifyReceiver::class.java).apply {
            this.action = action
            // A distinct data URI per (action, conversation): PendingIntents are matched by
            // everything *but* extras, so without it every row's Reply would resolve to the
            // first one registered and answer the wrong agent.
            data = Uri.parse("$action://${Uri.encode(alert.key)}")
            putExtra(NotifyReceiver.EXTRA_KEY, alert.key)
            putExtra(NotifyReceiver.EXTRA_VENDOR, alert.vendor.name)
            putExtra(NotifyReceiver.EXTRA_PROJECT, alert.projectPath)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, idOf(alert.key + action), intent, flags)
    }

    /**
     * A stable id per conversation, so the same agent updating twice replaces its own row
     * instead of stacking. Kept positive and clear of the two fixed ids above.
     */
    fun idOf(key: String): Int = (key.hashCode() and 0x7FFFFFFF) or 0x100
}
