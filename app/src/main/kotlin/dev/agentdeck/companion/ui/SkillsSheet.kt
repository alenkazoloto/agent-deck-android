package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import com.github.claudeagents.core.mobile.MobileMarketplaceChange
import com.github.claudeagents.core.mobile.MobileAvailablePlugin
import com.github.claudeagents.core.mobile.MobilePluginInstall
import com.github.claudeagents.core.mobile.MobilePluginToggle
import com.github.claudeagents.core.mobile.MobilePluginUninstall
import com.github.claudeagents.core.mobile.MobilePluginUpdate
import com.github.claudeagents.core.mobile.MobileSkillCopy
import com.github.claudeagents.core.mobile.MobileSkillCreate
import com.github.claudeagents.core.mobile.MobileSkillRow
import com.github.claudeagents.core.mobile.MobileSkillState
import com.github.claudeagents.core.mobile.MobileSkillsList
import com.github.claudeagents.core.mobile.MobileSkillsProject

/**
 * Settings › Resources › "Skills, agents and plugins": the skills, legacy commands, subagents and installed
 * plugins the desk's Settings › Skills, Subagents and Plugins list for an open project, read-only.
 *
 * Read again each time the sheet opens and whenever another project is picked, so a skill written on the
 * desk is there. The machine sends a description (name, the first lines of what it does, where it comes
 * from and why it is not simply on), never a file, so what is shown is all there is. The project chips
 * appear only when more than one project is open. A plugin row's "Turn on" / "Turn off" ([onToggle], null on a machine or
 * a phone without `plugin-toggle`) is the desk's Enable / Disable: the machine answers with its own sentence when it did
 * not change it, and the list is read again either way so what is shown is what is there. "New skill…" ([onCreate], null without `skill-create`) is the desk's New Skill dialog
 * for a name, scope, description and who may invoke it — never its script, model, effort or access. "Edit file" ([onEditFile], null without
 * `skill-file`, offered on a row the machine marks [MobileSkillRow.fileEditable]) is the desk's "Open SKILL.md": a full-screen text editor
 * whose typed text is kept while it is unsaved ([hasFileDraft] says so on the row). Editing a subagent's file or a hook, and installing a
 * plugin, stay on the desk. "Copy to project" / "Copy to personal" ([onCopy], null without `skill-copy`) is the desk's
 * copy link on a directory skill: a dialog asks the copy's name, and a name the machine says is taken stays open with its sentence. A skill or command row's four state chips ([onState], null without `skill-state`) are the desk's state combo, offered only where the machine says it would write the state. "Update" ([onUpdate], null without `plugin-update`) is the desk's Update on a
 * marketplace plugin, one tap and no dialog as on the desk; an archive the desk asks about comes back as the machine's sentence. "Uninstall…" ([onUninstall], null without `plugin-uninstall`) is the
 * desk's Uninstall on a marketplace plugin: a dialog first, then the same answer and re-read as a toggle. An "Available plugins" section ([onInstall], null
 * without `plugin-install`, so nothing is offered where the owner has not granted it) lists what the machine's marketplaces offer and it has not
 * installed, filtered by a search field over the list the machine sent: the desk's Plugin Catalog Install, one tap and no dialog as on the desk,
 * answered "Installed X." or the machine's sentence (an archive the desk asks about comes back as it). A "Marketplaces" section lists
 * the machine's plugin catalogs by name and kind of source (never where from), each with "Refresh" ([onRefreshMarketplace], null without
 * `marketplace-refresh`) and "Remove…" ([onRemoveMarketplace], null without `marketplace-remove`, a dialog in the desk's words first).
 * Adding a marketplace names a source to fetch and later run, so it stays on the desk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillsSheet(
    onLoad: suspend (String?) -> MobileSkillsList?,
    onDismiss: () -> Unit,
    onToggle: (suspend (MobilePluginToggle) -> String?)? = null,
    onUninstall: (suspend (MobilePluginUninstall) -> String?)? = null,
    onUpdate: (suspend (MobilePluginUpdate) -> String?)? = null,
    onInstall: (suspend (MobilePluginInstall) -> String?)? = null,
    onState: (suspend (MobileSkillState) -> String?)? = null,
    onCopy: (suspend (MobileSkillCopy) -> String?)? = null,
    onCreate: (suspend (MobileSkillCreate) -> String?)? = null,
    onEditFile: ((project: String, row: MobileSkillRow) -> Unit)? = null,
    hasFileDraft: (project: String, row: MobileSkillRow) -> Boolean = { _, _ -> false },
    onRefreshMarketplace: (suspend (MobileMarketplaceChange) -> String?)? = null,
    onRemoveMarketplace: (suspend (MobileMarketplaceChange) -> String?)? = null,
) {
    var project by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<MobileSkillsList?>(null) }
    var loading by remember { mutableStateOf(true) }
    // Kept across a load that fails, so the chips survive a tap on a project the machine did not answer for.
    var known by remember { mutableStateOf(emptyList<MobileSkillsProject>()) }
    val load by rememberUpdatedState(onLoad)
    val toggle by rememberUpdatedState(onToggle)
    val uninstall by rememberUpdatedState(onUninstall)
    val update by rememberUpdatedState(onUpdate)
    val install by rememberUpdatedState(onInstall)
    var availableQuery by remember { mutableStateOf("") }
    var installingId by remember { mutableStateOf<String?>(null) }
    val stateChange by rememberUpdatedState(onState)
    val copyChange by rememberUpdatedState(onCopy)
    var copying by remember { mutableStateOf<MobileSkillRow?>(null) }
    val createSkill by rememberUpdatedState(onCreate)
    var creating by remember { mutableStateOf(false) }
    val refreshMarketplace by rememberUpdatedState(onRefreshMarketplace)
    val removeMarketplace by rememberUpdatedState(onRemoveMarketplace)
    var asking by remember { mutableStateOf<MobileSkillRow?>(null) }
    var askingMarketplace by remember { mutableStateOf<MobileSkillRow?>(null) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var changing by remember { mutableStateOf(false) }
    var changeNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(project, reload) {
        loading = true
        loaded = load(project)
        loaded?.projects?.takeIf { it.size > 1 }?.let { known = it }
        loading = false
    }
    asking?.let { plugin ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Uninstall \"${plugin.name}\"?") },
            text = { Text(PLUGIN_UNINSTALL_WORDS) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = null
                        changing = true
                        changeNote = null
                        val asked = MobilePluginUninstall(plugin.id, loaded?.project.orEmpty())
                        scope.launch {
                            changeNote = uninstall?.invoke(asked)
                            changing = false
                            reload++
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("plugin-uninstall-confirm"),
                ) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") } },
        )
    }
    copying?.let { row ->
        SkillCopyDialog(
            row = row,
            project = loaded?.project.orEmpty(),
            onCopy = { asked -> copyChange?.invoke(asked) },
            onDone = { copying = null; reload++ },
            onDismiss = { copying = null },
        )
    }
    if (creating) {
        SkillCreateDialog(
            project = loaded?.project.orEmpty(),
            onCreate = { asked -> createSkill?.invoke(asked) },
            onDone = { creating = false; reload++ },
            onDismiss = { creating = false },
        )
    }
    askingMarketplace?.let { marketplace ->
        AlertDialog(
            onDismissRequest = { askingMarketplace = null },
            title = { Text("Remove Marketplace") },
            text = { Text(marketplaceRemoveWords(marketplace.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingMarketplace = null
                        changing = true
                        changeNote = null
                        val asked = MobileMarketplaceChange(marketplace.name, loaded?.project.orEmpty())
                        scope.launch {
                            changeNote = removeMarketplace?.invoke(asked)
                            changing = false
                            reload++
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("marketplace-remove-confirm"),
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askingMarketplace = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") } },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Skills, agents and plugins", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            val result = loaded
            if (known.size > 1) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    known.forEach { open ->
                        FilterChip(selected = open.path == (project ?: result?.project), onClick = { project = open.path }, label = { Text(open.name) })
                    }
                }
            }
            if (onCreate != null && result != null) {
                TextButton(onClick = { creating = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("skill-new")) { Text("New skill…") }
            }
            when {
                loading -> Note("Reading the machine's skills, agents and plugins…")
                result == null -> Note("The machine did not answer. Close this and try again.")
                else -> {
                    result.notes.forEach { Note(it) }
                    changeNote?.let { Note(it) }
                    if (result.rows.isEmpty() && result.notes.isEmpty()) Note("This project has no skills, commands, subagents or plugins.")
                    val sections = skillSections(result.rows)
                    val offered = if (onInstall != null) result.available else emptyList()
                    val startInstall: (MobileAvailablePlugin) -> Unit = { plugin ->
                        changing = true
                        installingId = plugin.id
                        changeNote = "Installing ${plugin.name} for this Claude account…"
                        val asked = MobilePluginInstall(plugin.id, result.project)
                        scope.launch {
                            changeNote = install?.invoke(asked) ?: "Installed ${plugin.name}."
                            installingId = null
                            changing = false
                            reload++
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 560.dp).testTag("skills-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // Per run of this block, not per composition: the search field re-runs it and the section must come back each time.
                        var availableShown = false
                        sections.forEach { (title, rows) ->
                            if (!availableShown && rows.first().kind == "marketplace") {
                                availableShown = true
                                availableSection(offered, availableQuery, { availableQuery = it }, installingId, !changing, startInstall)
                            }
                            item(key = "h/$title") {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            // The index keeps two same-named rows (one shadowing the other) from sharing a key, which would crash the list.
                            itemsIndexed(rows, key = { index, row -> "${row.kind}/$index/${row.name}" }) { _, row ->
                                val change = toggle
                                if (row.kind == "marketplace") {
                                    MarketplaceRow(
                                        row,
                                        if (refreshMarketplace != null && !changing) {
                                            {
                                                changing = true
                                                changeNote = null
                                                val asked = MobileMarketplaceChange(row.name, result.project)
                                                scope.launch {
                                                    changeNote = refreshMarketplace?.invoke(asked)
                                                    changing = false
                                                    reload++
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                        if (removeMarketplace != null && !changing) ({ askingMarketplace = row }) else null,
                                    )
                                    return@itemsIndexed
                                }
                                SkillRow(
                                    row,
                                    if (change != null && row.id.isNotEmpty() && !changing) {
                                        {
                                            changing = true
                                            changeNote = null
                                            val asked = MobilePluginToggle(row.id, !row.enabled, result.project)
                                            scope.launch {
                                                changeNote = change(asked)
                                                changing = false
                                                reload++
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    if (stateChange != null && row.stateEditable && !changing) {
                                        { picked ->
                                            changing = true
                                            changeNote = null
                                            val asked = MobileSkillState(row.name.removePrefix("/"), picked, result.project)
                                            scope.launch {
                                                changeNote = stateChange?.invoke(asked)
                                                changing = false
                                                reload++
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    if (uninstall != null && row.id.isNotEmpty() && row.removable && !changing) ({ asking = row }) else null,
                                    if (copyChange != null && row.copyTo.isNotEmpty() && !changing) ({ copying = row }) else null,
                                    if (update != null && row.id.isNotEmpty() && row.removable && !changing) {
                                        {
                                            changing = true
                                            changeNote = null
                                            val asked = MobilePluginUpdate(row.id, result.project)
                                            scope.launch {
                                                changeNote = update?.invoke(asked)
                                                changing = false
                                                reload++
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    onEditFile = if (onEditFile != null && row.fileEditable && !changing) ({ onEditFile(result.project, row) }) else null,
                                    editFileLabel = if (hasFileDraft(result.project, row)) "Edit file (unsaved changes)" else "Edit file",
                                )
                            }
                        }
                        if (!availableShown) availableSection(offered, availableQuery, { availableQuery = it }, installingId, !changing, startInstall)
                    }
                }
            }
        }
    }
}

/**
 * "Available plugins": what the machine's marketplaces list and it has not installed, below the installed plugins and above the catalogs
 * they come from. The search field filters the list the machine sent (name, marketplace, description). [canInstall] is false while
 * another change runs, so one tap cannot start two.
 */
private fun LazyListScope.availableSection(
    available: List<MobileAvailablePlugin>,
    query: String,
    onQuery: (String) -> Unit,
    installingId: String?,
    canInstall: Boolean,
    onInstall: (MobileAvailablePlugin) -> Unit,
) {
    if (available.isEmpty()) return
    val shown = availableMatching(available, query)
    item(key = "h/available") {
        Column {
            Text(
                "Available plugins (${available.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text("Search available plugins") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("available-search"),
            )
            if (shown.isEmpty()) Detail("No available plugin matches \"${query.trim()}\".")
        }
    }
    itemsIndexed(shown, key = { _, plugin -> "available/${plugin.id}" }) { _, plugin ->
        Column(Modifier.fillMaxWidth().testTag("available-row").padding(top = 8.dp)) {
            Text(plugin.name, style = MaterialTheme.typography.bodyLarge)
            Detail(availableSourceLine(plugin))
            if (plugin.description.isNotEmpty()) Detail(plugin.description, maxLines = 3)
            TextButton(
                onClick = { onInstall(plugin) },
                enabled = canInstall,
                modifier = Modifier.heightIn(min = 48.dp).testTag("plugin-install-${plugin.id}"),
            ) { Text(if (installingId == plugin.id) "Installing…" else "Install") }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** One plugin catalog: its name and kind of source. Never where it is fetched from, which can be a folder or a URL with a credential. */
@Composable
private fun MarketplaceRow(row: MobileSkillRow, onRefresh: (() -> Unit)?, onRemove: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().testTag("skills-row").padding(top = 8.dp)) {
        Text(row.name, style = MaterialTheme.typography.bodyLarge)
        Detail(skillSourceLine(row))
        if (row.detail.isNotEmpty()) Detail(row.detail)
        if (onRefresh != null || onRemove != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRefresh != null) {
                    TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp).testTag("marketplace-refresh-${row.name}")) { Text("Refresh") }
                }
                if (onRemove != null) {
                    TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp).testTag("marketplace-remove-${row.name}")) {
                        Text("Remove…", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillRow(
    row: MobileSkillRow,
    onToggle: (() -> Unit)?,
    onState: ((String) -> Unit)?,
    onUninstall: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onUpdate: (() -> Unit)?,
    onEditFile: (() -> Unit)? = null,
    editFileLabel: String = "Edit file",
) {
    Column(Modifier.fillMaxWidth().testTag("skills-row").padding(top = 8.dp)) {
        Text(row.name, style = MaterialTheme.typography.bodyLarge)
        Detail(skillSourceLine(row))
        if (row.description.isNotEmpty()) Detail(row.description, maxLines = 3)
        if (row.detail.isNotEmpty()) Detail(row.detail)
        if (onState != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobileSkillState.SKILL_STATES.forEach { state ->
                    FilterChip(
                        selected = row.state == state,
                        onClick = { if (row.state != state) onState(state) },
                        label = { Text(skillStateLabel(state)) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("skill-state-${row.name}-$state"),
                    )
                }
            }
        }
        if (onToggle != null || onUninstall != null || onUpdate != null || onCopy != null || onEditFile != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onEditFile != null) {
                    TextButton(onClick = onEditFile, modifier = Modifier.heightIn(min = 48.dp).testTag("skill-file-${row.kind}-${row.name}")) { Text(editFileLabel) }
                }
                if (onToggle != null) {
                    TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp).testTag("plugin-toggle-${row.id}")) {
                        Text(if (row.enabled) "Turn off" else "Turn on")
                    }
                }
                if (onCopy != null) {
                    TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp).testTag("skill-copy-${row.name}")) { Text(skillCopyLabel(row)) }
                }
                if (onUpdate != null) {
                    TextButton(onClick = onUpdate, modifier = Modifier.heightIn(min = 48.dp).testTag("plugin-update-${row.id}")) { Text("Update") }
                }
                if (onUninstall != null) {
                    TextButton(onClick = onUninstall, modifier = Modifier.heightIn(min = 48.dp).testTag("plugin-uninstall-${row.id}")) {
                        Text("Uninstall…", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The desk's copy link opened as a dialog: the copy's name, prefilled with the skill's own. Left as it is, the machine names the copy
 * after the source directory; changed, it must be the desk's New Skill name rule, which the button waits for. A sentence the machine
 * answers with (a name already taken) stays here beside the field so another name can be typed, and the dialog closes only when done.
 */
@Composable
private fun SkillCopyDialog(
    row: MobileSkillRow,
    project: String,
    onCopy: suspend (MobileSkillCopy) -> String?,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val own = skillCopyOwnName(row)
    var typed by remember { mutableStateOf(own) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val request = skillCopyRequest(row, typed, project)
    // A Dialog around a full-width Surface, not an AlertDialog: its intrinsic measure of a single-line field never settles under Robolectric (`McpAddDialog`).
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(skillCopyLabel(row), style = MaterialTheme.typography.headlineSmall)
                Text(skillCopyWords(row), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.trim(); failure = null },
                    label = { Text("Name of the copy") },
                    singleLine = true,
                    isError = request == null,
                    supportingText = { Text(failure ?: if (request == null) "Lowercase letters, numbers and hyphens, up to 64." else "This becomes /$typed.") },
                    modifier = Modifier.fillMaxWidth().testTag("skill-copy-name"),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    TextButton(
                        enabled = request != null && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val answer = onCopy(request!!)
                                busy = false
                                if (answer == null) onDone() else failure = answer
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("skill-copy-confirm"),
                    ) { Text("Copy") }
                }
            }
        }
    }
}

/**
 * The desk's New Skill dialog, without the choices that make a chat run something: a name (the desk's rule, checked as it is typed), where
 * it lives, a description and who may invoke it. The machine's sentence (a name already taken) stays here beside the fields so another name
 * can be typed; the dialog closes only when the skill was written. Nothing is sent while the name is not one the machine would take.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillCreateDialog(
    project: String,
    onCreate: suspend (MobileSkillCreate) -> String?,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var personal by remember { mutableStateOf(true) }
    var description by remember { mutableStateOf("") }
    var invocation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val request = skillCreateRequest(name, personal, description, invocation, project)
    // A Dialog around a full-width Surface, not an AlertDialog: its intrinsic measure of a single-line field never settles under Robolectric (`McpAddDialog`).
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New skill", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim(); failure = null },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = name.isNotEmpty() && MobileSkillCreate.nameProblem(name) != null,
                    supportingText = { Text(failure ?: skillNameHint(name)) },
                    modifier = Modifier.fillMaxWidth().testTag("skill-new-name"),
                )
                Text("Where", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = personal, onClick = { personal = true; failure = null }, label = { Text("Personal") }, modifier = Modifier.testTag("skill-new-personal"))
                    FilterChip(selected = !personal, onClick = { personal = false; failure = null }, label = { Text("Project") }, modifier = Modifier.testTag("skill-new-project"))
                }
                Text(
                    if (personal) "Personal skills are available in all your projects." else "A project skill is stored with the repository, so its collaborators get it too.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    minLines = 2,
                    maxLines = 4,
                    isError = description.trim().length > MobileSkillCreate.MAX_DESCRIPTION,
                    supportingText = { Text("${MobileSkillCreate.oneLine(description).length} / ${MobileSkillCreate.MAX_DESCRIPTION}") },
                    modifier = Modifier.fillMaxWidth().testTag("skill-new-description"),
                )
                Text("Who can invoke it", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SKILL_INVOCATIONS.forEach { (value, label) ->
                        FilterChip(selected = invocation == value, onClick = { invocation = value }, label = { Text(label) }, modifier = Modifier.testTag("skill-new-invoke-${value.ifEmpty { "any" }}"))
                    }
                }
                Text(
                    "Model, effort, access and script stay in the IDE: a script runs in its terminal.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    TextButton(
                        enabled = request != null && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val answer = onCreate(request!!)
                                busy = false
                                if (answer == null) onDone() else failure = answer
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("skill-new-confirm"),
                    ) { Text("Create") }
                }
            }
        }
    }
}

@Composable
private fun Detail(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** The desk's own confirmation sentence for Uninstall, so the phone promises what the desk does. */
internal const val PLUGIN_UNINSTALL_WORDS =
    "Its skills, commands, agents, hooks, and MCP servers disappear from new Claude Code sessions."

/** The desk's own confirmation sentence for removing a marketplace (`PluginsPanel.MarketplacesDialog.removeSelected`). */
internal fun marketplaceRemoveWords(name: String): String =
    "Remove \"$name\"? Plugins installed from it stop updating until it is added back."

/** The desk's own words for a skill's state (`SkillsPanel.STATE_LABELS`), so the phone names what the desk's combo does. */
internal fun skillStateLabel(state: String): String = when (state) {
    "name-only" -> "Name only"
    "user-invocable-only" -> "User-invocable only"
    "off" -> "Off"
    else -> "On"
}

/** "Project · Not active (shadowed)": where it comes from and, when it is not simply on, why. */
internal fun skillSourceLine(row: MobileSkillRow): String =
    listOf(row.source, row.status).filter { it.isNotEmpty() }.joinToString(" · ")

/** The sections in the desk's order — skills, commands, then subagents — each headed by its count; an empty one is left out. */
internal fun skillSections(rows: List<MobileSkillRow>): List<Pair<String, List<MobileSkillRow>>> = listOf(
    "skill" to "Skills",
    "command" to "Commands",
    "agent" to "Subagents",
    "plugin" to "Plugins",
    "marketplace" to "Marketplaces",
).mapNotNull { (kind, title) ->
    rows.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { "$title (${it.size})" to it }
}

/** The available plugins whose name, marketplace or description holds every word of [query], in the machine's order; all of them for a blank one. */
internal fun availableMatching(available: List<MobileAvailablePlugin>, query: String): List<MobileAvailablePlugin> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return available
    return available.filter { plugin ->
        val haystack = "${plugin.name} ${plugin.marketplace} ${plugin.description}".lowercase()
        words.all { it in haystack }
    }
}

/** "acme · 1,234 installs": the marketplace it comes from and, when Claude Code's catalog cache has one, how many installed it. */
internal fun availableSourceLine(plugin: MobileAvailablePlugin): String = listOfNotNull(
    plugin.marketplace.takeIf { it.isNotEmpty() },
    plugin.installs?.let { "${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it)} install${if (it == 1) "" else "s"}" },
).joinToString(" · ")

/** The desk's link words: a personal skill goes to the project, a project one to personal. */
internal fun skillCopyLabel(row: MobileSkillRow): String = if (row.copyTo == "project") "Copy to project" else "Copy to personal"

/** What the copy does, in the desk's terms — the project scope is shared with the repository's collaborators. */
internal fun skillCopyWords(row: MobileSkillRow): String =
    if (row.copyTo == "project") "Copies ${row.name} into this project's .claude/skills, where the repository's collaborators get it too. The original stays."
    else "Copies ${row.name} into your personal skills. The original stays."

/** The name a copy has when the reader does not change it: the skill's own, without a slash or a nested `apps/web:` prefix. */
internal fun skillCopyOwnName(row: MobileSkillRow): String = row.name.removePrefix("/").substringAfterLast(':')

/**
 * The request a typed name makes, or null while it is not one the machine would take. The unchanged own name is sent as none, so the
 * machine names the copy after the source directory as the desk does; a changed one is the desk's New Skill rule.
 */
internal fun skillCopyRequest(row: MobileSkillRow, typed: String, project: String): MobileSkillCopy? {
    val toProject = row.copyTo == "project"
    if (typed == skillCopyOwnName(row)) return MobileSkillCopy(row.name.removePrefix("/"), toProject, "", project)
    return typed.takeIf { MobileSkillCopy.NAME_RULE.matches(it) }?.let { MobileSkillCopy(row.name.removePrefix("/"), toProject, it, project) }
}

/** The desk's invocation checkboxes as one choice: anyone (neither box), "Only I can invoke it", "Only Claude can invoke it". */
private val SKILL_INVOCATIONS = listOf("" to "Anyone", "user" to "Only I can invoke it", "model" to "Only Claude can invoke it")

/** Why the typed name or description is not one the machine would take, in the desk's words, or null. */
internal fun skillCreateProblem(name: String, description: String): String? =
    MobileSkillCreate.nameProblem(name)
        ?: if (MobileSkillCreate.oneLine(description).length > MobileSkillCreate.MAX_DESCRIPTION) "Keep the description to 1,024 characters or fewer." else null

/** The line under the name field: the desk's rule until a name breaks it, then which part it breaks; `/name` when it is good. */
internal fun skillNameHint(name: String): String =
    if (name.isEmpty()) "Lowercase letters, numbers and hyphens. This becomes /name."
    else MobileSkillCreate.nameProblem(name) ?: "This becomes /$name."

/** The request the typed fields make, or null while the machine would refuse them. The description is sent as one line. */
internal fun skillCreateRequest(name: String, personal: Boolean, description: String, invocation: String, project: String): MobileSkillCreate? {
    if (skillCreateProblem(name, description) != null) return null
    return MobileSkillCreate(name, personal, MobileSkillCreate.oneLine(description), invocation, project)
}
