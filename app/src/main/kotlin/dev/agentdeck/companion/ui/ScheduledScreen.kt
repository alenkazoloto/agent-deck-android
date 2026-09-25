package dev.agentdeck.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileScheduleDependencySelection
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleEditDetail
import com.github.claudeagents.core.mobile.MobileScheduleEditRequest
import com.github.claudeagents.core.mobile.MobileFollowUpSource
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledFollowUp
import com.github.claudeagents.core.mobile.MobileScheduledOutcome
import com.github.claudeagents.core.mobile.MobileScheduledOutside
import com.github.claudeagents.core.mobile.MobileScheduledRow
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.AfterDelay
import dev.agentdeck.companion.data.AfterReset
import dev.agentdeck.companion.data.AtTime
import dev.agentdeck.companion.data.AfterRun
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.ScheduleDue
import dev.agentdeck.companion.data.ScheduleHabit
import dev.agentdeck.companion.data.ScheduleRepeat
import dev.agentdeck.companion.data.AfterSessions
import dev.agentdeck.companion.data.ScheduleWhen

/**
 * What is queued on the machine, and — for the first time — a way to add to it.
 *
 * The screen had none of the fleet's manners: no pull-to-refresh, **Cancel** and **Cancel all**
 * firing straight through with nothing between the thumb and a lost prompt, and rows that named
 * neither project nor branch, so two prompts from different repos were the same row twice.
 *
 * Weight is the second thing it lacked. Every control was the same button: a create action beside
 * a bulk cancel, and per row a **Cancel** that cannot be undone painted exactly like **Pause**.
 * Destructive is `error` and set apart, safe is quiet, and the one thing the screen wants you to
 * do is the only filled control on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(
    rows: List<MobileScheduledRow>,
    loading: Boolean,
    canCreate: Boolean,
    projects: List<String>,
    /** `/v1/hello`, for the model ladder and accounts the create dialog offers. */
    hello: MobileHello?,
    draft: String,
    onDraft: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreate: (target: NewChatTarget, dueAtMs: Long, repeat: ScheduleRepeat?, dependencies: List<MobileScheduleDependencySelection>) -> Unit,
    onCommand: (action: String, ids: List<String>, announce: String?) -> Unit,
    onEdit: (String) -> Unit = {},
    editId: String? = null,
    editDetail: MobileScheduleEditDetail? = null,
    editDraft: String = "",
    editLoading: Boolean = false,
    editSaving: Boolean = false,
    editError: String? = null,
    onEditDraft: (String) -> Unit = {},
    onEditDismiss: () -> Unit = {},
    onEditSave: (MobileScheduleEditRequest) -> Unit = {},
    /** "After sessions finish" for the create dialog; null when the machine cannot hold a prompt for other sessions. */
    sources: ScheduleSourcesOffer? = null,
    /** Agents the create dialog offers; the fleet's own set, as on New chat. */
    vendors: List<AgentVendor> = listOf(AgentVendor.CLAUDE),
    /**
     * Usage's "Schedule a prompt for …" on a drained plan: the create dialog opens on this agent
     * and account with its reset picked. Kept until the dialog closes, so a rotation reopens it.
     */
    afterReset: NewChatTarget? = null,
    onAfterResetDone: () -> Unit = {},
    /** Runs that already finished, newest first; empty from an older plugin. */
    outcomes: List<MobileScheduledOutcome> = emptyList(),
    onOpenOutcome: (MobileScheduledOutcome) -> Unit = {},
    /** Schedules Claude Code or Codex own, read-only; empty from an older plugin. */
    outside: List<MobileScheduledOutside> = emptyList(),
    /** Follow-ups that were admitted, newest first; empty from an older plugin. */
    followUps: List<MobileScheduledFollowUp> = emptyList(),
    onOpenFollowUpChat: (key: String, vendor: AgentVendor?, projectPath: String?, title: String) -> Unit = { _, _, _, _ -> },
    /** The desk's overlapping-runs switch; null from a machine that has none. */
    allowOverlap: Boolean? = null,
    onAllowOverlap: (Boolean) -> Unit = {},
    /** What the create dialog opens on — the last scheduling — and where it stores the next one. */
    habit: ScheduleHabit = ScheduleHabit(),
    onHabit: (ScheduleHabit) -> Unit = {},
) {
    var cancelling by remember { mutableStateOf<List<MobileScheduledRow>?>(null) }
    var composing by remember { mutableStateOf(false) }
    val empty = rows.isEmpty() && !loading

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rows.isNotEmpty()) {
                // The ids are the ones on screen. A "cancel everything" request would race
                // the list the user is looking at and kill a row that arrived in between.
                DeckIconButton(
                    label = "Cancel all ${rows.size} prompts",
                    icon = DeckIcons.CancelAll,
                    onClick = { cancelling = rows },
                    destructive = true,
                )
            }
            // Only where the machine says it will honour a due time. An older plugin ignores
            // the field and runs the prompt immediately, which is not what "schedule" means.
            // Hidden while the list is empty: the empty state carries this action instead.
            if (canCreate && !empty) {
                DeckIconButton("Schedule a prompt", Icons.Filled.Add, onClick = { composing = true })
            }
        }

        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                when {
                    // A first load used to jump from a blank page to a full list.
                    rows.isEmpty() && loading -> item(key = "skeleton") { ScheduledSkeleton() }
                    empty -> item(key = "empty") { NothingScheduled(canCreate) { composing = true } }
                    else -> items(rows, key = { it.id }) { row ->
                        ScheduledRow(row, onCommand, onCancel = { cancelling = listOf(row) }, onEdit = { onEdit(row.id) })
                    }
                }
                // Only where a repeat exists to be held back, or the switch is already off and must
                // stay reachable: a permanent setting under a list of one-off prompts is noise.
                if (allowOverlap != null && (!allowOverlap || rows.any { it.repeating })) {
                    item(key = "overlap") { OverlapRow(allowOverlap, onAllowOverlap) }
                }
                if (outcomes.isNotEmpty()) {
                    item(key = "outcomes-heading") {
                        Text(
                            "Recent runs",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                                .semantics { heading() },
                        )
                    }
                    items(outcomes, key = { "outcome-${it.taskId}-${it.finishedAtMs}" }) { outcome ->
                        OutcomeRow(outcome, onOpen = { onOpenOutcome(outcome) })
                    }
                }
                if (followUps.isNotEmpty()) {
                    item(key = "follow-ups-heading") {
                        Text(
                            "Follow-ups",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                                .semantics { heading() },
                        )
                    }
                    items(followUps, key = { "follow-up-${it.id}" }) { followUp ->
                        FollowUpRow(followUp, onOpenFollowUpChat)
                    }
                }
                if (outside.isNotEmpty()) {
                    item(key = "outside-heading") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
                            Text(
                                "Created outside the IDE",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                "Change them where they were created.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(outside, key = { "outside-${it.id}-${it.scope}" }) { OutsideRow(it) }
                }
            }
        }
    }

    if (editId != null) {
        ScheduleEditDialog(
            id = editId, detail = editDetail, savedDraft = editDraft,
            loading = editLoading, saving = editSaving, error = editError,
            onDraft = onEditDraft, onDismiss = onEditDismiss,
            onRetry = { onEdit(editId) }, onSave = onEditSave,
            canRetry = hello == null || com.github.claudeagents.core.mobile.MobileProtocol.Capability.SCHEDULE_EDIT in hello.capabilities,
            hello = hello,
        )
    }

    cancelling?.let { doomed ->
        ConfirmCancel(doomed, onDismiss = { cancelling = null }) {
            cancelling = null
            onCommand(
                MobileScheduledCommand.CANCEL,
                doomed.map { it.id },
                if (doomed.size == 1) "Cancelled 1 prompt" else "Cancelled ${doomed.size} prompts",
            )
        }
    }

    val preset = afterReset?.takeIf { canCreate }
    if (composing || preset != null) {
        val close = {
            composing = false
            if (preset != null) onAfterResetDone()
        }
        // Keyed so a preset arriving over an open blank dialog replaces its picks.
        key(preset) {
            ScheduleDialog(
                projects = projects,
                vendors = vendors,
                hello = hello,
                draft = draft,
                onDraft = onDraft,
                habit = habit,
                onHabit = onHabit,
                onDismiss = close,
                canRepeat = hello != null && MobileProtocol.Capability.SCHEDULE_REPEAT in hello.capabilities,
                onCreate = { target, dueAtMs, repeat, dependencies ->
                    close()
                    onCreate(target, dueAtMs, repeat, dependencies)
                },
                preset = preset,
                sources = sources,
            )
        }
    }
}

/**
 * The gate in front of the one irreversible act here.
 *
 * There is no undo for a cancel: the row is gone from the machine's queue and the plugin mints
 * no way to put it back. So the prompt itself is quoted in the dialog — the thing the user is
 * about to lose is the text they wrote, and a dialog that says "Cancel 1 prompt?" without
 * showing which one is asking them to confirm from memory.
 */
@Composable
private fun ConfirmCancel(
    rows: List<MobileScheduledRow>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rows.size == 1) "Cancel this prompt?" else "Cancel ${rows.size} prompts?") },
        text = {
            Column {
                Text("Cancelling removes them from the machine's queue. This cannot be undone.")
                rows.take(3).forEach { row ->
                    Text(
                        "· ${row.prompt.ifBlank { "(empty prompt)" }}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (rows.size > 3) {
                    Text(
                        "and ${rows.size - 3} more",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Cancel them") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep them") } },
    )
}

/**
 * The thing you remember while away from the desk.
 *
 * Relative choices rather than a date-and-time picker: the prompts a user queues from a phone
 * are "when I am back at it", not "at 14:37 on the 9th", and a wheel picker on a phone to
 * express "tomorrow morning" is four gestures for a decision that is one.
 */
@Composable
private fun ScheduleDialog(
    projects: List<String>,
    vendors: List<AgentVendor>,
    hello: MobileHello?,
    draft: String,
    onDraft: (String) -> Unit,
    habit: ScheduleHabit,
    onHabit: (ScheduleHabit) -> Unit,
    onDismiss: () -> Unit,
    canRepeat: Boolean,
    onCreate: (NewChatTarget, Long, ScheduleRepeat?, List<MobileScheduleDependencySelection>) -> Unit,
    preset: NewChatTarget? = null,
    sources: ScheduleSourcesOffer? = null,
) {
    val due = remember { ScheduleDuePick.opening(preset, habit) }
    var repeat by remember { mutableStateOf(habit.repeat) }
    // Model and account null are "whatever the machine is set to", which is what this dialog
    // always sent for the model; the account resolves to the machine's active one on send.
    var target by remember { mutableStateOf(openingTarget(projects, vendors, preset, habit.target)) }
    val nowMs = LocalNow.current()
    // Ids belong to one project, so choosing another drops the picks made in the last.
    var dependencies by remember(target.projectPath) { mutableStateOf(emptyList<MobileScheduleDependencySelection>()) }
    val picked = due.picked(resetFor(hello, target, nowMs), sessionsOffered = sources != null)
    val waitsForSessions = picked is AfterSessions
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule a prompt") },
        text = {
            Column {
                if (projects.isEmpty()) {
                    Text("No project is open on this machine, so there is nowhere to run a prompt.")
                } else {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraft,
                        placeholder = { Text("What should the agent do?") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        shape = RoundedCornerShape(18.dp),
                    )
                    Column(
                        Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Shown at one project too: where the prompt is about to run is state
                        // the user acts on, and hiding it left them guessing at the machine.
                        // Stacked rather than side by side because both pills carry a name and
                        // a dialog at font scale 1.3 has no room for two.
                        ScheduleTargetPickers(
                            target = target,
                            projects = projects,
                            vendors = vendors,
                            hello = hello,
                            onTarget = { target = it },
                        ) {
                            ScheduleDueSelector(due, hello, target, sessionsOffered = sources != null)
                        }
                        if (waitsForSessions && sources != null) {
                            ScheduleSourcesPicker(target.projectPath, sources, dependencies) { dependencies = it }
                        }
                        // A prompt that waits for sessions has no cadence, so the desk stands the checkbox down; here it goes.
                        if (canRepeat && !waitsForSessions) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable { repeat = !repeat }.testTag("schedule-repeat"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(repeat, onCheckedChange = null)
                                Text(picked.repeatLabel(), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = projects.isNotEmpty() && draft.isNotBlank() && picked.valid && (!waitsForSessions || dependencies.isNotEmpty()),
                onClick = {
                    // Only a schedule the machine is about to be asked for is a habit; Cancel leaves it as it was.
                    onHabit(due.remembered(habit, if (canRepeat && !waitsForSessions) repeat else habit.repeat, target))
                    onCreate(target, picked.dueAtMs(), picked.repeat().takeIf { repeat && canRepeat && !waitsForSessions },
                        dependencies.takeIf { waitsForSessions }.orEmpty())
                },
            ) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Where the create dialog opens: Usage's [preset] agent and account, else the [remembered] target
 * while its agent is still offered, else Claude when offered — what this dialog always sent. Model
 * and account null are "whatever the machine is set to"; the account resolves to the machine's
 * active one on send. A remembered project the IDE has since closed gives way to the first open one.
 */
internal fun openingTarget(
    projects: List<String>,
    vendors: List<AgentVendor>,
    preset: NewChatTarget?,
    remembered: NewChatTarget? = null,
): NewChatTarget {
    val project = projects.firstOrNull().orEmpty()
    preset?.let { return it.copy(projectPath = project) }
    remembered?.takeIf { it.vendor in vendors }?.let { return it.copy(projectPath = it.projectPath.takeIf { p -> p in projects } ?: project) }
    val vendor = vendors.firstOrNull { it == AgentVendor.CLAUDE } ?: vendors.firstOrNull() ?: AgentVendor.CLAUDE
    return NewChatTarget(project, vendor)
}

/** The create dialog's When pick; hoisted so a test can drive the pill without the dialog window. */
internal class ScheduleDuePick {
    var relative by mutableStateOf(ScheduleWhen.entries.first())
    var atReset by mutableStateOf(false)
    var afterRun by mutableStateOf(false)
    var afterSessions by mutableStateOf(false)

    /** The free-typed picks ("In N …", "At HH:mm"); their fields live here so a chosen pill keeps what was typed. */
    var custom by mutableStateOf<CustomDue?>(null)
    var delayText by mutableStateOf("1")
    var delayMinutes by mutableStateOf(false)
    var clockText by mutableStateOf("")

    enum class CustomDue { IN, AT }

    fun delay() = AfterDelay(delayText.toIntOrNull() ?: 0, delayMinutes)
    fun clock() = AtTime(clockText)

    companion object {
        /**
         * Usage's offer opens on the reset (the pill falls back to "In an hour" if that account has
         * none); otherwise the pill opens on the [habit]'s pick and keeps its typed values.
         */
        fun opening(preset: NewChatTarget?, habit: ScheduleHabit = ScheduleHabit()) = ScheduleDuePick().apply {
            relative = habit.relative
            delayText = habit.inAmount.toString()
            delayMinutes = habit.inMinutes
            clockText = habit.atTime
            if (preset != null) {
                atReset = true
                return@apply
            }
            when (habit.due) {
                ScheduleHabit.Due.IN -> custom = CustomDue.IN
                ScheduleHabit.Due.AT -> custom = CustomDue.AT
                ScheduleHabit.Due.AT_RESET -> atReset = true
                ScheduleHabit.Due.AFTER_RUN -> afterRun = true
                ScheduleHabit.Due.RELATIVE, null -> Unit
            }
        }
    }

    /**
     * The habit after scheduling with this pick. A schedule that waits for sessions is not one: its
     * sources are gone by next time, so the timing (and the cadence, which it has none of) stays as it was.
     */
    fun remembered(previous: ScheduleHabit, repeat: Boolean, target: NewChatTarget? = previous.target): ScheduleHabit {
        if (afterSessions) return previous.copy(target = target)
        return ScheduleHabit(
            due = when {
                afterRun -> ScheduleHabit.Due.AFTER_RUN
                atReset -> ScheduleHabit.Due.AT_RESET
                custom == CustomDue.IN -> ScheduleHabit.Due.IN
                custom == CustomDue.AT -> ScheduleHabit.Due.AT
                else -> ScheduleHabit.Due.RELATIVE
            },
            relative = relative,
            inAmount = delayText.toIntOrNull()?.coerceIn(1, AfterDelay.MAX_AMOUNT) ?: previous.inAmount,
            inMinutes = delayMinutes,
            atTime = clockText.trim().takeIf { AtTime(it).valid } ?: previous.atTime,
            repeat = repeat,
            target = target,
        )
    }

    /** A reset pick whose account no longer has a known reset falls back to the relative one it replaced. */
    fun picked(reset: AfterReset?, runOffered: Boolean = false, sessionsOffered: Boolean = false): ScheduleDue =
        AfterSessions.takeIf { afterSessions && sessionsOffered } ?: AfterRun.takeIf { afterRun && runOffered }
            ?: reset?.takeIf { atReset }
            ?: when (custom) { CustomDue.IN -> delay(); CustomDue.AT -> clock(); null -> relative }
}

/**
 * The reset of the account the request will name — the pick, else the machine's active one — so
 * switching agent or account moves the choice with it, as the desk's dialog does.
 */
internal fun resetFor(hello: MobileHello?, target: NewChatTarget, nowMs: Long): AfterReset? {
    val id = NewChat.accountFor(hello, target) ?: return null
    return AfterReset.of(hello?.accounts?.get(target.vendor)?.firstOrNull { it.id == id }, nowMs)
}

/**
 * The relative choices, then the reset one while a reset is known. Its time leads because the pill
 * stops at 160 dp and the moment is what the reader is choosing.
 */
@Composable
internal fun ScheduleDueSelector(
    pick: ScheduleDuePick,
    hello: MobileHello?,
    target: NewChatTarget,
    reset: AfterReset? = resetFor(hello, target, LocalNow.current()),
    runOffered: Boolean = false,
    sessionsOffered: Boolean = false,
) {
    val nowMs = LocalNow.current()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Selector(
            options = ScheduleWhen.entries.map { SelectorOption<ScheduleDue>(it.label, it) } +
                listOf(SelectorOption<ScheduleDue>(pick.delay().label, pick.delay()), SelectorOption<ScheduleDue>(pick.clock().label, pick.clock())) +
                listOfNotNull(reset?.let { SelectorOption<ScheduleDue>("At ${Times.clock(it.dueAtMs(), nowMs)}, ${it.label}", it) }) +
                listOfNotNull(AfterRun.takeIf { runOffered }?.let { SelectorOption<ScheduleDue>("When this run finishes", it) }) +
                listOfNotNull(AfterSessions.takeIf { sessionsOffered }?.let { SelectorOption<ScheduleDue>("After sessions finish…", it) }),
            selected = pick.picked(reset, runOffered, sessionsOffered),
            onSelect = {
                pick.atReset = it is AfterReset
                pick.afterRun = it is AfterRun
                pick.afterSessions = it is AfterSessions
                pick.custom = when (it) {
                    is AfterDelay -> ScheduleDuePick.CustomDue.IN
                    is AtTime -> ScheduleDuePick.CustomDue.AT
                    else -> null
                }
                if (it is ScheduleWhen) pick.relative = it
            },
        )
        ScheduleDueFields(pick)
    }
}

/**
 * Where a scheduled prompt runs: project, agent, account, then [between] (the due-time pill),
 * then model. Split from the dialog so the picks can be driven without a dialog window, which
 * Robolectric never lets settle while it holds a text field.
 */
@Composable
internal fun ScheduleTargetPickers(
    target: NewChatTarget,
    projects: List<String>,
    vendors: List<AgentVendor>,
    hello: MobileHello?,
    onTarget: (NewChatTarget) -> Unit,
    between: @Composable () -> Unit = {},
) {
    Selector(
        options = projects.map { SelectorOption(it.substringAfterLast('/'), it) },
        selected = target.projectPath,
        onSelect = { onTarget(target.copy(projectPath = it)) },
        prefix = "Project",
    )
    AgentSelector(target, vendors, hello, onTarget, prefix = "Agent")
    // ACP has no account, model, effort, mode or toggles: those pickers would name a Claude ladder.
    val runPicks = NewChat.acpAgentFor(hello, target) == null
    if (runPicks) AccountSelector(
        hello = hello,
        vendor = target.vendor,
        picked = target.accountId,
        onSelect = { onTarget(target.copy(accountId = it)) },
        prefix = "Account",
    )
    between()
    if (!runPicks) return
    // The same control the new-chat screen draws, over the same field of the same request. Two
    // pickers over one wire field is how they drift.
    ModelSelector(
        hello = hello,
        vendor = target.vendor,
        selected = target.model,
        onSelect = { onTarget(target.copy(model = it)) },
    )
    EffortSelector(
        hello = hello,
        vendor = target.vendor,
        selected = target.effort,
        onSelect = { onTarget(target.copy(effort = it)) },
    )
    ModeSelector(
        hello = hello,
        vendor = target.vendor,
        selected = target.permissionMode,
        onSelect = { onTarget(target.copy(permissionMode = it)) },
    )
    RunToggleSelector(hello, target.vendor, "Fast", target.fastMode, { onTarget(target.copy(fastMode = it)) }, vendors = RunOptionRows.FAST_VENDORS)
    RunToggleSelector(hello, target.vendor, "Thinking", target.thinking, { onTarget(target.copy(thinking = it)) })
}

/**
 * A run that finished, in the desk's words. Exiting green is not the task having succeeded, so
 * the line claims only what the exit told us and the row's value is the chat it wrote — one tap
 * to read it. A run whose session the machine does not list has nothing to open and says so.
 */
@Composable
private fun OutcomeRow(outcome: MobileScheduledOutcome, onOpen: () -> Unit) {
    val nowMs = LocalNow.current()
    val opens = outcome.key != null
    val status = when {
        outcome.failed -> "Failed — ${outcome.detail ?: "no detail reported"}"
        opens -> "Ran"
        else -> "Ran — no chat recorded"
    }
    Column(
        Modifier.fillMaxWidth()
            .then(if (opens) Modifier.clickable(onClickLabel = "Open the chat this run wrote", onClick = onOpen) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("scheduled-outcome"),
    ) {
        Text(
            outcome.prompt.ifBlank { "(empty prompt)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = if (outcome.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            listOfNotNull(
                outcome.projectPath?.takeIf { it.isNotBlank() }?.substringAfterLast('/'),
                Times.clock(outcome.finishedAtMs, nowMs),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * An admitted follow-up, closed like the desk's fold: what it came to and how many sessions it
 * waited on. Open, it lists each source with its state, and a source or the result the machine
 * can locate opens that chat. One the machine does not list is plain text, not a dead tap.
 */
@Composable
private fun FollowUpRow(
    followUp: MobileScheduledFollowUp,
    onOpen: (key: String, vendor: AgentVendor?, projectPath: String?, title: String) -> Unit,
) {
    var expanded by rememberSaveable(followUp.id) { mutableStateOf(false) }
    val count = followUp.sources.size
    Column(Modifier.fillMaxWidth().testTag("scheduled-follow-up")) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Hide the sources" else "Show the sources") { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Follow-up · ${followUp.status} · $count ${if (count == 1) "source" else "sources"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                followUp.projectPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            followUp.resultKey?.let { key ->
                FollowUpLine("Open result", null, onClick = { onOpen(key, followUp.resultVendor, followUp.projectPath, "Follow-up") })
            }
            followUp.sources.forEach { source -> FollowUpSourceLine(source, followUp.projectPath, onOpen) }
        }
    }
}

@Composable
private fun FollowUpSourceLine(
    source: MobileFollowUpSource,
    projectPath: String?,
    onOpen: (key: String, vendor: AgentVendor?, projectPath: String?, title: String) -> Unit,
) {
    val key = source.key
    FollowUpLine(
        "${source.label} · ${source.state}${if (source.context) " · context included" else ""}",
        opens = if (key != null) "Open this source chat" else null,
        onClick = key?.let { { onOpen(it, source.vendor, projectPath, source.label) } },
    )
}

@Composable
private fun FollowUpLine(text: String, opens: String?, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = opens ?: "Open the chat this follow-up wrote", onClick = onClick) else Modifier)
            .heightIn(min = 40.dp)
            .padding(start = 32.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)
            .testTag("scheduled-follow-up-line"),
    )
}

/**
 * A schedule another tool owns: nothing to tap because nothing here can change it. The line under
 * the prompt is the desk's Source / When / Scope columns, "When" first because whether it fires
 * at all ("Paused — …") is the answer a reader came for.
 */
@Composable
private fun OutsideRow(entry: MobileScheduledOutside) {
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("scheduled-outside"),
    ) {
        Text(
            entry.prompt.ifBlank { "(no prompt)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            entry.schedule,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            listOfNotNull(entry.source, entry.scope, entry.note).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ScheduledRow(
    row: MobileScheduledRow,
    onCommand: (String, List<String>, String?) -> Unit,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
) {
    // "Due today" is the reader's question, so this one is the phone's clock — unlike a
    // fleet row's age, which is the machine's stamp measured against the machine's snapshot.
    val nowMs = LocalNow.current()
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        .clickable(onClickLabel = "Edit scheduled prompt", onClick = onEdit)) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.state == MobileScheduledRow.RUNNING) RunningIndicator()
                    StatusChip(row.state)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        row.prompt.ifBlank { "(empty prompt)" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildList {
                            // Which repo this is for, first: two prompts from different projects
                            // were the same row twice, and the project is what tells them apart.
                            row.projectPath?.takeIf { it.isNotBlank() }
                                ?.let { add(it.substringAfterLast('/')) }
                            // The machine's cadence names it; an older plugin sends only the flag.
                            if (row.repeating) add(row.cadence ?: "repeating")
                            // A row waiting on a run or on sessions has no moment: `dueAtMs` is
                            // ignored until the wait clears, so "due <now>" would be a lie. Otherwise
                            // it is already the *next* occurrence for a repeating row: the machine
                            // advances it as the row fires, so it is never a moment that has passed.
                            add(row.waiting ?: "due ${Times.clock(row.dueAtMs, nowMs)}")
                            row.agent?.let(::add)
                            // A row bound to a conversation continues it; one without starts a chat.
                            if (row.sessionId.isNullOrBlank()) add("new chat")
                            // A run that went fine is metadata; the one that did not gets its
                            // own line below, because it is the only one that changes a decision.
                            if (row.lastRunAtMs > 0 && !row.lastRunFailed) {
                                add("ran ${Times.clock(row.lastRunAtMs, nowMs)}")
                            }
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // The past tense the wire could not carry until MP-10. Without it a nightly
                    // prompt that failed reads exactly like one that has never run — both say
                    // `queued`, because that is the only tense `state` has.
                    if (row.lastRunAtMs > 0 && row.lastRunFailed) {
                        Text(
                            "Last run failed · ${Times.clock(row.lastRunAtMs, nowMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.state == MobileScheduledRow.PAUSED) {
                    DeckIconButton("Resume", Icons.Filled.PlayArrow,
                        onClick = { onCommand(MobileScheduledCommand.RESUME, listOf(row.id), "Resumed") })
                } else {
                    DeckIconButton("Pause", DeckIcons.Pause,
                        onClick = { onCommand(MobileScheduledCommand.PAUSE, listOf(row.id), "Paused") })
                }
                DeckIconButton("Run now", DeckIcons.RunNow,
                    onClick = { onCommand(MobileScheduledCommand.RUN_NOW, listOf(row.id), "Running it now") })
                Spacer(Modifier.weight(1f))
                DeckIconButton("Cancel this prompt", Icons.Filled.Delete,
                    onClick = onCancel, destructive = true)
            }
        }
    }
}

/**
 * The row's state, rendered as a state instead of a word inside a sentence.
 *
 * The vocabulary is the desktop chip's — a phone that renamed `running`/`paused`/`queued` would
 * be a second product — so the *word* is what separates two rows, and the tint only reinforces
 * it. A string this build does not know is drawn as sent, outlined rather than tinted: the
 * plugin on the other end can be newer than the phone, and dropping the word would be a lie.
 */
@Composable
private fun StatusChip(state: String) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        MobileScheduledRow.RUNNING -> scheme.primaryContainer to scheme.onPrimaryContainer
        MobileScheduledRow.QUEUED -> scheme.secondaryContainer to scheme.onSecondaryContainer
        MobileScheduledRow.PAUSED -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        else -> Color.Transparent to scheme.onSurfaceVariant
    }
    Surface(
        // A Surface rather than an AssistChip: a chip's height is fixed at 32 dp and this label
        // has to grow with the user's font scale.
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = if (container == Color.Transparent) BorderStroke(1.dp, scheme.outline) else null,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Status: $state" },
    ) {
        Text(
            state,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * An empty queue, and the one gesture that fills it.
 *
 * Where the machine cannot take a scheduled prompt the button is not offered and the reason is,
 * because "nothing here" and "this machine cannot do this" are not the same page.
 */
@Composable
private fun NothingScheduled(canCreate: Boolean, onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.DateRange,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            "Nothing is scheduled on this machine.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            if (canCreate) "Queue a prompt and the machine runs it when it comes due."
            else "Scheduling from the phone needs a newer plugin on this machine.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (canCreate) {
            DeckIconButton("Schedule a prompt", Icons.Filled.Add, onClick = onCreate)
        }
    }
}

/**
 * The shape of the list that is coming, while it is coming.
 *
 * Deliberately still: a shimmer is an infinite animation, and a Roborazzi capture waits for the
 * composition to go idle — it would hang the golden suite rather than fail it.
 */
@Composable
private fun ScheduledSkeleton() {
    Column(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Loading scheduled prompts"
        },
    ) {
        repeat(SKELETON_ROWS) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(
                    Modifier.padding(12.dp).heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SkeletonBar(Modifier.width(64.dp), 22.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBar(Modifier.fillMaxWidth(0.9f), 16.dp)
                        SkeletonBar(Modifier.fillMaxWidth(0.55f), 12.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier, height: Dp) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)),
    )
}

/** Enough to read as a list and not as one row that failed to load. */
private const val SKELETON_ROWS = 3

/**
 * Settings › Scheduled's "Start the next run even if the previous one is still going". Off, a
 * repeat shorter than its run stays overdue with nothing saying why; the row names that.
 */
@Composable
private fun OverlapRow(allow: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Switch) { onChange(!allow) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("schedule-overlap"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Start the next run even if the previous one is still going", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (allow) "A repeat shorter than its run fires on time." else "A repeat waits for its previous run to finish.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // `onCheckedChange = null`: the row is the control, a 48 dp target that announces once.
        Switch(checked = allow, onCheckedChange = null)
    }
}
