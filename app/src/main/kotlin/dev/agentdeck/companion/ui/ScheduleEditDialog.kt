package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleDependencySelection
import com.github.claudeagents.core.mobile.MobileScheduleEditDetail
import com.github.claudeagents.core.mobile.MobileScheduleEditRequest

internal fun scheduleEditInitial(detail: MobileScheduleEditDetail, saved: String): MobileScheduleEditRequest =
    runCatching { MobileProtocol.parseObject(saved)?.let { MobileScheduleEditRequest.fromJson(it, validate = false) } }.getOrNull()
        ?: MobileScheduleEditRequest(
            prompt = detail.prompt,
            whenChoice = if (detail.selectedDependencies.isEmpty()) "keep" else "dependencies",
            delayAmount = scheduleDelayAmount(detail.repeatEveryMs),
            delayUnit = if (detail.repeatEveryMs > 0 && !scheduleWholeHours(detail.repeatEveryMs)) "minutes" else "hours",
            timeOfDay = detail.repeatAtTime ?: java.time.Instant.ofEpochMilli(detail.dueAtMs)
                .atZone(java.time.ZoneId.of(detail.timeZoneId)).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            repeat = detail.repeatEveryMs > 0 || detail.repeatAtTime != null,
            newChat = detail.sessionId == null,
            model = detail.model,
            accountId = detail.accountId,
            dependencies = detail.selectedDependencies,
        )

@Composable
internal fun ScheduleEditDialog(
    id: String,
    detail: MobileScheduleEditDetail?,
    savedDraft: String,
    loading: Boolean,
    saving: Boolean,
    error: String?,
    onDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    canRetry: Boolean = true,
    onSave: (MobileScheduleEditRequest) -> Unit,
) {
    var form by remember(id, detail) {
        mutableStateOf(detail?.let { scheduleEditInitial(it, savedDraft) })
    }
    var newChatAccount by remember(id, detail) {
        mutableStateOf(runCatching { MobileProtocol.parseObject(savedDraft)?.get("newChatAccountId")?.asString }
            .getOrNull() ?: form?.accountId)
    }
    fun change(next: MobileScheduleEditRequest) {
        form = next
        if (next.newChat) newChatAccount = next.accountId
        onDraft(next.toJson().apply { addProperty("newChatAccountId", newChatAccount) }.toString())
    }
    val value = form
    val valid = value != null && value.prompt.isNotBlank() && when (value.whenChoice) {
        "in" -> value.delayAmount in 1..999
        "at" -> value.timeOfDay.matches(Regex("([01]?\\d|2[0-3]):[0-5]\\d"))
        "dependencies" -> value.dependencies.isNotEmpty()
        "reset" -> detail?.accountOptions?.any { it.id == value.accountId && it.resetAtMs != null } == true
        else -> true
    }
    ScheduleEditorWindow(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Edit scheduled prompt") },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .testTag("schedule-edit-form"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator()
                    Text("Loading schedule…")
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    if (detail == null && canRetry) TextButton(onClick = onRetry) { Text("Retry") }
                    else if (detail != null) Text("Your edits are saved. Try saving again.")
                }
                if (detail != null && value != null) {
                    detail.projectPath?.let { Text(it.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium) }
                    if (!detail.editable) {
                        Text(detail.editUnavailableReason ?: "This prompt is already running and cannot be edited.")
                        Text(detail.prompt)
                    } else {
                        OutlinedTextField(
                            value = value.prompt, onValueChange = { change(value.copy(prompt = it)) },
                            label = { Text("Prompt") }, minLines = 3, maxLines = 6,
                            modifier = Modifier.fillMaxWidth().testTag("schedule-edit-prompt"),
                        )
                        Text("Run", style = MaterialTheme.typography.titleSmall)
                        val reset = detail.accountOptions.firstOrNull { it.id == value.accountId }?.resetAtMs
                        val choices = listOf(
                            "keep" to if (detail.afterSessionId != null) "Keep waiting for the current run" else
                                "Keep scheduled time · ${scheduleTime(detail.dueAtMs, detail.timeZoneId)}",
                            "in" to "In",
                            "at" to "At",
                            "reset" to "After the usage limit resets",
                            "dependencies" to "After sessions finish",
                        )
                        Column {
                            choices.forEach { (key, label) ->
                                val enabled = key != "reset" || reset != null
                                ScheduleChoice(label, value.whenChoice == key, enabled) { change(value.copy(whenChoice = key, repeat = value.repeat && key != "dependencies" && !(key == "keep" && detail.afterSessionId != null))) }
                                if (value.whenChoice == key) when (key) {
                                    "in" -> Row(
                                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedTextField(
                                            value = value.delayAmount.takeIf { it > 0 }?.toString().orEmpty(),
                                            onValueChange = { text -> if (text.length <= 3 && text.all(Char::isDigit))
                                                change(value.copy(delayAmount = text.toIntOrNull() ?: 0)) },
                                            label = { Text("Amount") }, singleLine = true,
                                            modifier = Modifier.weight(1f).testTag("schedule-edit-delay"),
                                        )
                                        Selector(listOf(SelectorOption("hours", "hours"), SelectorOption("minutes", "minutes")),
                                            value.delayUnit, { change(value.copy(delayUnit = it)) })
                                    }
                                    "at" -> OutlinedTextField(
                                        value = value.timeOfDay,
                                        onValueChange = { change(value.copy(timeOfDay = it)) },
                                        label = { Text("Time (HH:mm)") }, singleLine = true,
                                        supportingText = { Text("${detail.timeZoneId} · past times run tomorrow") },
                                        modifier = Modifier.fillMaxWidth().testTag("schedule-edit-time"),
                                    )
                                    "reset" -> reset?.let { Text(scheduleTime(it + 120_000, detail.timeZoneId),
                                        style = MaterialTheme.typography.bodySmall) }
                                    "dependencies" -> ScheduleDependencyChoices(detail, value, ::change)
                                }
                            }
                        }
                        if (value.whenChoice != "dependencies" && !(value.whenChoice == "keep" && detail.afterSessionId != null) && (detail.canRepeat || value.repeat)) {
                            ScheduleCheck(repeatLabel(value, detail), value.repeat) { change(value.copy(repeat = it)) }
                        }
                        Text("Where", style = MaterialTheme.typography.titleSmall)
                        Selector(
                            options = buildList {
                                if (detail.sessionId != null) add(SelectorOption("This chat", false))
                                add(SelectorOption("New ${detail.vendor.lowercase().replaceFirstChar(Char::uppercase)} chat", true))
                            },
                            selected = value.newChat, onSelect = {
                                change(value.copy(newChat = it, accountId = if (it) newChatAccount ?: detail.accountId else detail.accountId))
                            },
                        )
                        if (value.newChat && detail.accountOptions.isNotEmpty()) {
                            Selector(detail.accountOptions.map { SelectorOption(it.label, it.id) },
                                value.accountId.orEmpty(), { change(value.copy(accountId = it)) }, prefix = "Account")
                        } else {
                            Text("Account: ${detail.accountOptions.firstOrNull { it.id == detail.accountId }?.label ?: detail.accountId.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = value.model.orEmpty(), onValueChange = { change(value.copy(model = it)) },
                            label = { Text("Model") }, placeholder = { Text("Default model") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("schedule-edit-model"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = detail?.editable == true && valid && !loading && !saving,
                onClick = { value?.let(onSave) }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScheduleEditorWindow(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.92f),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        Box(Modifier.padding(24.dp)) { title() }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) { text() }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleChoice(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp), color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
    }
}

@Composable
private fun ScheduleCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ScheduleDependencyChoices(
    detail: MobileScheduleEditDetail,
    form: MobileScheduleEditRequest,
    onChange: (MobileScheduleEditRequest) -> Unit,
) {
    if (detail.dependencySources.isEmpty()) Text("No sessions or pending schedules in this project.")
    detail.dependencySources.forEach { source ->
        val selected = form.dependencies.firstOrNull { it.id == source.id }
        ScheduleCheck("${source.label} · ${source.status}", selected != null) { checked ->
            onChange(form.copy(dependencies = form.dependencies.filterNot { it.id == source.id } +
                if (checked) listOf(MobileScheduleDependencySelection(source.id, false)) else emptyList()))
        }
        if (selected != null) {
            Column(Modifier.padding(start = 24.dp)) {
                ScheduleCheck("Include context", selected.inheritContext) { checked ->
                    onChange(form.copy(dependencies = form.dependencies.map {
                        if (it.id == source.id) it.copy(inheritContext = checked) else it
                    }))
                }
            }
        }
    }
}

private fun scheduleTime(ms: Long, zone: String): String = java.time.Instant.ofEpochMilli(ms)
    .atZone(runCatching { java.time.ZoneId.of(zone) }.getOrDefault(java.time.ZoneId.systemDefault()))
    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))

private fun repeatLabel(form: MobileScheduleEditRequest, detail: MobileScheduleEditDetail): String = when (form.whenChoice) {
    "in" -> "Repeat every ${form.delayAmount} ${form.delayUnit}"
    "at" -> "Repeat daily"
    "reset" -> if (detail.accountOptions.firstOrNull { it.id == form.accountId }?.weeklyLimit == true)
        "Repeat each week" else "Repeat each usage window (~5 h)"
    else -> when {
        detail.repeatAtTime != null -> "Repeat daily"
        detail.repeatEveryMs > 0 -> "Repeat every ${detail.repeatEveryMs / 60_000} minutes"
        else -> "Repeat daily"
    }
}

private fun scheduleWholeHours(interval: Long) = interval % 3_600_000L == 0L && interval / 3_600_000L in 1..999
private fun scheduleDelayAmount(interval: Long): Int = when {
    interval <= 0 -> 1
    scheduleWholeHours(interval) -> (interval / 3_600_000L).toInt()
    else -> (interval / 60_000L).coerceIn(1, 999).toInt()
}
