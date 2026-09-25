package dev.agentdeck.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileDecisionRequest
import com.github.claudeagents.core.mobile.MobilePendingPlan

/**
 * A finished plan the run is waiting on, with the desk window's choices: one "Approve · <mode>"
 * per mode the machine offers, and "Keep planning".
 *
 * The modes are the machine's, in its order and words — the phone names none of its own, so a mode
 * the machine would not accept is never a button. Nothing is the default and Enter approves
 * nothing, as on the desk: a stray tap must not start an implementation. The words in the field
 * go with whichever button is tapped and are kept per ask, so a refused decision does not lose
 * them; a tap locks the card until the machine answers, and [decideFailures] lifts the lock.
 * The plan is read in a bounded window that "Show full plan" opens, because approving is
 * reading it — the buttons stay reachable under a plan of any length.
 *
 * Shown only when the machine advertises `permission-decisions`, as [PermissionCard] is.
 */
@Composable
internal fun PlanCard(
    plan: MobilePendingPlan,
    decideFailures: Int,
    onDecide: (requestId: String, decision: String, mode: String, feedback: String) -> Unit,
) {
    var sent by rememberSaveable(plan.requestId, decideFailures) { mutableStateOf(false) }
    var feedback by rememberSaveable(plan.requestId) { mutableStateOf("") }
    var expanded by rememberSaveable(plan.requestId) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("plan-card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Approve the plan?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            plan.plan?.let { text ->
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp).testTag("plan-text")
                        .let { if (expanded) it else it.heightIn(max = 240.dp).verticalScroll(rememberScrollState()) },
                ) { MarkdownText(text) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show full plan") }
            }
            OutlinedTextField(
                value = feedback,
                onValueChange = { feedback = it.take(MobileDecisionRequest.MAX_FEEDBACK_CHARS) },
                label = { Text("Tell the agent what to change") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("plan-feedback"),
                minLines = 2,
            )
            plan.modes.forEachIndexed { index, mode ->
                val approve = {
                    sent = true
                    onDecide(plan.requestId, MobileDecisionRequest.APPROVE, mode.id, feedback)
                }
                if (index == 0) {
                    Button(
                        onClick = { approve() },
                        enabled = !sent,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 10.dp),
                    ) { Text("Approve · ${mode.label}") }
                } else {
                    OutlinedButton(
                        onClick = { approve() },
                        enabled = !sent,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 6.dp),
                    ) { Text("Approve · ${mode.label}") }
                }
                Text(
                    mode.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            OutlinedButton(
                onClick = {
                    sent = true
                    onDecide(plan.requestId, MobileDecisionRequest.KEEP_PLANNING, "", feedback)
                },
                enabled = !sent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 10.dp),
            ) { Text("Keep planning") }
        }
    }
}
