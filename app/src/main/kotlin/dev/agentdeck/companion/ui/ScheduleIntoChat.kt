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
import androidx.compose.runtime.LaunchedEffect
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
import com.github.claudeagents.core.mobile.MobileAcpSession
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleDependencySelection
import dev.agentdeck.companion.data.AfterRun
import dev.agentdeck.companion.data.AfterSessions
import dev.agentdeck.companion.data.LimitContinuation
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.ScheduleChatForm
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
    /** This chat's own last form, which wins over [habit] when it exists; written on Cancel as well as on Schedule. */
    val form: ScheduleChatForm? = null,
    val onForm: (ScheduleChatForm) -> Unit = {},
) {
    companion object {
        /**
         * Whether [key]'s conversation may schedule a prompt into itself. An ACP chat needs its own capability: a plugin
         * without it refuses the prompt, and a Schedule button that always fails is worse than none.
         */
        fun offered(capabilities: Collection<String>, key: String): Boolean =
            MobileProtocol.Capability.SCHEDULE_CREATE in capabilities &&
                (!MobileAcpSession.isKey(key) || MobileProtocol.Capability.ACP_SCHEDULE in capabilities)
    }
}

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
    // The chat's own form wins over the machine's habit, as the desk's per-chat FormDraft does.
    val opened = offer.form?.habit ?: offer.habit
    val due = remember {
        ScheduleDuePick.opening(null, opened).apply {
            // Picks left unscheduled reopen on "After sessions finish…", as the desk's dialog does.
            if (offer.form?.dependencies?.isNotEmpty() == true && offer.sources != null) afterSessions = true
        }
    }
    var repeat by remember { mutableStateOf(opened.repeat) }
    val target = NewChatTarget(projectPath, vendor, accountId = offer.accountId)
    // The chat runs on its own account, so only that exact account's reset: [resetFor]'s fallback
    // to the machine's active account would offer another account's reset time.
    val reset = offer.accountId?.let { LimitContinuation.resetOf(hello, vendor, it, LocalNow.current()) }
    var dependencies by remember { mutableStateOf(offer.form?.dependencies.orEmpty()) }
    // Sessions that finished or went away since the picks were left are not on the machine's list, and it refuses their ids.
    val listed = offer.sources?.state?.takeIf { it.project == projectPath && !it.loading && it.error == null }?.sources
    LaunchedEffect(listed) {
        if (listed != null) dependencies = dependencies.filter { d -> listed.any { it.id == d.id } }
    }
    val picked = due.picked(reset, offer.canAfterRun, sessionsOffered = offer.sources != null)
    val waitsForRun = picked is AfterRun
    val waitsForSessions = picked is AfterSessions
    val repeats = offer.canRepeat && !waitsForRun && !waitsForSessions
    // The desk keeps a chat's form when its dialog closes, not only when it schedules.
    val dismiss = {
        offer.onForm(ScheduleChatForm(due.remembered(opened, if (repeats) repeat else opened.repeat), dependencies))
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
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
                if (repeats) {
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
                    offer.onHabit(due.remembered(offer.habit, if (repeats) repeat else offer.habit.repeat))
                    // Scheduling consumes the picks: only a Cancel leaves "After sessions finish…" ones for next time.
                    offer.onForm(ScheduleChatForm(due.remembered(opened, if (repeats) repeat else opened.repeat)))
                    offer.onSchedule(
                        picked.dueAtMs(), picked.repeat().takeIf { repeat && repeats },
                        waitsForRun, dependencies.takeIf { waitsForSessions }.orEmpty(),
                    )
                },
            ) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        // As LimitContinuationDialog: with the platform's default window a focused text field
        // never lets the dialog settle (ScheduleIntoChatInteractionTest hung until this).
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    )
}
