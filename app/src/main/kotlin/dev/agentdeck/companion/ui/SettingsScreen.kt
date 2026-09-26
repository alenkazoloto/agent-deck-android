package dev.agentdeck.companion.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import com.github.claudeagents.core.mobile.MobileBadges
import com.github.claudeagents.core.mobile.MobileChatAutoHide
import com.github.claudeagents.core.mobile.MobileExtensions
import com.github.claudeagents.core.mobile.MobileAcpAgentAction
import com.github.claudeagents.core.mobile.MobileAcpAgents
import com.github.claudeagents.core.mobile.MobileMcpHealth
import com.github.claudeagents.core.mobile.MobileMcpAdd
import com.github.claudeagents.core.mobile.MobileMcpRemove
import com.github.claudeagents.core.mobile.MobileMcpServers
import com.github.claudeagents.core.mobile.MobileOrchestration
import com.github.claudeagents.core.mobile.MobileWorkflowRun
import com.github.claudeagents.core.mobile.MobilePluginToggle
import com.github.claudeagents.core.mobile.MobilePluginUninstall
import com.github.claudeagents.core.mobile.MobileMarketplaceChange
import com.github.claudeagents.core.mobile.MobilePluginUpdate
import com.github.claudeagents.core.mobile.MobileSkillsList
import com.github.claudeagents.core.mobile.MobileProtocol
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.DeckState
import dev.agentdeck.companion.LogSend
import dev.agentdeck.companion.PushState
import dev.agentdeck.companion.Link
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.AppUpdate
import dev.agentdeck.companion.data.HostReach
import dev.agentdeck.companion.data.NotifyTrigger
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.ThemeChoice
import dev.agentdeck.companion.notify.DeckNotifications

/**
 * The screen that did not exist.
 *
 * The overflow menu held exactly one item, Unpair, so there was nowhere to see which machine
 * answered, on what address, when it last said anything, which app this is — or to turn a
 * single notification off. Every section here answers a question a user actually asks; nothing
 * here is a toggle for a signal the wire does not carry, which is why the notification list
 * has three rows and not the six the plan sketched.
 */
@Composable
fun SettingsScreen(
    state: DeckState,
    onSettings: (AppSettings) -> Unit,
    onSwitchMachine: (String) -> Unit,
    onAddMachine: () -> Unit,
    onUnpair: () -> Unit,
    onRefreshHello: () -> Unit,
    onRefreshPush: () -> Unit,
    onChoosePush: (String?) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onReleasePage: () -> Unit,
    onRetryQueued: (String) -> Unit = {},
    onEditQueued: (String) -> Unit = {},
    onDiscardQueued: (String) -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onLoadOrchestration: suspend () -> MobileOrchestration? = { null },
    onOpenWorkflowChat: (MobileWorkflowRun) -> Unit = {},
    onLoadBadges: suspend () -> MobileBadges? = { null },
    onLoadExtensions: suspend () -> MobileExtensions? = { null },
    onLoadAcpAgents: suspend () -> MobileAcpAgents? = { null },
    onActOnAcpAgent: suspend (MobileAcpAgentAction) -> String? = { null },
    onLoadMcpServers: suspend (String?) -> MobileMcpServers? = { null },
    onCheckMcpHealth: suspend (String?) -> MobileMcpHealth? = { null },
    onRemoveMcpServer: suspend (MobileMcpRemove) -> String? = { null },
    onAddMcpServer: suspend (MobileMcpAdd) -> String? = { null },
    onLoadSkills: suspend (String?) -> MobileSkillsList? = { null },
    onTogglePlugin: suspend (MobilePluginToggle) -> String? = { null },
    onUninstallPlugin: suspend (MobilePluginUninstall) -> String? = { null },
    onUpdatePlugin: suspend (MobilePluginUpdate) -> String? = { null },
    onRefreshMarketplace: suspend (MobileMarketplaceChange) -> String? = { null },
    onRemoveMarketplace: suspend (MobileMarketplaceChange) -> String? = { null },
    onSkillState: suspend (com.github.claudeagents.core.mobile.MobileSkillState) -> String? = { null },
    onCopySkill: suspend (com.github.claudeagents.core.mobile.MobileSkillCopy) -> String? = { null },
    onCreateSkill: suspend (com.github.claudeagents.core.mobile.MobileSkillCreate) -> String? = { null },
    onEditSkillFile: (project: String, row: com.github.claudeagents.core.mobile.MobileSkillRow) -> Unit = { _, _ -> },
    hasSkillFileDraft: (project: String, row: com.github.claudeagents.core.mobile.MobileSkillRow) -> Boolean = { _, _ -> false },
    onOpenMemory: () -> Unit = {},
    preview: PreviewLink? = null,
    onRefreshKeepAwake: () -> Unit = {},
    onKeepAwake: (Boolean) -> Unit = {},
    onHoldForReview: (Boolean) -> Unit = {},
    onRefreshPromptSuggestions: () -> Unit = {},
    onPromptSuggestions: (Boolean) -> Unit = {},
    onRefreshRestrictedMode: () -> Unit = {},
    onRestrictedMode: (Boolean) -> Unit = {},
    onRefreshQuestionsKeepWorking: () -> Unit = {},
    onQuestionsKeepWorking: (Boolean) -> Unit = {},
    onRefreshContinueInterrupted: () -> Unit = {},
    onContinueInterrupted: (Boolean) -> Unit = {},
    onRefreshChatAutoHide: () -> Unit = {},
    onChatAutoHide: (Int) -> Unit = {},
    onRefreshAutoReview: () -> Unit = {},
    onAutoReview: (com.github.claudeagents.core.mobile.MobileAutoReview.Change) -> Unit = {},
    onRefreshNewChatDefaults: () -> Unit = {},
    onNewChatMode: (String) -> Unit = {},
    onSendLogs: () -> Unit = {},
    onShareLogs: () -> Unit = {},
    initialTab: SettingsTab = SettingsTab.Machine,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(state.machine?.id) { onRefreshHello() }
    // After hello, not beside it: whether the *machine* will send a push is one of the four
    // facts this screen reports, and it is the only one that comes off the wire.
    LaunchedEffect(state.machine?.id, state.hello) { onRefreshPush() }
    // The same gate as the push read above: the switch is asked for once hello says the machine has it.
    LaunchedEffect(state.machine?.id, state.hello) {
        onRefreshKeepAwake()
        onRefreshPromptSuggestions()
        onRefreshRestrictedMode()
        onRefreshQuestionsKeepWorking()
        onRefreshContinueInterrupted()
        onRefreshChatAutoHide()
        onRefreshAutoReview()
    }
    LaunchedEffect(state.machine?.id, state.hello) { onRefreshNewChatDefaults() }

    Column(Modifier.fillMaxSize()) {
        // Scrollable: at the largest font "Machine" clipped to "Machi…" in an even four-way split.
        PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            SettingsTab.entries.forEach { entry ->
                Tab(
                    selected = entry == tab,
                    onClick = { tab = entry },
                    text = { Text(entry.label, maxLines = 1) },
                )
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            when (tab) {
                SettingsTab.Machine -> {
                    MachineSection(state, onSwitchMachine, onAddMachine, onUnpair, onKeepAwake, onHoldForReview, onPromptSuggestions, onRestrictedMode, onQuestionsKeepWorking, onContinueInterrupted, onChatAutoHide, onAutoReview, onNewChatMode)
                    OutgoingSection(state, onRetryQueued, onEditQueued, onDiscardQueued)
                    UsageSection(state, onOpenUsage)
                    OrchestrationSection(state, onLoadOrchestration, onOpenWorkflowChat)
                    ResourcesSection(state, onLoadBadges, onLoadExtensions, onLoadAcpAgents, onActOnAcpAgent, onLoadMcpServers, onCheckMcpHealth, onRemoveMcpServer, onAddMcpServer, onLoadSkills, onTogglePlugin, onUninstallPlugin, onUpdatePlugin, onRefreshMarketplace, onRemoveMarketplace, onSkillState, onCopySkill, onCreateSkill, onEditSkillFile, hasSkillFileDraft, onOpenMemory, preview)
                }
                SettingsTab.Alerts -> NotificationSection(state, context, onSettings, onChoosePush)
                SettingsTab.App -> {
                    ConnectionSection(state, onSettings)
                    ReadingSection(state, onSettings)
                    WritingSection(state, onSettings)
                    AppearanceSection(state, onSettings)
                }
                SettingsTab.About -> {
                    AboutSection(
                        state = state,
                        onSettings = onSettings,
                        onCheckUpdate = onCheckUpdate,
                        onDownloadUpdate = onDownloadUpdate,
                        onInstallUpdate = onInstallUpdate,
                        onReleasePage = onReleasePage,
                    )
                    DiagnosticsSection(state)
                    LogsSection(state, onSendLogs, onShareLogs)
                }
            }
        }
    }
}

/**
 * The page's four tabs, each a question a reader arrives with: which machine and what is waiting
 * on it, what may interrupt me, how should the app look and behave, and what is this build.
 */
enum class SettingsTab(val label: String) {
    Machine("Machine"),
    Alerts("Alerts"),
    App("App"),
    About("About"),
}

// ---- sections ---------------------------------------------------------------------------

/**
 * Instructions this phone has taken on and not yet delivered — the whole queue, where the
 * composer's chip shows only the conversation in front of the reader.
 *
 * The section is absent while the queue is empty, which is the ordinary state: a permanent
 * "Outgoing: none" card is a status nobody asked for. Retry / Edit / Discard appear on a parked
 * item alone; an item merely waiting for the link is the app working, not a decision.
 */
@Composable
private fun OutgoingSection(
    state: DeckState,
    onRetry: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    if (state.outgoing.isEmpty) return
    Section("Outgoing") {
        state.outgoing.items.forEach { item ->
            row {
                ListItem(
                    headlineContent = { Text(item.label.ifBlank { "New chat" }) },
                    supportingContent = {
                        Column {
                            Text(item.prompt.lineSequence().first().take(120))
                            Text(
                                if (item.parked) {
                                    item.lastError ?: "Not delivered. Retry, edit or discard it."
                                } else if (item.clientMessageId == state.delivering) {
                                    "Sending…"
                                } else {
                                    "Waiting for ${state.machine?.machineName?.ifBlank { null } ?: "the machine"}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.parked) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (item.parked) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onRetry(item.clientMessageId) }) { Text("Retry") }
                                    TextButton(onClick = { onEdit(item.clientMessageId) }) { Text("Edit") }
                                    TextButton(onClick = { onDiscard(item.clientMessageId) }) { Text("Discard") }
                                }
                            }
                        }
                    },
                    leadingContent = { RowIcon(Icons.AutoMirrored.Filled.Send) },
                    colors = rowColors(),
                    modifier = Modifier.heightIn(min = MIN_TARGET),
                )
            }
        }
    }
}

@Composable
private fun MachineSection(
    state: DeckState,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onUnpair: () -> Unit,
    onKeepAwake: (Boolean) -> Unit,
    onHoldForReview: (Boolean) -> Unit,
    onPromptSuggestions: (Boolean) -> Unit,
    onRestrictedMode: (Boolean) -> Unit,
    onQuestionsKeepWorking: (Boolean) -> Unit,
    onContinueInterrupted: (Boolean) -> Unit,
    onChatAutoHide: (Int) -> Unit,
    onAutoReview: (com.github.claudeagents.core.mobile.MobileAutoReview.Change) -> Unit,
    onNewChatMode: (String) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val machine = state.machine
    // Hoisted: the row list is built outside composition, and the reader's clock is a
    // composition-local read.
    val now = LocalNow.current()
    Section("Machine") {
        if (machine == null) {
            detail(Icons.Filled.Info, "Nothing paired", "Pair a machine to see its agents here.")
        } else {
            // More than one is the only case where *which* machine is a question, so the list
            // appears only then; a single pairing shows its own details and nothing else.
            if (state.machines.size > 1) {
                state.machines.forEach { other ->
                    row { MachineRow(other, active = other.id == machine.id) { onSwitch(other.id) } }
                }
            }
            detail(Icons.Filled.AccountBox, "Name", machine.machineName.ifBlank { "(unnamed)" })
            val answered = machine.preferredHost ?: machine.hosts.firstOrNull().orEmpty()
            detail(Icons.Filled.Place, "Address", "$answered:${machine.port}")
            // The *others*, not all of them: repeating the address the row above already
            // names makes a two-address machine look like it has three.
            machine.hosts.filterNot { it == answered }.takeIf { it.isNotEmpty() }?.let {
                detail(Icons.Filled.LocationOn, "Other addresses", it.joinToString(", "))
            }
            // A prefix, not the whole 64-character digest: it is here to be *compared* with
            // what the IDE shows, and eight bytes is what a human compares.
            detail(Icons.Filled.Lock, "Key fingerprint", machine.spkiFingerprint.take(16) + "…")
            detail(
                Icons.Filled.DateRange,
                "Last snapshot",
                state.snapshot?.generatedAtMs?.takeIf { it > 0 }
                    ?.let { Times.clock(it, now) }
                    ?: "None since this app started",
            )
            // Only once the machine has answered: a switch drawn before that would guess its
            // position, and a phone that guessed "on" would show what the desk may not be doing.
            state.keepAwake?.let { on ->
                toggle(
                    Icons.Filled.Home,
                    "Keep awake while an agent works",
                    "Holds this machine's sleep off for as long as a run is alive.",
                    on,
                    onKeepAwake,
                )
                // Beside its parent switch, and only from a machine that reports it: an older one has no such value to show.
                state.holdForReview?.let { review ->
                    toggle(
                        Icons.Filled.Home,
                        "Also while a session waits for my review",
                        "Stays awake after a run ends, until you review or answer.",
                        review,
                        onHoldForReview,
                    )
                }
            }
            // Only once the machine has answered, for the switch above's reason; a phone without the owner's grant is never answered, so is shown none.
            state.promptSuggestions?.let { on ->
                toggle(
                    Icons.Filled.Edit,
                    "Suggest the next prompt",
                    "Adds a model call after each turn, which uses tokens.",
                    on,
                    onPromptSuggestions,
                )
            }
            // Drawn only once a phone holding the owner's grant has read the desk's value, for the switch above's reason.
            state.restrictedMode?.let { on ->
                toggle(
                    Icons.Filled.Lock,
                    "Restricted mode for Claude chats",
                    "Removes Bash, PowerShell and WebFetch from the next run. Chats in Full access start unrestricted.",
                    on,
                    onRestrictedMode,
                )
            }
            // Same gate as the switch above: the phone draws it only once the machine has answered its grant holder.
            state.questionsKeepWorking?.let { on ->
                toggle(
                    Icons.Filled.Info,
                    "Keep working while a question waits",
                    "Agents carry on with their recommended option; your answer follows. Codex always does.",
                    on,
                    onQuestionsKeepWorking,
                )
            }
            // Drawn once the machine has answered; no grant, so an older machine (no route) is the only one left without it.
            state.continueInterrupted?.let { on ->
                toggle(
                    Icons.Filled.Refresh,
                    "Continue an interrupted step",
                    "A desk chat that stops unfinished sends one continuation by itself. Off leaves it stopped.",
                    on,
                    onContinueInterrupted,
                )
            }
            // A typed number, not a menu of guesses: how long a chat stays worth listing is the reader's own call.
            state.chatAutoHideDays?.let { days ->
                row { DaysRow(Icons.Filled.DateRange, "Hide chats I haven't opened in", days, onChatAutoHide) }
            }
            // Same gate again. Each firing is a full Codex review on the reader's account, so both switches say so.
            state.autoReview?.let { review ->
                toggle(
                    Icons.Filled.Check,
                    "Review changes when a run ends",
                    "Asks the Codex reviewer about a run that changed files. Each review uses tokens.",
                    review.onRunFinished,
                ) { onAutoReview(com.github.claudeagents.core.mobile.MobileAutoReview.Change(onRunFinished = it)) }
                toggle(
                    Icons.Filled.Done,
                    "Review changes after a commit",
                    "Asks the Codex reviewer about what you just committed. Each review uses tokens.",
                    review.onCommit,
                ) { onAutoReview(com.github.claudeagents.core.mobile.MobileAutoReview.Change(onCommit = it)) }
            }
            // Only once the machine has answered, for the switch above's reason: a pill drawn before it would guess the pin.
            state.newChatDefaults?.takeIf { it.choices.isNotEmpty() }?.let { defaults ->
                row {
                    ControlRow(Icons.Filled.Edit, "Start new chats in") {
                        Selector(
                            options = defaults.choices.map { SelectorOption(it.label, it.slug) },
                            selected = defaults.mode,
                            onSelect = onNewChatMode,
                        )
                    }
                }
            }
            action(Icons.Filled.Add, "Pair another machine", onClick = onAdd)
            action(
                Icons.Filled.Delete,
                "Unpair ${machine.machineName.ifBlank { "this machine" }}…",
                // A dialog, not a destination: a chevron here would promise a page.
                chevron = false,
                destructive = true,
            ) { confirming = true }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Unpair ${machine?.machineName?.ifBlank { "this machine" } ?: "this machine"}?") },
            text = {
                Text(
                    "This phone will forget the machine, its cached conversations and any unsent " +
                        "drafts, and the machine will forget this device. Pairing again needs a " +
                        "new code from the IDE.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onUnpair()
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NotificationSection(
    state: DeckState,
    context: Context,
    onSettings: (AppSettings) -> Unit,
    onChoosePush: (String?) -> Unit,
) {
    val allowed = DeckNotifications.allowed(context)
    Section("Notifications") {
        NotifyTrigger.entries.forEach { trigger ->
            toggle(
                icon = trigger.icon,
                title = trigger.title,
                subtitle = trigger.description,
                checked = trigger in state.settings.triggers,
                onChange = { on ->
                    val triggers = if (on) state.settings.triggers + trigger
                    else state.settings.triggers - trigger
                    onSettings(state.settings.copy(triggers = triggers))
                },
            )
            // Sound, importance and bypass belong to Android, not to a screen this app would
            // have to reinvent — so each row hands off to its own system channel.
            action(Icons.Filled.Settings, "Sound and importance for “${trigger.title}”") {
                openChannelSettings(context, trigger.id)
            }
        }
        if (!allowed) {
            detail(
                Icons.Filled.Lock,
                "Notifications are off",
                "Android is blocking them for Agent Deck. Turn them on below and this list " +
                    "starts working.",
            )
            action(Icons.Filled.Settings, "Open Agent Deck's notification settings") {
                openAppNotificationSettings(context)
            }
        }
        pushRows(state.push, onChoosePush)
    }
}

/**
 * How an alert reaches this phone, and the one control that changes it.
 *
 * The *sentence* is [PushCopy]'s decision, taken over four separate facts rather than one
 * "push works" boolean: a reader whose phone is silent needs to know which piece is missing,
 * and the four have four different repairs — install an app, tap a row here, wait for a relay,
 * or flip a switch in the IDE at their desk. Only one of those is on this screen, and a single
 * "Push: off" row would be true in every case and useful in none.
 */
private fun SectionRows.pushRows(push: PushState, onChoosePush: (String?) -> Unit) {
    val copy = PushCopy.of(push)
    detail(Icons.Filled.Info, copy.title, copy.body)
    if (copy.offerStop) {
        action(Icons.Filled.Close, "Stop notifying this phone when the app is closed") {
            onChoosePush(null)
        }
    }
    copy.offer.forEach { distributor ->
        action(Icons.Filled.Notifications, "Notify me through ${distributor.label}") {
            onChoosePush(distributor.packageName)
        }
    }
}

@Composable
private fun ConnectionSection(state: DeckState, onSettings: (AppSettings) -> Unit) {
    Section("Connection") {
        toggle(
            icon = Icons.Filled.Refresh,
            title = "Stay connected in the background",
            subtitle = "Behind an ongoing notification that says what it is holding.",
            checked = state.settings.stayConnected,
            onChange = { onSettings(state.settings.copy(stayConnected = it)) },
        )
    }
}

/**
 * How a diff is laid out under a conversation's Changes tab.
 *
 * Here rather than on the diff itself because the answer is a reading habit and not a per-file
 * decision — and two toggles above every file would be two controls in the way of every file.
 * Both off by default: a phone is narrow, and wrapping and a number gutter each cost the column
 * the code is in.
 *
 * Wrapping reaches a chat's code fences too, so the row does not say "in diffs"; line numbers
 * stay the diff's alone, because a fence has none to be right about.
 */
@Composable
private fun ReadingSection(state: DeckState, onSettings: (AppSettings) -> Unit) {
    Section("Reading") {
        toggle(
            icon = Icons.AutoMirrored.Filled.List,
            title = "Wrap long lines",
            subtitle = "Diffs and code blocks. Off: a long line scrolls sideways instead of folding.",
            checked = state.settings.diffSoftWrap,
            onChange = { onSettings(state.settings.copy(diffSoftWrap = it)) },
        )
        toggle(
            icon = Icons.Filled.Menu,
            title = "Show line numbers in diffs",
            subtitle = "A number gutter beside each changed line.",
            checked = state.settings.diffLineNumbers,
            onChange = { onSettings(state.settings.copy(diffLineNumbers = it)) },
        )
        toggle(
            icon = Icons.Filled.Build,
            title = "Open tool calls",
            subtitle = "Each turn's tool calls start unfolded. Off: tap \"Tool calls\" to open them.",
            checked = state.settings.openToolCalls,
            onChange = { onSettings(state.settings.copy(openToolCalls = it)) },
        )
    }
}

@Composable
private fun WritingSection(state: DeckState, onSettings: (AppSettings) -> Unit) {
    Section("Writing") {
        toggle(
            icon = Icons.Filled.Edit,
            title = "Complete emoji after :",
            subtitle = "Typing :cry suggests matching emoji; typing the closing colon of :cry: replaces it.",
            checked = state.settings.emojiCompletion,
            onChange = { onSettings(state.settings.copy(emojiCompletion = it)) },
        )
    }
}

@Composable
private fun AppearanceSection(state: DeckState, onSettings: (AppSettings) -> Unit) {
    Section("Appearance") {
        row {
            ControlRow(Icons.Filled.Face, "Theme") {
                // No `prefix`: the headline names the setting, and the pill's 160 dp label
                // clipped "Match the system" back to "Match the syst…" once it carried both.
                Selector(
                    options = ThemeChoice.entries.map { SelectorOption(it.label, it) },
                    selected = state.settings.theme,
                    onSelect = { onSettings(state.settings.copy(theme = it)) },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            toggle(
                icon = Icons.Filled.Star,
                title = "Colour from the wallpaper",
                subtitle = "Material You. Off keeps Agent Deck's own palette.",
                checked = state.settings.dynamicColor,
                onChange = { onSettings(state.settings.copy(dynamicColor = it)) },
            )
        }
    }
}

/**
 * The app's own version, and the only route this app has out of it.
 *
 * Agent Deck is sideloaded, so no store will ever offer its owner a newer build — which is why
 * an update lives here rather than behind a link to a download page, and why the status line is
 * permanent while the *buttons* appear only when there is something to press.
 */
@Composable
private fun AboutSection(
    state: DeckState,
    onSettings: (AppSettings) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onReleasePage: () -> Unit,
) {
    val update = state.update
    val release = update.release
    val offered = update.available && !AppUpdate.tooOld(release, Build.VERSION.SDK_INT)
    Section("About") {
        // The installed version comes from the package manager through the view model, not from
        // `BuildConfig`: after a sideload the APK on the phone and the sources that built this
        // screen are the same only by luck, and it is the *installed* one being compared.
        detail(Icons.Filled.Info, "Agent Deck", update.installedName.ifBlank { "unknown" })
        detail(Icons.Filled.Refresh, "Updates", AppUpdate.status(update, Build.VERSION.SDK_INT))
        when {
            update.readyApk != null -> action(
                Icons.Filled.Star,
                "Install ${release?.label.orEmpty()}",
                chevron = false,
                onClick = onInstallUpdate,
            )
            offered -> action(
                Icons.Filled.Star,
                listOfNotNull(
                    "Download and install ${release?.label.orEmpty()}",
                    AppUpdate.size(release?.sizeBytes ?: 0).takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                chevron = false,
                onClick = onDownloadUpdate,
            )
        }
        if (!update.busy) {
            action(Icons.Filled.Search, "Check for updates", chevron = false, onClick = onCheckUpdate)
        }
        // The way out of every refusal above — a wrong signing key, an installer this phone does
        // not have, a checksum that did not match — is the page the APK can be fetched from by
        // hand. Offered only while one of those is on screen, never as decoration.
        if (offered || update.error != null) {
            action(Icons.Filled.Share, "Open the release page", onClick = onReleasePage)
        }
        toggle(
            icon = Icons.Filled.Notifications,
            title = "Tell me about new versions",
            subtitle = "A banner at the top of the app when a newer build is published. The rows " +
                "above still offer it when this is off.",
            checked = state.settings.updateNotices,
            onChange = { onSettings(state.settings.copy(updateNotices = it)) },
        )
        state.hello?.let { hello ->
            detail(
                Icons.Filled.Build,
                "IDE",
                listOf(hello.ideName, hello.pluginVersion).filter { it.isNotBlank() }.joinToString(" "),
            )
            detail(Icons.Filled.Call, "Protocol", "v${hello.protocolVersion}")
            // What the machine says it can do — the honest source for which surfaces exist.
            detail(
                Icons.Filled.CheckCircle,
                "This machine can",
                hello.capabilities.sorted().joinToString(", ").ifBlank { "nothing it named" },
            )
            hello.servedByOtherIde?.let { detail(Icons.Filled.Person, "Served by", it) }
        }
    }
}

/**
 * The way into the Usage page.
 *
 * Absent from a machine that does not advertise [MobileProtocol.Capability.USAGE]: a row that
 * opens a page which can only say "this plugin is too old" is a row that costs the reader a tap
 * to learn nothing. The section is the whole destination — one always-visible route, and the
 * plan line that used to sit in Diagnostics moves here, where it is about the plan rather than
 * about the connection.
 */
@Composable
private fun UsageSection(state: DeckState, onOpenUsage: () -> Unit) {
    if (MobileProtocol.Capability.USAGE !in state.hello?.capabilities.orEmpty()) return
    Section("Usage") {
        state.snapshot?.usageLine?.takeIf { it.isNotBlank() }?.let {
            detail(Icons.Filled.DateRange, "Plan usage", it)
        }
        action(Icons.Filled.Info, "Spend and plan windows", onClick = onOpenUsage)
    }
}

/**
 * The way into the teams and workflow runs the desk's Settings › Orchestration page monitors.
 *
 * Absent from a machine that does not advertise [MobileProtocol.Capability.ORCHESTRATION], and
 * read-only wherever it is present: the desk page has no control over a board or run to mirror.
 * A run's "Open chat" is navigation, not a control.
 */
@Composable
private fun OrchestrationSection(
    state: DeckState,
    onLoad: suspend () -> MobileOrchestration?,
    onOpenChat: (MobileWorkflowRun) -> Unit,
) {
    if (MobileProtocol.Capability.ORCHESTRATION !in state.hello?.capabilities.orEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Section("Agents") {
        action(Icons.Filled.Info, "Agent teams and workflow runs", onClick = { open = true })
    }
    if (open) OrchestrationSheet(onLoad = onLoad, onDismiss = { open = false }, onOpenChat = onOpenChat)
}

/**
 * The way into the desk's Resources tabs the phone mirrors: Memory and instructions (a text editor,
 * saved only under the owner's per-phone grant), Skills, agents and plugins and MCP servers (read-only lists) and Badges
 * (read-only, apart from sharing an earned badge's line). Each row is absent from a machine that does
 * not advertise its capability, and the section with it when none is.
 */
@Composable
private fun ResourcesSection(
    state: DeckState,
    onLoadBadges: suspend () -> MobileBadges?,
    onLoadExtensions: suspend () -> MobileExtensions?,
    onLoadAcpAgents: suspend () -> MobileAcpAgents?,
    onActOnAcpAgent: suspend (MobileAcpAgentAction) -> String?,
    onLoadMcpServers: suspend (String?) -> MobileMcpServers?,
    onCheckMcpHealth: suspend (String?) -> MobileMcpHealth?,
    onRemoveMcpServer: suspend (MobileMcpRemove) -> String?,
    onAddMcpServer: suspend (MobileMcpAdd) -> String?,
    onLoadSkills: suspend (String?) -> MobileSkillsList?,
    onTogglePlugin: suspend (MobilePluginToggle) -> String?,
    onUninstallPlugin: suspend (MobilePluginUninstall) -> String?,
    onUpdatePlugin: suspend (MobilePluginUpdate) -> String?,
    onRefreshMarketplace: suspend (MobileMarketplaceChange) -> String?,
    onRemoveMarketplace: suspend (MobileMarketplaceChange) -> String?,
    onSkillState: suspend (com.github.claudeagents.core.mobile.MobileSkillState) -> String?,
    onCopySkill: suspend (com.github.claudeagents.core.mobile.MobileSkillCopy) -> String?,
    onCreateSkill: suspend (com.github.claudeagents.core.mobile.MobileSkillCreate) -> String?,
    onEditSkillFile: (project: String, row: com.github.claudeagents.core.mobile.MobileSkillRow) -> Unit,
    hasSkillFileDraft: (project: String, row: com.github.claudeagents.core.mobile.MobileSkillRow) -> Boolean,
    onOpenMemory: () -> Unit,
    preview: PreviewLink?,
) {
    val capabilities = state.hello?.capabilities.orEmpty()
    val previewRow = preview != null && MobileProtocol.Capability.PREVIEW in capabilities
    val memory = MobileProtocol.Capability.MEMORY_FILES in capabilities
    val mcp = MobileProtocol.Capability.MCP_SERVERS in capabilities
    val skills = MobileProtocol.Capability.SKILLS_LIST in capabilities
    val badges = MobileProtocol.Capability.BADGES in capabilities
    val extensions = MobileProtocol.Capability.EXTENSIONS in capabilities
    val acpAgents = MobileProtocol.Capability.ACP_AGENT_STATUS in capabilities
    if (!memory && !mcp && !skills && !badges && !extensions && !previewRow && !acpAgents) return
    var previewOpen by rememberSaveable { mutableStateOf(false) }
    var badgesOpen by rememberSaveable { mutableStateOf(false) }
    var extensionsOpen by rememberSaveable { mutableStateOf(false) }
    var mcpOpen by rememberSaveable { mutableStateOf(false) }
    var acpAgentsOpen by rememberSaveable { mutableStateOf(false) }
    var skillsOpen by rememberSaveable { mutableStateOf(false) }
    Section("Resources") {
        if (memory) action(Icons.Filled.Edit, "Memory and instructions", onClick = onOpenMemory)
        if (skills) action(Icons.AutoMirrored.Filled.List, "Skills, agents and plugins", onClick = { skillsOpen = true })
        if (acpAgents) action(Icons.Filled.Person, "ACP agents", onClick = { acpAgentsOpen = true })
        if (mcp) action(Icons.Filled.Build, "MCP servers", onClick = { mcpOpen = true })
        if (extensions) action(Icons.Filled.Settings, "Extensions", onClick = { extensionsOpen = true })
        if (badges) action(Icons.Filled.Star, "Badges", onClick = { badgesOpen = true })
        if (previewRow) action(Icons.Filled.Search, "Preview", onClick = { previewOpen = true })
    }
    // Gated on the capability too, so a machine switch or a revoked grant that takes `preview` away closes it rather than leaving the old machine's page up.
    if (previewOpen && previewRow && preview != null) PreviewSheet(link = preview, onDismiss = { previewOpen = false })
    if (skillsOpen) SkillsSheet(
        onLoad = onLoadSkills,
        onToggle = onTogglePlugin.takeIf { MobileProtocol.Capability.PLUGIN_TOGGLE in capabilities },
        onUninstall = onUninstallPlugin.takeIf { MobileProtocol.Capability.PLUGIN_UNINSTALL in capabilities },
        onUpdate = onUpdatePlugin.takeIf { MobileProtocol.Capability.PLUGIN_UPDATE in capabilities },
        onRefreshMarketplace = onRefreshMarketplace.takeIf { MobileProtocol.Capability.MARKETPLACE_REFRESH in capabilities },
        onRemoveMarketplace = onRemoveMarketplace.takeIf { MobileProtocol.Capability.MARKETPLACE_REMOVE in capabilities },
        onState = onSkillState.takeIf { MobileProtocol.Capability.SKILL_STATE in capabilities },
        onCopy = onCopySkill.takeIf { MobileProtocol.Capability.SKILL_COPY in capabilities },
        onCreate = onCreateSkill.takeIf { MobileProtocol.Capability.SKILL_CREATE in capabilities },
        onEditFile = onEditSkillFile.takeIf { MobileProtocol.Capability.SKILL_FILE in capabilities },
        hasFileDraft = hasSkillFileDraft,
        onDismiss = { skillsOpen = false },
    )
    if (acpAgentsOpen && acpAgents) AcpAgentsSheet(
        onLoad = onLoadAcpAgents,
        onAct = onActOnAcpAgent.takeIf { MobileProtocol.Capability.ACP_AGENT_ACTIONS in capabilities },
        onDismiss = { acpAgentsOpen = false },
    )
    if (mcpOpen) McpSheet(
        onLoad = onLoadMcpServers,
        onCheck = onCheckMcpHealth.takeIf { MobileProtocol.Capability.MCP_HEALTH in capabilities },
        onRemove = onRemoveMcpServer.takeIf { MobileProtocol.Capability.MCP_REMOVE in capabilities },
        onAdd = onAddMcpServer.takeIf { MobileProtocol.Capability.MCP_ADD in capabilities },
        onDismiss = { mcpOpen = false },
    )
    if (badgesOpen) BadgesSheet(onLoad = onLoadBadges, onDismiss = { badgesOpen = false })
    if (extensionsOpen) ExtensionsSheet(onLoad = onLoadExtensions, onDismiss = { extensionsOpen = false })
}

@Composable
private fun DiagnosticsSection(state: DeckState) {
    // Hoisted for the same reason the Machine section hoists it: the rows are built outside
    // composition and the reader's clock is a composition-local read.
    val now = LocalNow.current()
    Section("Diagnostics") {
        detail(
            Icons.Filled.Call,
            "Connection",
            when (val link = state.link) {
                Link.Live -> "Connected"
                Link.Connecting -> "Connecting"
                Link.Offline -> "This phone is offline"
                is Link.Stale -> link.reason
                is Link.Repair -> link.reason
            },
        )
        // Which address actually carried a call, as opposed to which one is remembered.
        // The machine section's "Address" is the phone's *preference*; this is the answer, and
        // the gap between the two is the whole of MP-07: a user who reads "cannot reach your
        // machine" needs to know it is dialling a LAN address from the train. Named only once
        // something has answered — "none yet" is a real state and the sentence says so.
        detail(
            Icons.Filled.Place,
            "Last answered by",
            state.lastGood
                ?.let { "${it.host} · ${Times.clock(it.atWallClockMs, now)} · ${it.reach.phrase}" }
                ?: "Nothing has answered since this app started",
        )
        detail(
            Icons.AutoMirrored.Filled.List,
            "Conversations known",
            (state.snapshot?.rows?.size ?: 0).toString(),
        )
        // The plan line lives under Usage when the machine has that page, and here otherwise:
        // on an older plugin this sentence is the only usage the phone can show at all.
        if (MobileProtocol.Capability.USAGE !in state.hello?.capabilities.orEmpty()) {
            state.snapshot?.usageLine?.takeIf { it.isNotBlank() }?.let {
                detail(Icons.Filled.DateRange, "Plan usage", it)
            }
        }
    }
}

/**
 * This app's own log — the answer to "what did the app just do" for a reader who is not sitting
 * at a debugger, and for an agent at the desk that can read the file the machine keeps.
 *
 * Sending is offered only to a machine that says it can take one; sharing is always there,
 * because it needs nothing from the machine. What each did stays on screen until the next tap.
 */
@Composable
private fun LogsSection(state: DeckState, onSend: () -> Unit, onShare: () -> Unit) {
    val now = LocalNow.current()
    val canSend = state.machine != null &&
        MobileProtocol.Capability.PHONE_LOGS in state.hello?.capabilities.orEmpty()
    Section("Logs") {
        if (canSend) {
            action(
                Icons.AutoMirrored.Filled.Send,
                if (state.logSend == LogSend.Sending) "Sending log…" else "Send log to this machine",
                chevron = false,
            ) { if (state.logSend != LogSend.Sending) onSend() }
        }
        when (val sent = state.logSend) {
            is LogSend.Sent -> detail(
                Icons.Filled.CheckCircle,
                "Log sent",
                "${(sent.bytes + 1023) / 1024} KB at ${Times.clock(sent.atMs, now)}. Open it in the IDE, " +
                    "under Settings › Connections › Mobile › Devices.",
            )
            is LogSend.Failed -> detail(Icons.Filled.Warning, "Log not sent", sent.reason)
            else -> Unit
        }
        action(Icons.Filled.Share, "Share log…", chevron = false, onClick = onShare)
    }
}

/**
 * What an address is worth away from the desk, in the words a reader can act on.
 *
 * The enum's own names are about routing; these are about the user's next move. Deliberately
 * not "private"/"public" — a Tailscale address *is* private by RFC 6598 and travels anywhere,
 * which is the one case where the technical word would send someone to fix the wrong thing.
 */
private val HostReach.phrase: String
    get() = when (this) {
        HostReach.LOOPBACK -> "this device"
        HostReach.LAN -> "its own network only"
        HostReach.OVERLAY -> "reachable anywhere on your overlay"
        HostReach.PUBLIC -> "reachable from anywhere"
    }

// ---- pieces ---------------------------------------------------------------------------

/** Android's minimum touch target, held explicitly on every row that takes a gesture. */
private val MIN_TARGET = 48.dp

/** Where a `ListItem`'s text starts: 16 dp of padding, a 24 dp icon, 16 dp more. */
private val TEXT_INSET = 56.dp

/** Which glyph names a notification, rather than four rows carrying the same bell. */
private val NotifyTrigger.icon: ImageVector
    get() = when (this) {
        NotifyTrigger.NEEDS_YOU -> Icons.Filled.Person
        NotifyTrigger.FAILED -> Icons.Filled.Warning
        NotifyTrigger.FINISHED -> Icons.Filled.CheckCircle
        NotifyTrigger.PLAN_USAGE -> Icons.Filled.Info
    }

/**
 * The rows of one grouped card, collected as plain Kotlin before any of them is composed.
 *
 * The dividers go *between* rows, so a row has to know whether it is the first — and a counter
 * kept in composition keeps counting when the section's body recomposes on its own. Building
 * the list first makes the index a fact about the list rather than about the recomposition.
 */
private class SectionRows {
    val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }

    fun detail(icon: ImageVector, title: String, value: String) = row { DetailRow(icon, title, value) }

    fun action(
        icon: ImageVector,
        title: String,
        chevron: Boolean = true,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ) = row { ActionRow(icon, title, chevron, destructive, onClick) }

    fun toggle(
        icon: ImageVector,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) = row { ToggleRow(icon, title, subtitle, checked, onChange) }
}

@Composable
private fun Section(title: String, content: SectionRows.() -> Unit) {
    val rows = SectionRows().apply(content).rows
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 28.dp, end = 16.dp, bottom = 6.dp),
        )
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            Modifier.padding(start = TEXT_INSET),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    row()
                }
            }
        }
    }
}

/**
 * A `ListItem` paints its own container, and its default is `surface` — which would erase the
 * tonal card the rows are grouped inside.
 */
@Composable
private fun rowColors(headline: Color = MaterialTheme.colorScheme.onSurface) =
    ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        headlineColor = headline,
    )

/** Decoration: a described icon would be a second node in a row whose name is its headline. */
@Composable
private fun RowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
}

/** A fact. Nothing merges an unclickable row, so two Texts would announce twice. */
@Composable
private fun DetailRow(icon: ImageVector, title: String, value: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = { RowIcon(icon) },
        colors = rowColors(),
        modifier = Modifier.heightIn(min = MIN_TARGET).semantics(mergeDescendants = true) {},
    )
}

/** A row that does something. 48 dp minimum, and it announces itself once, as a button. */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    chevron: Boolean,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    val trailing: (@Composable () -> Unit)? =
        if (chevron) {
            { RowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight) }
        } else {
            null
        }
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { RowIcon(icon, if (destructive) error else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = trailing,
        colors = rowColors(headline = if (destructive) error else MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.heightIn(min = MIN_TARGET).clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { RowIcon(icon) },
        // `onCheckedChange = null` keeps the switch out of the focus order: the row is the
        // control, so it is a 48 dp target rather than a 32 dp bullseye, and it announces once.
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = rowColors(),
        modifier = Modifier.heightIn(min = MIN_TARGET).clickable(role = Role.Switch) { onChange(!checked) },
    )
}

/**
 * A whole number of days typed in place; 0 is off. It commits on Done or when focus leaves, and only a
 * number the machine accepts — anything else snaps back to the stored value rather than being sent to be refused.
 */
@Composable
private fun DaysRow(icon: ImageVector, title: String, days: Int, onChange: (Int) -> Unit) {
    var text by remember(days) { mutableStateOf(days.toString()) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val typed = text.toIntOrNull()
        if (typed != null && typed in MobileChatAutoHide.RANGE && typed != days) onChange(typed) else text = days.toString()
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(if (days == 0) "Off: the desk lists every chat." else "Folded out of the desk's list. Pinned chats stay.") },
        leadingContent = { RowIcon(icon) },
        trailingContent = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter(Char::isDigit).take(3) },
                singleLine = true,
                suffix = { Text("days") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.width(112.dp)
                    .testTag("chat-auto-hide-days")
                    .semantics { contentDescription = "$title, days" }
                    .onFocusChanged { state ->
                        if (focused && !state.isFocused) commit()
                        focused = state.isFocused
                    },
            )
        },
        colors = rowColors(),
        modifier = Modifier.heightIn(min = MIN_TARGET),
    )
}

/** A row whose value is a control the user operates in place, so nothing here merges it away. */
@Composable
private fun ControlRow(icon: ImageVector, title: String, control: @Composable () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { RowIcon(icon) },
        trailingContent = control,
        colors = rowColors(),
        modifier = Modifier.heightIn(min = MIN_TARGET),
    )
}

@Composable
private fun MachineRow(machine: PairedMachine, active: Boolean, onSelect: () -> Unit) {
    val trailing: (@Composable () -> Unit)? =
        if (active) {
            {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Showing this machine",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            null
        }
    ListItem(
        headlineContent = { Text(machine.machineName.ifBlank { "(unnamed)" }) },
        supportingContent = {
            Text(machine.hosts.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { RowIcon(Icons.Filled.Home) },
        trailingContent = trailing,
        colors = rowColors(),
        modifier = Modifier.heightIn(min = MIN_TARGET).clickable(role = Role.Button, onClick = onSelect),
    )
}

private fun openChannelSettings(context: Context, channelId: String) {
    val intent = Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(AndroidSettings.EXTRA_CHANNEL_ID, channelId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
