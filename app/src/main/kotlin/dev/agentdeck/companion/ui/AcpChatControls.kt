package dev.agentdeck.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.WaitingReason
import com.github.claudeagents.core.mobile.MobileAcpCall
import com.github.claudeagents.core.mobile.MobileAcpChat
import com.github.claudeagents.core.mobile.MobileAcpConfig
import com.github.claudeagents.core.mobile.MobileAcpOutcome
import com.github.claudeagents.core.mobile.MobileAcpSession
import kotlinx.coroutines.launch

/** Captured row and root identities travel with every action; a recomposition cannot retarget an in-flight answer. */
class AcpChatActions(
    val remembered: MobileAcpSession? = null,
    val canConfigure: Boolean = false,
    val canDecide: Boolean = false,
    val waitingReason: WaitingReason? = null,
    val onReopen: suspend () -> String? = { null },
    val onConfig: suspend (epoch: String, id: String, value: String) -> String? = { _, _, _ -> null },
    val onDecision: suspend (epoch: String, call: MobileAcpCall, outcome: MobileAcpOutcome) -> String? = { _, _, _ -> null },
)

@Composable
internal fun AcpChatControls(chat: MobileAcpChat?, actions: AcpChatActions, identity: String) {
    Column(Modifier.fillMaxWidth().testTag("acp-controls")) {
        actions.remembered?.let { saved ->
            AcpActionCard(identity, "acp-saved") { busy, submit ->
                Text("Saved chat", style = MaterialTheme.typography.titleSmall)
                saved.reopenError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saved.canResume) {
                    Button(enabled = !busy, onClick = { submit { actions.onReopen() } }) {
                        Text(if (saved.reopenError == null) "Reopen" else "Retry")
                    }
                } else Text("Reopen this chat in the IDE.")
            }
        }
        if (chat != null) {
            if (actions.canConfigure) chat.config.filter { it.options.isNotEmpty() }.forEach { config ->
                AcpConfigControl(chat.epoch, config, actions, identity)
            }
            if (chat.todos.isNotEmpty() || chat.activity.isNotEmpty()) {
                SurfaceCard("acp-progress") {
                    chat.todos.forEach { Text("${if (it.status == "completed") "✓" else "•"} ${it.text}") }
                    chat.activity.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (chat.activeCount > 0) Text("${chat.activeCount} active tasks", style = MaterialTheme.typography.labelSmall)
                }
            }
            chat.pendingCalls.forEach { call ->
                if (actions.canDecide) {
                    when (call.kind) {
                        "question" -> AcpQuestionControl(chat.epoch, call, actions, identity)
                        "plan" -> AcpPlanControl(chat.epoch, call, actions, identity)
                    }
                } else AcpIdeDecisionNotice(call.kind)
            }
        }
        if (chat?.pendingCalls.isNullOrEmpty() && !actions.canDecide) {
            when (actions.waitingReason) {
                WaitingReason.QUESTION -> AcpIdeDecisionNotice("question")
                WaitingReason.PLAN_APPROVAL -> AcpIdeDecisionNotice("plan")
                else -> Unit
            }
        }
    }
}

@Composable
private fun AcpIdeDecisionNotice(kind: String) {
    Text(
        if (kind == "plan") "Review this plan in the IDE." else "Answer this question in the IDE.",
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun AcpConfigControl(epoch: String, config: MobileAcpConfig, actions: AcpChatActions, identity: String) {
    val label = "${config.name}: ${config.options.firstOrNull { it.value == config.currentValue }?.name ?: config.currentValue}"
    if (config.options.size == 1) {
        SurfaceCard("acp-config-${config.id}-card") { Text(label, modifier = Modifier.testTag("acp-config-${config.id}")) }
        return
    }
    var expanded by remember(identity, epoch, config.id) { mutableStateOf(false) }
    AcpActionCard(identity + epoch + config.id + config.currentValue, "acp-config-${config.id}-card") { busy, submit ->
        TextButton(enabled = !busy, onClick = { expanded = true }, modifier = Modifier.testTag("acp-config-${config.id}")) {
            Text(label)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            config.options.forEach { option ->
                DropdownMenuItem(enabled = option.value != config.currentValue, text = { Column {
                    option.group?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    Text(option.name)
                    option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                } }, onClick = {
                    expanded = false
                    submit { actions.onConfig(epoch, config.id, option.value) }
                })
            }
        }
    }
}

@Composable
private fun AcpQuestionControl(epoch: String, call: MobileAcpCall, actions: AcpChatActions, identity: String) {
    var selected by remember(identity, epoch, call.decisionToken) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    AcpActionCard(identity + epoch + call.decisionToken, "acp-question-${call.toolCallId}", lockOnSuccess = true) { busy, submit ->
        call.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        call.questions.forEach { question ->
            Text(question.prompt, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            question.options.forEach { option ->
                val picked = option.id in selected[question.id].orEmpty()
                val pick = {
                    val choices = if (!question.allowMultiple) listOf(option.id)
                    else if (picked) selected[question.id].orEmpty() - option.id else selected[question.id].orEmpty() + option.id
                    selected = selected + (question.id to choices)
                }
                val choiceModifier = if (question.allowMultiple)
                    Modifier.toggleable(picked, enabled = !busy, role = Role.Checkbox, onValueChange = { pick() })
                else Modifier.selectable(picked, enabled = !busy, role = Role.RadioButton, onClick = pick)
                Row(choiceModifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (question.allowMultiple) Checkbox(picked, onCheckedChange = null)
                    else RadioButton(picked, onClick = null)
                    Text(option.label, modifier = Modifier.padding(12.dp))
                }
            }
        }
        Button(enabled = !busy && call.questions.isNotEmpty() && call.questions.all { selected[it.id].orEmpty().isNotEmpty() },
            onClick = { submit { actions.onDecision(epoch, call, MobileAcpOutcome("answered", selected)) } }) { Text("Answer") }
        TextButton(enabled = !busy, onClick = { submit { actions.onDecision(epoch, call, MobileAcpOutcome("skipped")) } }) { Text("Skip") }
    }
}

@Composable
private fun AcpPlanControl(epoch: String, call: MobileAcpCall, actions: AcpChatActions, identity: String) {
    var reason by remember(identity, epoch, call.decisionToken) { mutableStateOf("") }
    AcpActionCard(identity + epoch + call.decisionToken, "acp-plan-${call.toolCallId}", lockOnSuccess = true) { busy, submit ->
        Text(call.title ?: "Approve the plan?", style = MaterialTheme.typography.titleSmall)
        call.overview?.let { MarkdownText(it) }
        call.plan?.let { plan ->
            Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { MarkdownText(plan) }
        }
        OutlinedTextField(value = reason, onValueChange = { reason = it.take(2000) }, label = { Text("Reason (optional)") },
            modifier = Modifier.fillMaxWidth().testTag("acp-plan-reason"), enabled = !busy)
        Button(enabled = !busy, onClick = { submit { actions.onDecision(epoch, call, MobileAcpOutcome("accepted")) } }) { Text("Approve") }
        TextButton(enabled = !busy, onClick = { submit { actions.onDecision(epoch, call, MobileAcpOutcome("rejected", reason = reason.takeIf { it.isNotBlank() })) } }) { Text("Reject") }
        TextButton(enabled = !busy, onClick = { submit { actions.onDecision(epoch, call, MobileAcpOutcome("cancelled")) } }) { Text("Skip") }
    }
}

@Composable
private fun AcpActionCard(
    identity: String,
    tag: String,
    lockOnSuccess: Boolean = false,
    content: @Composable (busy: Boolean, submit: (suspend () -> String?) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(identity) { mutableStateOf(false) }
    var failure by remember(identity) { mutableStateOf<String?>(null) }
    SurfaceCard(tag) {
        content(busy) { action ->
            if (!busy) {
                busy = true
                failure = null
                scope.launch {
                    failure = action()
                    if (failure != null || !lockOnSuccess) busy = false
                }
            }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SurfaceCard(tag: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(14.dp)) { content() } }
}
