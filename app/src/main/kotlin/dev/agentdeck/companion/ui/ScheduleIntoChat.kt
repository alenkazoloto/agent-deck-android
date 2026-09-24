package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileScheduleDependencySelection
import dev.agentdeck.companion.data.AfterRun
import dev.agentdeck.companion.data.AfterSessions
import dev.agentdeck.companion.data.LimitContinuation
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.ScheduleHabit
import dev.agentdeck.companion.data.ScheduleRepeat

/**
 * What a conversation needs to offer the desk's "This chat" schedule (`ScheduleDialog`, ⋯ ›
 * Schedule message, `/schedule`); null when the machine cannot queue a prompt for later.
 */
class ScheduleIntoChatOffer(
    /** The chat's own account, so "At <reset>" is that account's reset, not the machine's active one. */
    val accountId: String?,
    val canRepeat: Boolean,
    /** The chat is running and the machine holds a prompt for its end: adds "When this run finishes" to the pill. */
    val canAfterRun: Boolean = false,
    /** "After sessions finish…" for this chat's project; null when the machine cannot hold a prompt for other sessions. */
    val sources: ScheduleSourcesOffer? = null,
    val onSchedule: (dueAtMs: Long, repeat: ScheduleRepeat?, afterRun: Boolean, dependencies: List<MobileScheduleDependencySelection>) -> Unit,
    /** The last scheduling, which the dialog opens on, and where it stores the next one; shared with the create dialog. */
    val habit: ScheduleHabit = ScheduleHabit(),
    val onHabit: (ScheduleHabit) -> Unit = {},
)

/**
 * The prompt is the composer's own draft, edited in place: it stays there until the machine
 * accepts the schedule, so Cancel, a refusal or a lost link leave it where it was typed. The
 * run settings are the composer's, as a reply would send them, so the dialog does not repeat them.
 */
@Composable
internal fun ScheduleIntoChatDialog(
    offer: ScheduleIntoChatOffer,
    hello: MobileHello?,
    vendor: AgentVendor,
    projectPath: String,
    prompt: String,
    onPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val due = remember { ScheduleDuePick.opening(null, offer.habit) }
    var repeat by remember { mutableStateOf(offer.habit.repeat) }
    val target = NewChatTarget(projectPath, vendor, accountId = offer.accountId)
    // The chat runs on its own account, so only that exact account's reset: [resetFor]'s fallback
    // to the machine's active account would offer another account's reset time.
    val reset = offer.accountId?.let { LimitContinuation.resetOf(hello, vendor, it, LocalNow.current()) }
    var dependencies by remember { mutableStateOf(emptyList<MobileScheduleDependencySelection>()) }
    val picked = due.picked(reset, offer.canAfterRun, sessionsOffered = offer.sources != null)
    val waitsForRun = picked is AfterRun
    val waitsForSessions = picked is AfterSessions
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule into this chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPrompt,
                    placeholder = { Text("What should the agent do?") },
                    modifier = Modifier.fillMaxWidth().testTag("schedule-into-chat-prompt"),
                    maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                )
                ScheduleDueSelector(due, hello, target, reset, runOffered = offer.canAfterRun, sessionsOffered = offer.sources != null)
                if (waitsForSessions && offer.sources != null) {
                    ScheduleSourcesPicker(projectPath, offer.sources, dependencies) { dependencies = it }
                }
                // A prompt that waits has no cadence, so the desk stands the checkbox down; here it goes.
                if (offer.canRepeat && !waitsForRun && !waitsForSessions) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable { repeat = !repeat }.testTag("schedule-into-chat-repeat"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(repeat, onCheckedChange = null)
                        Text(picked.repeatLabel(), Modifier.padding(start = 8.dp))
                    }
                }
                Text(
                    if (waitsForRun) "Runs in this chat once its current run ends, with the composer's run settings."
                    else if (waitsForSessions) "Runs in this chat once the chosen sessions finish, with the composer's run settings."
                    else "Runs in this chat with the composer's run settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = prompt.isNotBlank() && picked.valid && (!waitsForSessions || dependencies.isNotEmpty()),
                onClick = {
                    onDismiss()
                    offer.onHabit(due.remembered(offer.habit, if (offer.canRepeat && !waitsForRun && !waitsForSessions) repeat else offer.habit.repeat))
                    offer.onSchedule(
                        picked.dueAtMs(), picked.repeat().takeIf { repeat && offer.canRepeat && !waitsForRun && !waitsForSessions },
                        waitsForRun, dependencies.takeIf { waitsForSessions }.orEmpty(),
                    )
                },
            ) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        // As LimitContinuationDialog: with the platform's default window a focused text field
        // never lets the dialog settle (ScheduleIntoChatInteractionTest hung until this).
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    )
}
