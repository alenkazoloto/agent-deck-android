package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileWorktreeActionRequest
import com.github.claudeagents.core.mobile.MobileWorktreeActionResult
import com.github.claudeagents.core.mobile.MobileWorktreeFleet
import com.github.claudeagents.core.mobile.MobileWorktreeRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** An action waiting for the reader's yes: Merge and Remove confirm, as the desk's dialogs do. */
data class WorktreeConfirm(val row: MobileWorktreeRow, val action: String, val baseBranch: String?)

/**
 * The "Manage worktrees" sheet: the machine's rows, the action awaiting confirmation, the one
 * running ([working] is its row's path), and why the last one did not land.
 */
data class WorktreeSheet(
    val projectPath: String,
    val fleet: MobileWorktreeFleet? = null,
    val confirm: WorktreeConfirm? = null,
    val working: String? = null,
    val error: String? = null,
) {
    val loaded: Boolean get() = fleet != null
}

/**
 * "Manage worktrees…" on the phone (M4, P22): the desk's Merge, Remove and Prune, run and re-checked
 * on the machine. A write that outlasts one request is followed under its operation id, and one
 * whose answer never arrived keeps that id, so trying the same action again reads it back rather
 * than running git twice. Every outcome re-reads the rows, which are the machine's, not a guess.
 */
class WorktreeManageFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
    private val snack: (String) -> Unit,
    private val pollMs: Long = POLL_MS,
) {
    private var uncertain: MobileWorktreeActionRequest? = null
    private var reading: Job? = null
    private val _sheet = MutableStateFlow<WorktreeSheet?>(null)
    val sheet: StateFlow<WorktreeSheet?> = _sheet.asStateFlow()

    fun open(projectPath: String) {
        _sheet.value = WorktreeSheet(projectPath)
        reload()
    }

    fun close() {
        reading?.cancel()
        _sheet.value = null
    }

    /** Merge and Remove ask first; Prune runs at once, as the desk's menu entry does. */
    fun ask(row: MobileWorktreeRow, action: String) {
        val sheet = _sheet.value?.takeIf { it.working == null } ?: return
        if (action == MobileWorktreeActionRequest.PRUNE) return run(sheet, WorktreeConfirm(row, action, sheet.fleet?.baseBranch))
        _sheet.value = sheet.copy(confirm = WorktreeConfirm(row, action, sheet.fleet?.baseBranch), error = null)
    }

    fun dismiss() = _sheet.update { it?.copy(confirm = null) }

    fun confirm() {
        val sheet = _sheet.value ?: return
        run(sheet, sheet.confirm ?: return)
    }

    private fun run(sheet: WorktreeSheet, confirm: WorktreeConfirm) {
        val client = client() ?: return
        val asked = generation()
        val row = confirm.row
        // The count the confirmation named: the machine refuses if the tree's has moved since.
        val discardFiles = row.dirtyFiles.takeIf { confirm.action == MobileWorktreeActionRequest.REMOVE && row.discardWarning != null }
        val fresh = MobileWorktreeActionRequest(
            sheet.projectPath, row.path, confirm.action, row.branch,
            confirm.baseBranch.takeIf { confirm.action == MobileWorktreeActionRequest.MERGE }, discardFiles, "",
        )
        val request = fresh.copy(operationId = uncertain?.takeIf { it.copy(operationId = "") == fresh }?.operationId ?: UUID.randomUUID().toString())
        _sheet.value = sheet.copy(confirm = null, working = row.path, error = null)
        scope.launch {
            var outcome: Result<MobileWorktreeActionResult>
            var waited = 0L
            var acknowledged = false
            while (true) {
                outcome = withContext(Dispatchers.IO) { runCatching { client.worktreeAction(request) } }
                if (asked != generation()) return@launch
                val working = outcome.getOrNull()?.state == MobileWorktreeActionResult.WORKING
                acknowledged = acknowledged || working
                if (!working || waited >= MAX_WAIT_MS) break
                delay(pollMs)
                waited += pollMs
            }
            val error = outcome.fold(
                onSuccess = { result ->
                    when {
                        result.done -> { uncertain = null; snack(result.message); null }
                        result.state == MobileWorktreeActionResult.WORKING -> { uncertain = request; STILL_WORKING }
                        else -> { uncertain = null; result.message.ifBlank { "The command could not be run." } }
                    }
                },
                // Once the machine said "working", a later refusal leaves that write running.
                onFailure = { uncertain = if (it is BridgeRefusal && !acknowledged) null else request; describe(it) },
            )
            _sheet.update { it?.copy(working = null, error = error) }
            if (_sheet.value != null) reload()
        }
    }

    private fun reload() {
        val sheet = _sheet.value ?: return
        val client = client() ?: return
        val asked = generation()
        reading?.cancel()
        reading = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.worktreeFleet(sheet.projectPath) } }
            if (asked != generation() || _sheet.value?.projectPath != sheet.projectPath) return@launch
            val fleet = outcome.getOrElse { MobileWorktreeFleet(sheet.projectPath, refused = describe(it)) }
            _sheet.update { it?.copy(fleet = fleet) }
        }
    }

    /** A different machine: its rows and a pending id mean nothing there. */
    fun forget() {
        uncertain = null
        close()
    }

    companion object {
        const val POLL_MS = 1_500L
        const val MAX_WAIT_MS = 5 * 60_000L
        const val STILL_WORKING =
            "The machine is still working on this. Try the same action again to check on it; git is not run twice."
    }
}
