package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.Dictation
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.PendingPhoto

/**
 * Starting a conversation from the phone.
 *
 * The bridge has accepted `newChat` since the protocol's first version — a phone-origin
 * prompt with no session id becomes an ordinary scheduled row due now — and the only thing
 * missing was a way to ask for one, so the app could reply to agents and never begin.
 *
 * Projects come from the snapshot's `openProjects` and nothing else: the machine refuses a
 * prompt for a project this IDE does not have open, and offering a project that can only be
 * refused is a dead option shaped like a live one.
 *
 * The model comes from [hello] for the same reason. `MobileSendRequest.model` has been on the
 * wire since v1 and the bridge has always forwarded it, so every chat started here ran on
 * whatever the machine defaulted to and never said which — the missing piece was the machine
 * telling the phone its own ladder, not a dropdown (MP-11).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewChatScreen(
    target: NewChatTarget,
    openProjects: List<String>,
    vendors: List<AgentVendor>,
    /** `/v1/hello`, for the model ladder. Null before it has answered — then no picker. */
    hello: MobileHello?,
    draft: String,
    sending: Boolean,
    notice: String?,
    onTarget: (NewChatTarget) -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onDismissNotice: () -> Unit,
    /** Photos already uploaded for this start; absent capability means no button and no chips. */
    photos: List<PendingPhoto> = emptyList(),
    attaching: Boolean = false,
    onAttach: (ByteArray) -> Unit = {},
    onRemovePhoto: (String) -> Unit = {},
    /** The new-chat composer's own stash (`new-chat` key); null in fixtures that do not exercise it. */
    stash: ComposerStashActions? = null,
) {
    val canAttach = MobileProtocol.Capability.ATTACHMENTS in hello?.capabilities.orEmpty()
    var promptFocused by remember { mutableStateOf(false) }
    val keyboardOpen = WindowInsets.isImeVisible && promptFocused
    // Three lines of 200 % text and a 56 dp Start bar pushed the field being typed into out of
    // sight on a phone on its side (J14). While crowded, the field is one line that scrolls to
    // its caret, Start rides in it as the conversation's Send does, and the bar returns with
    // the room.
    val crowded = keyboardOpen && KeyboardRoom.crowded()
    val canStart = (draft.isNotBlank() || photos.isNotEmpty()) && !sending
    val scroll = rememberScrollState()
    // The column kept the offset it had under the taller layout, which left the field above
    // the viewport; with the headings hidden the one-line field is the column's first row.
    LaunchedEffect(crowded) { if (crowded) scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!keyboardOpen) {
                Text("What would you like to do?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Give your agent a clear task. You can keep working together from here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                label = { Text("Your task") },
                placeholder = { Text("Ask a question or describe a change…") },
                minLines = when {
                    crowded -> 1
                    keyboardOpen -> 3
                    else -> 5
                },
                maxLines = if (crowded) 1 else Int.MAX_VALUE,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canAttach) AttachPhotoButton(busy = attaching, onPicked = onAttach)
                        DictateButton(onSpoken = { onDraft(Dictation.append(draft, it)) })
                        if (crowded) DeckIconButton("Start chat", Icons.Filled.Send, onSend, enabled = canStart)
                    }
                },
                modifier = Modifier.fillMaxWidth().onFocusChanged { promptFocused = it.isFocused },
            )
            PhotoChips(photos, onRemovePhoto)
            // Above the starters: a task the reader parked on purpose outranks a canned one.
            if (draft.isBlank()) stash?.let { StashedPromptsChip(it) }
            if (draft.isBlank() && !keyboardOpen) {
                Text(
                    "Start with an idea",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STARTER_PROMPTS.forEach { (label, prompt) ->
                        OutlinedButton(
                            onClick = { onDraft(prompt) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(label) }
                    }
                }
            }
            if (openProjects.isEmpty()) {
                Text(
                    "Open a project in your IDE to start this chat. You can write your task here while you wait.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Working in", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Selector(
                        options = openProjects.map { SelectorOption(projectName(it), it) },
                        selected = target.projectPath,
                        onSelect = { onTarget(target.copy(projectPath = it)) },
                        prefix = "Project:",
                    )
                    Selector(
                        options = vendors.map { SelectorOption(it.label(), it) },
                        selected = target.vendor,
                        onSelect = { onTarget(target.withVendor(it)) },
                        prefix = "Agent:",
                    )
                    // Inline beside Agent rather than nested under it as on the desktop: this
                    // screen has no usage strip naming the account, and the row already wraps.
                    AccountSelector(
                        hello = hello,
                        vendor = target.vendor,
                        picked = target.accountId,
                        onSelect = { onTarget(target.copy(accountId = it)) },
                    )
                    if (ModelRows.of(hello, target.vendor, target.model).isNotEmpty()) {
                        ModelSelector(
                            hello = hello,
                            vendor = target.vendor,
                            selected = target.model,
                            onSelect = { onTarget(target.copy(model = it)) },
                            prefix = "Model:",
                        )
                    }
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
                        prefix = "Mode:",
                    )
                    RunToggleSelector(hello, target.vendor, "Fast", target.fastMode, { onTarget(target.copy(fastMode = it)) }, vendors = RunOptionRows.FAST_VENDORS)
                    RunToggleSelector(hello, target.vendor, "Thinking", target.thinking, { onTarget(target.copy(thinking = it)) })
                }
            }
            notice?.let { message ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onDismissNotice) { Text("Dismiss") }
                    }
                }
            }
        }
        if (!crowded) Surface(tonalElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                // Beside Start rather than in the field, as the conversation keeps it beside the
                // run line; text only, so not while photos wait — they would start the next chat.
                if (draft.isNotBlank() && photos.isEmpty() && !sending) stash?.let {
                    DeckIconButton("Stash this task for later", DeckIcons.Stash, onClick = it.onStash,
                        modifier = Modifier.padding(end = 8.dp))
                }
                Button(
                    onClick = onSend,
                    // A photo alone is a message, so the gate is "nothing to send", not "nothing typed".
                    enabled = canStart,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) {
                    Text(if (sending) "Starting…" else "Start chat")
                }
            }
        }
    }
}

internal val STARTER_PROMPTS = listOf(
    "Review changes" to "Review the current uncommitted changes. Summarize what changed, identify bugs and missing tests, and suggest the next steps. Do not modify files yet.",
    "Find a bug" to "Help me investigate a bug. Start by asking me for the observed behavior and what I expected, then trace the cause before proposing a fix.",
    "Plan a task" to "Help me plan a change. Ask what I want to achieve, inspect the relevant code, and propose a small implementation plan before making changes.",
)

private fun projectName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { path }
