package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileSideAsk
import dev.agentdeck.companion.data.SideQuestionSheet

/**
 * The desk's `/btw` (`/v1/side-question`): the questions asked about this chat, each with the
 * machine's answer once it is in — the desk panel's stack, oldest first — and a field for the next.
 * The typed question is the reader's: a refusal or a lost link leaves it in the field.
 */
@Composable
internal fun SideQuestionDialog(
    sheet: SideQuestionSheet,
    onDraft: (String) -> Unit,
    onAsk: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("side-question-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("Side question", style = MaterialTheme.typography.headlineSmall)
                    if (sheet.title.isNotBlank()) {
                        Text(
                            sheet.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // One scrolling body under the fixed button: at 200% text the answers outgrow the dialog.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (sheet.asks.isEmpty()) {
                        Notice("Ask about this conversation. The answer is not added to it.")
                    }
                    sheet.asks.forEach { Ask(it) }
                    OutlinedTextField(
                        value = sheet.draft,
                        onValueChange = onDraft,
                        label = { Text("Question") },
                        isError = sheet.refused != null,
                        supportingText = sheet.refused?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().testTag("side-question-field"),
                    )
                    sheet.error?.let { Notice(it, error = true) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Button(
                        onClick = onAsk,
                        enabled = sheet.draft.isNotBlank() && !sheet.sending,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("side-question-ask"),
                    ) { Text("Ask") }
                }
            }
        }
    }
}

@Composable
private fun Ask(ask: MobileSideAsk) {
    Column(Modifier.fillMaxWidth().testTag("side-question-ask-row"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Text(ask.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        when {
            ask.running -> {
                Text(
                    "Thinking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { stateDescription = "Waiting for the answer" },
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            ask.failure != null -> Text(
                ask.failure.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("side-question-failure"),
            )
            else -> SelectionContainer {
                Text(ask.answer.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("side-question-answer"))
            }
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("side-question-notice"),
    )
}
