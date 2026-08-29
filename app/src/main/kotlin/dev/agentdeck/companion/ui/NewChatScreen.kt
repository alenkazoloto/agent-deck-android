package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import dev.agentdeck.companion.data.Dictation
import dev.agentdeck.companion.data.NewChatTarget

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
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        if (openProjects.isEmpty()) {
            Text(
                "This machine has no project open, so there is nowhere to start a chat. " +
                    "Open one in the IDE and refresh.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        Text("Project", style = MaterialTheme.typography.labelLarge)
        Selector(
            options = openProjects.map { SelectorOption(projectName(it), it) },
            selected = target.projectPath,
            onSelect = { onTarget(target.copy(projectPath = it)) },
            modifier = Modifier.padding(top = 4.dp),
        )

        Text("Agent", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
        Selector(
            options = vendors.map { SelectorOption(it.label(), it) },
            selected = target.vendor,
            // The model goes with the vendor it was picked from — see `NewChatTarget.model`.
            onSelect = { onTarget(target.copy(vendor = it, model = null)) },
            modifier = Modifier.padding(top = 4.dp),
        )

        // Absent entirely from a machine that never named its models: an empty picker is a
        // control that can only disappoint, and "Default" is what the request already does.
        if (ModelRows.of(hello, target.vendor, target.model).isNotEmpty()) {
            Text("Model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
            ModelSelector(
                hello = hello,
                vendor = target.vendor,
                selected = target.model,
                onSelect = { onTarget(target.copy(model = it)) },
                modifier = Modifier.padding(top = 4.dp),
                // The heading above already says it; Project and Agent are bare for the
                // same reason, and a pill reading "Model Opus 5" under it says it twice.
                prefix = null,
            )
        }

        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            label = { Text("First prompt") },
            placeholder = { Text("What should ${target.vendor.label()} do?") },
            minLines = 4,
            trailingIcon = {
                DictateButton(onSpoken = { onDraft(Dictation.append(draft, it)) })
            },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )

        notice?.let { message ->
            Card(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Verbatim: the machine's own sentence about why it refused.
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onDismissNotice) { Text("OK") }
                }
            }
        }

        Button(
            onClick = onSend,
            enabled = draft.isNotBlank() && !sending,
            modifier = Modifier.padding(top = 14.dp),
        ) {
            Text(if (sending) "Starting…" else "Start chat")
        }

        Text(
            "The chat opens on the machine and runs there. It appears in the list as soon " +
                "as it starts.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun projectName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { path }
