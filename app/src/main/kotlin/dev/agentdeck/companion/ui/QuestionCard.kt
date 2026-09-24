package dev.agentdeck.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileToolCall

/**
 * An `AskUserQuestion`, as the surface the reader answers it on.
 *
 * The options are the plugin's own — the same `InteractiveQuestion` parse the desktop's inline
 * cards and its permission dialog read — so the three surfaces cannot offer different answers to
 * one ask. A lone single-choice question sends on its tap; an ask with several questions, or a
 * multi-select one, collects a pick per question and sends them together, because the host
 * settles the whole parked request with the first answer it receives. The answer reaches the
 * CLI on the control channel and resumes the very turn the agent is blocked on; the snack says
 * which route the machine took, because the fallback is a new turn and that is not the same act.
 */
@Composable
internal fun QuestionCard(
    call: MobileToolCall,
    canAnswer: Boolean,
    answerFailures: Int,
    onAnswer: (Map<String, String>) -> Unit,
) {
    val live = TranscriptQuestions.answerable(call, canAnswer)
    val onTap = TranscriptQuestions.sendsOnTap(call)
    // Picks survive rotation and process death; the lock does not outlive a refused answer, so
    // the same picks can be sent again without re-tapping them.
    var picks by rememberSaveable(call.id, stateSaver = picksSaver) { mutableStateOf(emptyMap<String, List<String>>()) }
    var sent by rememberSaveable(call.id, answerFailures) { mutableStateOf(false) }
    val open = live && !sent
    Column(Modifier.padding(top = 8.dp)) {
        call.questions.forEach { question ->
            Text(
                TranscriptQuestions.header(question),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = if (question.multiSelect) 0.dp else 6.dp),
            )
            if (question.multiSelect) {
                Text(
                    "Choose any",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            question.options.forEach { option ->
                val chosen = option.label in picks[question.key].orEmpty()
                val pick = {
                    picks = TranscriptQuestions.toggle(picks, question, option.label)
                    if (onTap) {
                        sent = true
                        onAnswer(TranscriptQuestions.answers(call, picks))
                    }
                }
                // Enabled and not merely un-clickable: a disabled row keeps the whole card
                // readable, which is what an already-answered question is for.
                val choice = if (question.multiSelect) {
                    Modifier.toggleable(value = chosen, enabled = open, role = Role.Checkbox) { pick() }
                } else {
                    Modifier.selectable(selected = chosen, enabled = open, role = Role.RadioButton) { pick() }
                }
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .then(choice),
                    shape = RoundedCornerShape(12.dp),
                    color = if (chosen) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    border = if (open) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        option.description?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        if (open && !onTap) {
            Button(
                onClick = {
                    sent = true
                    onAnswer(TranscriptQuestions.answers(call, picks))
                },
                enabled = TranscriptQuestions.complete(call, picks),
                modifier = Modifier.padding(bottom = 6.dp),
            ) { Text(if (call.questions.size > 1) "Send answers" else "Send answer") }
        }
        TranscriptQuestions.closedNote(call, canAnswer)?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Question key and its picks, flattened to strings so the bundle holds them. */
private val picksSaver = listSaver<Map<String, List<String>>, String>(
    save = { picks -> picks.flatMap { (key, labels) -> listOf(key, labels.size.toString()) + labels } },
    restore = { flat ->
        val picks = LinkedHashMap<String, List<String>>()
        var i = 0
        while (i + 1 < flat.size) {
            val count = flat[i + 1].toIntOrNull() ?: break
            picks[flat[i]] = flat.subList(i + 2, minOf(flat.size, i + 2 + count)).toList()
            i += 2 + count
        }
        picks
    },
)
