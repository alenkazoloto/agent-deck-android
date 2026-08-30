package dev.agentdeck.companion.notify

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.NotifyTrigger

/** One conversation, and why the phone is about to buzz about it. */
data class Alert(
    val key: String,
    val trigger: NotifyTrigger,
    val title: String,
    val body: String,
    val vendor: AgentVendor,
    val projectPath: String,
)

/**
 * What to post, what to take down, and what to remember — computed from two snapshots and
 * nothing else.
 *
 * [post] is rows that *entered* a notifiable state since the last snapshot; [cancel] is rows
 * whose notification is no longer true, either because the state changed or because the row
 * left the fleet. [seen] is the map to hand back next time.
 */
data class AlertPlan(
    val post: List<Alert>,
    val cancel: List<String>,
    val seen: Map<String, String>,
) {
    val isEmpty: Boolean get() = post.isEmpty() && cancel.isEmpty()
}

/**
 * The notification decision, as a pure function over the fleet.
 *
 * Everything here exists to answer one requirement: *a run that needs the user produces
 * exactly one notification.* Snapshots arrive on every SSE `fleet` frame — several a minute on
 * a busy machine — and each one re-states every waiting row, so posting from the snapshot
 * itself would buzz once per frame for as long as the agent stayed blocked. The state is
 * therefore an **edge**: `key → trigger` is remembered, and only a row whose trigger *changed*
 * is news.
 *
 * [suppressKey] is the conversation the user is looking at right now. Telling someone about
 * the thing on their screen is the phone equivalent of the epic's presence gating — the
 * IDE-focus half of that contract belongs to the plugin (`PLAN-MOBILE-COMPANION.md` M6) and
 * is not app-side.
 */
object AttentionAlerts {

    fun plan(
        snapshot: MobileFleetSnapshot?,
        settings: AppSettings,
        seen: Map<String, String>,
        suppressKey: String? = null,
    ): AlertPlan {
        val rows = snapshot?.rows.orEmpty()
        if (snapshot == null) return AlertPlan(emptyList(), emptyList(), seen)

        val now = mutableMapOf<String, String>()
        val post = mutableListOf<Alert>()
        for (row in rows) {
            val trigger = NotifyTrigger.of(row)?.takeIf(settings::notifies) ?: continue
            now[row.key] = trigger.id
            if (seen[row.key] == trigger.id) continue
            // Remembered even when it is not posted, so leaving the conversation does not
            // immediately buzz about the row the reader just closed.
            if (row.key == suppressKey) continue
            post += alert(row, trigger)
        }

        // A row that left its trigger — answered, restarted, reviewed, or gone from the fleet
        // entirely — has a notification on the shade making a claim that is no longer true.
        val cancel = seen.keys.filter { it !in now }
        return AlertPlan(post, cancel, now)
    }

    private fun alert(row: MobileFleetRow, trigger: NotifyTrigger): Alert = Alert(
        key = row.key,
        trigger = trigger,
        title = row.title.ifBlank { "(no title)" },
        // The plugin's own live line when it has one — it is the sentence that says *what* is
        // wanted — and the project when it does not, because a title alone does not say which
        // repo an agent is asking about.
        body = row.liveLine?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(row.projectName.takeIf { it.isNotBlank() }, row.gitBranch?.takeIf { it.isNotBlank() })
                .joinToString(" · "),
        vendor = row.vendor,
        projectPath = row.projectPath,
    )

    /**
     * The summary line for the grouped notification. Five waiting agents must be one buzz and
     * one row on the shade, not five of each.
     */
    fun summary(seen: Map<String, String>): String? {
        if (seen.size < 2) return null
        val counts = NotifyTrigger.entries.mapNotNull { trigger ->
            val n = seen.values.count { it == trigger.id }
            if (n == 0) null else "$n ${trigger.title.lowercase()}"
        }
        return counts.joinToString(" · ")
    }
}
