package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileMemoryEntries
import com.github.claudeagents.core.mobile.MobileMemoryFile
import com.github.claudeagents.core.mobile.MobileMemoryProject
import com.github.claudeagents.core.mobile.MobileMemorySave
import com.github.claudeagents.core.mobile.MobileMemorySaved
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
 * One memory file open for editing. [savedText] is the last text known to be on the machine, at
 * [revision]; [text] is what the reader has typed, so [dirty] is the difference. A [conflict] is the
 * machine's current file when it moved on from [revision] — kept beside the reader's text, never
 * merged into it.
 */
data class OpenMemoryFile(
    val id: String,
    val label: String,
    val writable: Boolean,
    val existed: Boolean,
    val savedText: String,
    val revision: String,
    val text: String,
    val conflict: MobileMemoryFile? = null,
    /** The machine's sentence for a save it refused, or the link's for one that did not arrive. */
    val error: String? = null,
    /** Set by a save the machine took, until the next keystroke. */
    val saved: Boolean = false,
) {
    val dirty: Boolean get() = text != savedText
}

/**
 * The Memory sheet: the machine's open projects, one project's files, and one file open in an editor.
 * Each stage is [projects] → [entries] → [open]; a null later stage is "not chosen yet", a null
 * earlier one "not read yet" ([busy] says a read is in flight).
 */
data class MemorySheet(
    val projects: List<MobileMemoryProject>? = null,
    val project: MobileMemoryProject? = null,
    val entries: MobileMemoryEntries? = null,
    val open: OpenMemoryFile? = null,
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * The desk Memory tab's files on the phone (`/v1/memory`): pick an open project, pick a file, edit
 * it as text, save it against the revision that was read.
 *
 * **Typed text is the reader's work.** An edit that differs from the machine's text is kept per
 * (project, file) in [drafts] with the revision it was made from, through Back, closing the sheet, a
 * refused save and a failed link; only the machine taking it, or the reader choosing the machine's
 * text, drops it. Reopening a file whose text has moved on since brings the draft back *with* the
 * machine's version as a [OpenMemoryFile.conflict] — the reader sees that the file changed before
 * pressing Save, and Save would otherwise say the same. A different machine drops everything
 * ([forget]): its projects and files mean nothing on another.
 */
class MemoryFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
) {
    private val _sheet = MutableStateFlow<MemorySheet?>(null)
    val sheet: StateFlow<MemorySheet?> = _sheet.asStateFlow()
    private val drafts = HashMap<String, Draft>()
    private var working: Job? = null

    private class Draft(val text: String, val savedText: String, val revision: String)

    /** Opens on the machine's projects; with exactly one open, straight onto its files. */
    fun open() {
        working?.cancel()
        val client = client() ?: return
        _sheet.value = MemorySheet(busy = true)
        read { client.memoryProjects() }.then { answer ->
            val only = answer.projects.singleOrNull()
            _sheet.update { it?.copy(projects = answer.projects, busy = false) }
            if (only != null) chooseProject(only)
        }
    }

    fun chooseProject(project: MobileMemoryProject) {
        val client = client() ?: return
        _sheet.update { it?.copy(project = project, entries = null, open = null, error = null, busy = true) }
        read { client.memoryEntries(project.path) }.then { answer ->
            _sheet.update { open -> if (open?.project != project) open else open.copy(entries = answer, busy = false) }
        }
    }

    fun openFile(id: String) {
        val client = client() ?: return
        val project = _sheet.value?.project ?: return
        _sheet.update { it?.copy(error = null, busy = true) }
        read { client.memoryFile(project.path, id) }.then { file ->
            _sheet.update { sheet ->
                if (sheet?.project != project) return@update sheet
                if (file.unavailable != null) return@update sheet.copy(error = file.unavailable, busy = false)
                sheet.copy(open = opened(project, file), error = null, busy = false)
            }
        }
    }

    fun edit(text: String) {
        _sheet.update { sheet ->
            val open = sheet?.open ?: return@update sheet
            val key = key(sheet.project ?: return@update sheet, open.id)
            if (text == open.savedText) drafts.remove(key) else drafts[key] = Draft(text, open.savedText, open.revision)
            sheet.copy(open = open.copy(text = text, saved = false, error = null))
        }
    }

    /** Saves the whole text; nothing is sent for an untouched file, a read-only phone or while a save is in flight. */
    fun save() {
        val sheet = _sheet.value ?: return
        val open = sheet.open ?: return
        val project = sheet.project ?: return
        if (!open.writable || !open.dirty || sheet.busy || open.conflict != null) return
        val client = client() ?: return
        _sheet.value = sheet.copy(busy = true, open = open.copy(error = null))
        val asked = generation()
        val sent = open.text
        working = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.saveMemory(MobileMemorySave(project.path, open.id, sent, open.revision)) }
            }
            if (asked != generation()) return@launch
            _sheet.update { now ->
                val current = now?.open
                if (current == null || current.id != open.id || now.project != project) return@update now
                outcome.fold(
                    { answer -> now.copy(busy = false, open = applied(project, current, sent, answer)) },
                    { now.copy(busy = false, open = current.copy(error = describe(it))) },
                )
            }
        }
    }

    /** Keep mine: the reader's text is saved over the machine's version they have now seen. */
    fun keepMine() {
        _sheet.update { sheet ->
            val open = sheet?.open ?: return@update sheet
            val theirs = open.conflict ?: return@update sheet
            sheet.copy(open = open.copy(conflict = null, revision = theirs.revision, savedText = theirs.content))
        }
        save()
    }

    /** Take the machine's version; the reader's text for this file is dropped. */
    fun loadTheirs() {
        _sheet.update { sheet ->
            val open = sheet?.open ?: return@update sheet
            val theirs = open.conflict ?: return@update sheet
            drafts.remove(key(sheet.project ?: return@update sheet, open.id))
            sheet.copy(open = open.copy(conflict = null, text = theirs.content, savedText = theirs.content, revision = theirs.revision))
        }
    }

    /** Drops the reader's edits to the open file and shows the text the machine holds. */
    fun discard() {
        _sheet.update { sheet ->
            val open = sheet?.open ?: return@update sheet
            drafts.remove(key(sheet.project ?: return@update sheet, open.id))
            sheet.copy(open = open.copy(text = open.savedText, saved = false, error = null))
        }
    }

    /** Whether the reader has typed text here that the machine does not hold; the file list marks it. */
    fun hasDraft(id: String): Boolean = _sheet.value?.project?.let { drafts.containsKey(key(it, id)) } == true

    /** One step back: file → its list → the projects (when there is a choice) → closed. The typed text stays in [drafts]. */
    fun back() {
        val sheet = _sheet.value ?: return
        when {
            sheet.open != null -> _sheet.value = sheet.copy(open = null, error = null)
            sheet.project != null && (sheet.projects?.size ?: 0) > 1 -> _sheet.value = sheet.copy(project = null, entries = null, error = null)
            else -> dismiss()
        }
    }

    fun dismiss() {
        working?.cancel()
        _sheet.value = null
    }

    fun forget() {
        drafts.clear()
        dismiss()
    }

    private fun opened(project: MobileMemoryProject, file: MobileMemoryFile): OpenMemoryFile {
        val draft = drafts[key(project, file.id)]
        val fresh = OpenMemoryFile(file.id, file.label, file.writable, file.existed, file.content, file.revision, file.content)
        if (draft == null) return fresh
        // The reader's text, made from an older revision: the machine's version is shown beside it, not over it.
        val moved = draft.revision != file.revision
        return fresh.copy(
            text = draft.text,
            savedText = if (moved) draft.savedText else file.content,
            revision = if (moved) draft.revision else file.revision,
            conflict = file.takeIf { moved },
        )
    }

    private fun applied(project: MobileMemoryProject, open: OpenMemoryFile, sent: String, answer: MobileMemorySaved): OpenMemoryFile =
        when (answer.status) {
            MobileMemorySaved.SAVED -> {
                // Only the text that was sent is now on the machine; keystrokes made while it travelled stay dirty.
                drafts.remove(key(project, open.id))
                val kept = if (open.text != sent) Draft(open.text, sent, answer.revision).also { drafts[key(project, open.id)] = it } else null
                open.copy(savedText = sent, revision = answer.revision, existed = true, conflict = null, error = null, saved = kept == null)
            }
            MobileMemorySaved.CONFLICT -> open.copy(conflict = answer.current, error = if (answer.current == null) UNREADABLE_CONFLICT else null)
            else -> open.copy(error = answer.message ?: UNREADABLE_CONFLICT)
        }

    /** Runs [request] off the main thread and hands its answer to [then] unless the machine changed meanwhile. */
    private fun <T> read(request: () -> T): Pending<T> = Pending(request)

    private inner class Pending<T>(private val request: () -> T) {
        fun then(apply: (T) -> Unit) {
            val asked = generation()
            working?.cancel()
            working = scope.launch {
                val outcome = withContext(Dispatchers.IO) { runCatching(request) }
                if (asked != generation()) return@launch
                outcome.fold(apply) { failure -> _sheet.update { it?.copy(busy = false, error = describe(failure)) } }
            }
        }
    }

    private fun key(project: MobileMemoryProject, id: String) = project.path + "\n" + id

    private companion object {
        const val UNREADABLE_CONFLICT = "The machine did not accept that save. Reload the file and try again."
    }
}
