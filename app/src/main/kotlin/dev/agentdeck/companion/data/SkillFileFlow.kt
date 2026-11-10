package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileSkillFileSave
import com.github.claudeagents.core.mobile.MobileSkillRow
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
 * One skill's or command's file open for editing: the row it belongs to and, once the machine has answered, the editor's state
 * ([OpenMemoryFile], the memory editor's own). [error] is the machine's sentence for a file it would not hand over.
 */
data class SkillFileSheet(
    val project: String,
    val name: String,
    val kind: String,
    val source: String,
    val open: OpenMemoryFile? = null,
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * The desk Skills tab's "Open SKILL.md" on the phone (`/v1/skill-file`): read one listed skill's or command's file, edit it as text,
 * save it against the revision that was read — the memory editor's flow for one file, with the same draft rules ([FileDrafts]).
 *
 * **Typed text is the reader's work.** An edit that differs from the machine's text stays through Back, closing the dialog, a refused
 * save and a failed link; only the machine taking it or the reader choosing the machine's text drops it. Reopening a file that moved on
 * meanwhile brings the draft back *with* the machine's version as a conflict. A different machine drops everything ([forget]).
 */
class SkillFileFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
) {
    private val _sheet = MutableStateFlow<SkillFileSheet?>(null)
    val sheet: StateFlow<SkillFileSheet?> = _sheet.asStateFlow()
    private val drafts = FileDrafts()
    private var working: Job? = null

    /** Opens [row]'s file of the open [project]; the row's source label tells two same-named skills apart. */
    fun open(project: String, row: MobileSkillRow) {
        working?.cancel()
        val client = client() ?: return
        val shown = SkillFileSheet(project, row.name, row.kind, row.source, busy = true)
        _sheet.value = shown
        val asked = generation()
        working = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.skillFile(project, row.name, row.kind, row.source) } }
            if (asked != generation()) return@launch
            _sheet.update { now ->
                if (now == null || now.key() != shown.key()) return@update now
                outcome.fold(
                    { file ->
                        if (file.unavailable != null) now.copy(error = file.unavailable, busy = false)
                        else now.copy(open = drafts.opened(now.key(), file), error = null, busy = false)
                    },
                    { now.copy(busy = false, error = describe(it)) },
                )
            }
        }
    }

    fun edit(text: String) {
        _sheet.update { sheet ->
            val open = sheet?.open ?: return@update sheet
            sheet.copy(open = drafts.edit(sheet.key(), open, text))
        }
    }

    /** Saves the whole text; nothing is sent for an untouched file, a read-only phone, an unresolved conflict or while a save is in flight. */
    fun save() {
        val sheet = _sheet.value ?: return
        val open = sheet.open ?: return
        if (!open.writable || !open.dirty || sheet.busy || open.conflict != null) return
        val client = client() ?: return
        _sheet.value = sheet.copy(busy = true, open = open.copy(error = null))
        val asked = generation()
        val sent = open.text
        working = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.saveSkillFile(MobileSkillFileSave(sheet.name, sheet.kind, open.revision, sent, sheet.source, sheet.project)) }
            }
            if (asked != generation()) return@launch
            _sheet.update { now ->
                val current = now?.open
                if (current == null || now.key() != sheet.key()) return@update now
                outcome.fold(
                    { answer -> now.copy(busy = false, open = drafts.applied(now.key(), current, sent, answer)) },
                    { now.copy(busy = false, open = current.copy(error = describe(it))) },
                )
            }
        }
    }

    /** Keep mine: the reader's text is saved over the machine's version they have now seen. */
    fun keepMine() {
        _sheet.update { sheet ->
            val open = sheet?.open ?: return@update sheet
            sheet.copy(open = drafts.keepMine(open))
        }
        save()
    }

    /** Take the machine's version; the reader's text for this file is dropped. */
    fun loadTheirs() {
        _sheet.update { sheet ->
            val open = sheet?.open ?: return@update sheet
            sheet.copy(open = drafts.loadTheirs(sheet.key(), open))
        }
    }

    /** Drops the reader's edits to the open file and shows the text the machine holds. */
    fun discard() {
        _sheet.update { sheet ->
            val open = sheet?.open ?: return@update sheet
            sheet.copy(open = drafts.discard(sheet.key(), open))
        }
    }

    /** Whether the reader has typed text for [row] that the machine does not hold; the list says so. */
    fun hasDraft(project: String, row: MobileSkillRow): Boolean = drafts.has(key(project, row.kind, row.name, row.source))

    /** Closes the dialog; the typed text stays in the drafts. */
    fun dismiss() {
        working?.cancel()
        _sheet.value = null
    }

    fun forget() {
        drafts.clear()
        dismiss()
    }

    private fun SkillFileSheet.key() = key(project, kind, name, source)

    private fun key(project: String, kind: String, name: String, source: String) = "$project\n$kind\n$name\n$source"
}
