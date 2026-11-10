package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileReviewFeedbackChip
import com.github.claudeagents.core.mobile.MobileReviewNote
import com.github.claudeagents.core.mobile.MobileReviewNoteRequest
import com.github.claudeagents.core.mobile.MobileReviewNotes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** One line of a diff a note can cover: its number on its side and its text without the `+`/`-`. */
data class NoteLine(val number: Int, val text: String)

/**
 * The note being written or edited. [following] are the lines after the range on the same side of
 * the same hunk, so "Next line" can widen it without the phone guessing at lines it never showed.
 */
data class NoteEditor(
    val key: String,
    val path: String,
    val side: String,
    val lines: List<NoteLine>,
    val following: List<NoteLine> = emptyList(),
    val body: String = "",
    /** Set when editing a saved note; its lines are the machine's and cannot be widened. */
    val noteId: String? = null,
    /** Moving [noteId] to these lines, the desk's "Reattach review note": its text is kept as saved. */
    val reattach: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val startLine: Int get() = lines.first().number
    val endLine: Int get() = lines.last().number
    val quote: String get() = lines.joinToString("\n") { it.text }
    /** The lines may still widen: a new note, or a note being reattached. */
    val extendable: Boolean get() = (noteId == null || reattach) && following.isNotEmpty()
    val canSave: Boolean get() = body.isNotBlank() && !saving &&
        body.toByteArray(Charsets.UTF_8).size <= MobileReviewNoteRequest.BODY_BYTES
}

/** A conversation's review notes as the Changes tab shows them. */
data class NotesState(
    val key: String,
    val notes: MobileReviewNotes? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** The notes sheet is open. */
    val listing: Boolean = false,
    val showHistory: Boolean = false,
    /** Open notes ticked for "Attach to chat". */
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    val editor: NoteEditor? = null,
    /** The note "Reattach" picked: the next long-pressed diff line becomes its new place. */
    val moving: String? = null,
) {
    val movingNote: MobileReviewNote? get() = moving?.let { id -> notes?.notes.orEmpty().firstOrNull { it.id == id } }

    val open: List<MobileReviewNote> get() = notes?.notes.orEmpty().filter { it.open }
    val shown: List<MobileReviewNote> get() = if (showHistory) notes?.notes.orEmpty() else open

    /**
     * The composer's feedback chips over [draft], as the desk's `ReviewFeedbackChips` chooses them:
     * an attached batch's files while its token is in the draft, undelivered feedback always.
     */
    fun chips(draft: String): List<MobileReviewFeedbackChip> =
        notes?.chips.orEmpty().filter { it.retryable || it.token in ReviewNotesFlow.tokens(draft) }
}

/**
 * Review notes on a conversation's diff from the phone (M4, P21): the desk's saved notes, written
 * through `/v1/review/{key}/notes`, and attached to the chat as the desk attaches them — the
 * `@review-notes:` token lands in this chat's composer, and the send expands it on the machine.
 *
 * Every write answers the machine's whole list, which replaces the phone's. A write whose answer
 * never arrived keeps its operation id, so trying the same write again is answered with the first
 * outcome instead of a second note; any definite answer retires it.
 */
class ReviewNotesFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
    private val snack: (String) -> Unit,
    /** Every file of an attached batch was removed: its token leaves [key]'s composer, as the desk's chip bar removes it. */
    private val detached: (key: String, token: String) -> Unit = { _, _ -> },
    /** An attach or retry answered a token: it goes into [key]'s composer. */
    private val attached: (key: String, token: String) -> Unit,
) {
    private val _state = MutableStateFlow<NotesState?>(null)
    val state: StateFlow<NotesState?> = _state.asStateFlow()

    /** The write whose answer never arrived; only the identical write may reuse its id. */
    private var uncertain: MobileReviewNoteRequest? = null

    /** A cancelled editor's text by its lines, so closing the sheet by mistake does not lose it. */
    private val drafts = HashMap<String, String>()

    fun load(key: String) {
        val client = client() ?: return
        val asked = generation()
        _state.update { if (it?.key == key) it.copy(loading = true, error = null) else NotesState(key, loading = true) }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.reviewNotes(key) } }
            if (asked != generation() || _state.value?.key != key) return@launch
            outcome.onSuccess { notes ->
                val before = _state.value?.editor
                _state.update { it?.copy(notes = notes, loading = false).retainSelection() }
                // The note being reattached is gone at the machine: say why its editor closed.
                if (before?.reattach == true && _state.value?.editor == null) snack(before.error ?: "That review note no longer exists.")
            }
                .onFailure { error -> _state.update { it?.copy(loading = false, error = describe(error)) } }
        }
    }

    fun openList(key: String) {
        _state.update { if (it?.key == key) it.copy(listing = true) else NotesState(key, listing = true) }
        // Read again each time: the desk resolves and delivers notes behind the phone's back.
        load(key)
    }

    fun closeList() = _state.update { it?.copy(listing = false) }

    fun toggleHistory() = _state.update { it?.copy(showHistory = !it.showHistory) }

    fun toggle(noteId: String) = _state.update { state ->
        state?.takeIf { s -> s.open.any { it.id == noteId } }
            ?.let { it.copy(selected = if (noteId in it.selected) it.selected - noteId else it.selected + noteId) }
            ?: state
    }

    /**
     * Long-press on a diff line: a new note on it, with the text the reader left there last time —
     * or, while a note is [NotesState.moving], that note offered for reattaching there.
     */
    fun startAdd(key: String, path: String, side: String, line: NoteLine, following: List<NoteLine>) {
        _state.value?.takeIf { it.key == key }?.movingNote?.let { note ->
            val editor = NoteEditor(key, path, side, listOf(line), following, body = note.body, noteId = note.id, reattach = true)
            _state.update { it?.copy(editor = editor) }
            return
        }
        val editor = NoteEditor(key, path, side, listOf(line), following)
        _state.update { (if (it?.key == key) it else NotesState(key)).copy(editor = editor.copy(body = drafts[draftKey(editor)].orEmpty())) }
    }

    fun startEdit(note: MobileReviewNote) {
        val state = _state.value ?: return
        val lines = note.quote.split('\n').mapIndexed { i, text -> NoteLine(note.startLine + i, text) }
        val editor = NoteEditor(state.key, note.path, note.side, lines, body = note.body, noteId = note.id)
        _state.update { it?.copy(editor = editor.copy(body = drafts[draftKey(editor)] ?: note.body)) }
    }

    /** The notes sheet's "Reattach": the sheet closes so the reader can long-press the note's new lines. */
    fun startMove(noteId: String) = _state.update { state ->
        state?.takeIf { s -> s.notes?.notes.orEmpty().any { it.id == noteId } }?.copy(moving = noteId, listing = false) ?: state
    }

    fun cancelMove() = _state.update { it?.copy(moving = null, editor = it.editor?.takeUnless { e -> e.reattach }) }

    fun extend() = _state.update { state ->
        val editor = state?.editor?.takeIf { it.extendable } ?: return@update state
        state.copy(editor = editor.copy(lines = editor.lines + editor.following.first(), following = editor.following.drop(1)))
    }

    fun editBody(text: String) = _state.update { state -> state?.editor?.let { state.copy(editor = it.copy(body = text, error = null)) } ?: state }

    fun cancelEditor() {
        val editor = _state.value?.editor ?: return
        if (!editor.saving) {
            // Reattaching keeps the saved text; only the lines were being chosen.
            if (!editor.reattach) drafts[draftKey(editor)] = editor.body
            _state.update { it?.copy(editor = null) }
        }
    }

    fun save() {
        val editor = _state.value?.editor?.takeIf { it.canSave } ?: return
        val request = if (editor.reattach) {
            MobileReviewNoteRequest(
                editor.key, MobileReviewNoteRequest.REATTACH, "", noteId = editor.noteId, path = editor.path, side = editor.side,
                startLine = editor.startLine, endLine = editor.endLine, quote = editor.quote,
            )
        } else if (editor.noteId == null) {
            MobileReviewNoteRequest(
                editor.key, MobileReviewNoteRequest.ADD, "", path = editor.path, side = editor.side,
                startLine = editor.startLine, endLine = editor.endLine, quote = editor.quote, body = editor.body,
            )
        } else {
            MobileReviewNoteRequest(editor.key, MobileReviewNoteRequest.EDIT, "", noteId = editor.noteId, body = editor.body)
        }
        _state.update { it?.copy(editor = editor.copy(saving = true, error = null)) }
        write(request, onDone = {
            if (!editor.reattach) drafts.remove(draftKey(editor))
            _state.update { it?.copy(editor = null, moving = it.moving.takeUnless { editor.reattach }) }
            snack(
                when {
                    editor.reattach -> "Review note reattached."
                    editor.noteId == null -> "Review note saved."
                    else -> "Review note updated."
                },
            )
        }, onError = { message ->
            _state.update { state -> state?.editor?.let { state.copy(editor = it.copy(saving = false, error = message)) } ?: state }
            // A refused reattach may mean the note was deleted at the desk; re-read so the strip does not outlive it.
            if (editor.reattach) load(editor.key)
        })
    }

    fun resolve(noteId: String) = simple(MobileReviewNoteRequest.RESOLVE, noteId, "Review note resolved.")

    fun delete(noteId: String) = simple(MobileReviewNoteRequest.DELETE, noteId, "Review note deleted.")

    fun attach() {
        val state = _state.value?.takeIf { it.selected.isNotEmpty() && !it.busy } ?: return
        val request = MobileReviewNoteRequest(state.key, MobileReviewNoteRequest.ATTACH, "", noteIds = state.selected.sorted())
        busy(request) { answer -> answer.token?.let { token -> deliver(state.key, token, state.selected.size) } }
    }

    fun retry(batchId: String) {
        val state = _state.value?.takeIf { !it.busy } ?: return
        val request = MobileReviewNoteRequest(state.key, MobileReviewNoteRequest.RETRY, "", batchId = batchId)
        busy(request) { answer -> answer.token?.let { token -> deliver(state.key, token, null) } }
    }

    /**
     * The reader edited [key]'s draft: an attached batch whose token they erased is detached on the
     * machine, as the desk's draft listener detaches it, so the desk does not put it back into its
     * own composer. Only the reader's edits come here — a send clears the draft through another
     * path, and its batch is staged, which the machine leaves alone anyway.
     */
    fun draftEdited(key: String, before: String, after: String) {
        val state = _state.value?.takeIf { it.key == key } ?: return
        val erased = tokens(before) - tokens(after)
        if (erased.isEmpty()) return
        state.notes?.chips.orEmpty().filter { it.token in erased && !it.retryable }.map { it.batchId }.distinct().forEach { batchId ->
            write(MobileReviewNoteRequest(key, MobileReviewNoteRequest.DETACH, "", batchId = batchId), onDone = {}, onError = snack)
        }
    }

    /** A chip's ✕: that file's notes leave the feedback; the last file's removal takes the token out of the draft. */
    fun detachFile(key: String, chip: MobileReviewFeedbackChip) {
        write(MobileReviewNoteRequest(key, MobileReviewNoteRequest.DETACH_FILE, "", batchId = chip.batchId, path = chip.path), onDone = { answer ->
            if (answer.chips.none { it.batchId == chip.batchId }) detached(key, chip.token)
        }, onError = snack)
    }

    /** A different machine: nothing here means anything there. */
    fun forget() {
        uncertain = null
        drafts.clear()
        _state.value = null
    }

    private fun deliver(key: String, token: String, count: Int?) {
        _state.update { it?.copy(listing = false, selected = emptySet()) }
        attached(key, token)
        snack(
            when (count) {
                null -> "Review feedback is back in the message. Send it to try again."
                1 -> "1 review note attached to your message. Send it to deliver."
                else -> "$count review notes attached to your message. Send it to deliver."
            },
        )
    }

    private fun simple(op: String, noteId: String, done: String) {
        val state = _state.value?.takeIf { !it.busy } ?: return
        _state.update { it?.copy(editor = null) }
        busy(MobileReviewNoteRequest(state.key, op, "", noteId = noteId)) { snack(done) }
    }

    private fun busy(request: MobileReviewNoteRequest, done: (MobileReviewNotes) -> Unit) {
        _state.update { it?.copy(busy = true, error = null) }
        write(request, onDone = { answer ->
            _state.update { it?.copy(busy = false) }
            done(answer)
        }, onError = { message ->
            _state.update { it?.copy(busy = false) }
            snack(message)
        })
    }

    private fun write(request: MobileReviewNoteRequest, onDone: (MobileReviewNotes) -> Unit, onError: (String) -> Unit) {
        val client = client() ?: return onError("Not connected to a machine.")
        val asked = generation()
        // A detach is harmless to repeat, and fired by typing and ✕ between a lost Save or Attach
        // and its retry: it must neither take nor clear the slot that retry's id lives in.
        val replayed = request.op != MobileReviewNoteRequest.DETACH && request.op != MobileReviewNoteRequest.DETACH_FILE
        val operation = uncertain?.takeIf { replayed && it.copy(operationId = "") == request }?.operationId ?: UUID.randomUUID().toString()
        val sent = request.copy(operationId = operation)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.reviewNote(sent) } }
            if (asked != generation()) return@launch
            outcome.onSuccess { answer ->
                if (replayed) uncertain = null
                if (_state.value?.key == request.key) _state.update { it?.copy(notes = answer).retainSelection() }
                onDone(answer)
            }.onFailure { error ->
                if (replayed) uncertain = if (error is BridgeDeliveryUncertain) sent else null
                onError(describe(error))
            }
        }
    }

    private fun NotesState?.retainSelection(): NotesState? = this?.let { state ->
        val gone = state.moving != null && state.movingNote == null
        state.copy(
            selected = state.selected.intersect(state.open.map { it.id }.toSet()),
            moving = state.moving.takeUnless { gone },
            editor = state.editor.takeUnless { gone && it?.reattach == true },
        )
    }

    companion object {
        private val TOKEN = Regex("@review-notes:\\S+")

        /** The desk's `ReviewFeedbackTokens` pattern: the tokens an attach or retry put in a draft. */
        fun tokens(draft: String): Set<String> = TOKEN.findAll(draft).map { it.value }.toSet()

        /**
         * [draft] without [token] and the one space before it, trailing blanks trimmed as the desk
         * trims them; null when the token is not there, so the reader's text is never rewritten.
         */
        fun withoutToken(draft: String, token: String): String? {
            if (token !in draft) return null
            return draft.replace(" $token", "").replace(token, "").trimEnd()
        }
    }

    private fun draftKey(editor: NoteEditor) =
        editor.noteId?.let { "edit:$it" } ?: "add:${editor.path}:${editor.side}:${editor.startLine}"
}
