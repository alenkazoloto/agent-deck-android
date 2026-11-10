package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileSessionDirs
import com.github.claudeagents.core.mobile.MobileSessionDirsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The working-directories sheet: the chat it names, the directories the machine listed (null while
 * they are read), the path being typed, and the machine's or the link's last sentence.
 */
data class SessionDirsSheet(
    val key: String,
    val title: String,
    val dirs: List<String>? = null,
    /** The path being typed; kept through a refusal and a close, cleared only when the machine took it. */
    val draft: String = "",
    /** The desk's sentence for a path it refused; the list is unchanged. Cleared by the next edit. */
    val refused: String? = null,
    /** Why the machine could not be read or changed, in the machine's or the link's own words. */
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * A chat's additional working directories on the phone (`--add-dir`): listed when the sheet opens,
 * added from a typed path and removed one by one, all through `/v1/session-dirs` under the
 * machine's per-phone grant.
 *
 * A typed path is the reader's work, so it survives a refusal, a failed link and closing the sheet
 * ([drafts]); only the machine taking it clears it. A different machine drops everything ([forget]):
 * its keys, and the paths on its disk, mean nothing on another.
 */
class SessionDirsFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
) {
    private val _sheet = MutableStateFlow<SessionDirsSheet?>(null)
    val sheet: StateFlow<SessionDirsSheet?> = _sheet.asStateFlow()
    private val drafts = HashMap<String, String>()
    private var working: Job? = null

    fun open(key: String, title: String) {
        working?.cancel()
        val client = client() ?: return
        val opened = SessionDirsSheet(key, title, draft = drafts[key].orEmpty(), busy = true)
        _sheet.value = opened
        run(opened, consumesDraft = false) { client.sessionDirs(key) }
    }

    fun edit(text: String) {
        _sheet.update { open ->
            if (open == null) return@update null
            drafts[open.key] = text
            open.copy(draft = text, refused = null)
        }
    }

    /** Adds the typed path; nothing is sent for a blank field or while a change is in flight. */
    fun add() {
        val open = _sheet.value ?: return
        val path = open.draft.trim()
        if (path.isEmpty() || open.busy) return
        change(open, consumesDraft = true) { it.changeSessionDirs(MobileSessionDirsRequest(open.key, add = path)) }
    }

    fun remove(dir: String) {
        val open = _sheet.value ?: return
        if (open.busy) return
        change(open, consumesDraft = false) { it.changeSessionDirs(MobileSessionDirsRequest(open.key, remove = dir)) }
    }

    fun dismiss() {
        working?.cancel()
        _sheet.value = null
    }

    fun forget() {
        drafts.clear()
        dismiss()
    }

    private fun change(open: SessionDirsSheet, consumesDraft: Boolean, send: (BridgeClient) -> MobileSessionDirs) {
        val client = client() ?: return
        val busy = open.copy(busy = true, refused = null, error = null)
        _sheet.value = busy
        run(busy, consumesDraft) { send(client) }
    }

    private fun run(started: SessionDirsSheet, consumesDraft: Boolean, request: () -> MobileSessionDirs) {
        val asked = generation()
        working = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching(request) }
            if (asked != generation()) return@launch
            // Only onto the sheet that asked: closing it and opening another chat must not paint this one's list.
            _sheet.update { open ->
                if (open == null || open.key != started.key) return@update open
                outcome.fold(
                    { answer ->
                        // A draft the machine took is consumed; one it refused stays for the reader to fix.
                        val kept = if (consumesDraft && answer.refused == null && open.draft == started.draft) "" else open.draft
                        if (kept.isEmpty()) drafts.remove(open.key)
                        open.copy(dirs = answer.dirs, draft = kept, refused = answer.refused, error = null, busy = false)
                    },
                    { open.copy(error = describe(it), busy = false) },
                )
            }
        }
    }
}
