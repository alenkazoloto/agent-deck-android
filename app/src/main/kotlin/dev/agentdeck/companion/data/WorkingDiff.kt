package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileWorkingDiff
import com.github.claudeagents.core.mobile.MobileWorkingDiffFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The `/diff` sheet: the machine's answer, and the one file whose patch is open. */
data class WorkingDiffSheet(
    val key: String,
    /** Null while the machine is first read. */
    val diff: MobileWorkingDiff? = null,
    /** The file open in the patch view; null while the list is showing. */
    val openPath: String? = null,
    /** [openPath]'s row, its patch read alone when the list came without it. */
    val openFile: MobileWorkingDiffFile? = null,
    val loadingFile: Boolean = false,
    /** Why the machine could not be read. */
    val error: String? = null,
)

/**
 * A Codex chat's `/diff` from the phone (M4): every uncommitted change in the chat's repository,
 * untracked files included — the desk's `CodexWorkingDiffDialog` over `/v1/review/{key}/working-diff`.
 * Nothing is written; each open reads the tree afresh, as each desk `/diff` does.
 */
class WorkingDiffFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
) {
    private val _sheet = MutableStateFlow<WorkingDiffSheet?>(null)
    val sheet: StateFlow<WorkingDiffSheet?> = _sheet.asStateFlow()
    private var reading: Job? = null

    fun open(key: String) {
        _sheet.value = WorkingDiffSheet(key)
        read(key)
    }

    /** Again, after an error or a "still reading" answer — the open file's read, or the list. */
    fun refresh() {
        val current = _sheet.value ?: return
        val open = current.openPath
        if (open != null) {
            if (current.openFile == null) openFile(open)
            return
        }
        val key = current.key
        _sheet.value = WorkingDiffSheet(key)
        read(key)
    }

    fun dismiss() {
        reading?.cancel()
        _sheet.value = null
    }

    /** A different machine: its tree means nothing there. */
    fun forget() = dismiss()

    fun openFile(path: String) {
        val sheet = _sheet.value ?: return
        val row = sheet.diff?.files?.firstOrNull { it.path == path } ?: return
        if (row.patch != null) {
            _sheet.value = sheet.copy(openPath = path, openFile = row, loadingFile = false, error = null)
            return
        }
        _sheet.value = sheet.copy(openPath = path, openFile = null, loadingFile = true, error = null)
        val client = client() ?: return
        val asked = generation()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.workingDiff(sheet.key, path) } }
            if (asked != generation()) return@launch
            _sheet.update { open ->
                // A late answer for a file since closed, or for a sheet since reopened, lands nowhere.
                if (open == null || open.key != sheet.key || open.openPath != path) return@update open
                outcome.fold(
                    onSuccess = { alone ->
                        val file = alone.files.firstOrNull { it.path == path }
                        // Only an answer about the tree may say the file is clean; a slow or
                        // unreachable git has not said anything about it yet.
                        val answered = alone.outcome == MobileWorkingDiff.CHANGES || alone.outcome == MobileWorkingDiff.NONE
                        open.copy(
                            openFile = file,
                            loadingFile = false,
                            error = if (file != null) null else if (answered) GONE else alone.message ?: SLOW_FILE,
                        )
                    },
                    onFailure = { open.copy(loadingFile = false, error = describe(it)) },
                )
            }
        }
    }

    /** Back from a patch to the list. */
    fun closeFile() = _sheet.update { it?.copy(openPath = null, openFile = null, loadingFile = false, error = null) }

    private fun read(key: String) {
        reading?.cancel()
        val client = client() ?: return
        val asked = generation()
        reading = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.workingDiff(key) } }
            if (asked != generation()) return@launch
            _sheet.update { open ->
                if (open?.key != key) return@update open
                outcome.fold(
                    onSuccess = { open.copy(diff = it, error = null) },
                    onFailure = { open.copy(error = describe(it)) },
                )
            }
        }
    }

    companion object {
        const val SLOW_FILE = "The machine could not read this file's patch yet. Try again in a moment."
        const val GONE = "This file has no uncommitted changes any more. Go back to read the list again."
    }
}
