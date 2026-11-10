package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileCommitStagedPreview
import com.github.claudeagents.core.mobile.MobileCommitStagedRequest
import com.github.claudeagents.core.mobile.MobileCommitStagedResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The "Commit staged changes" sheet: the machine's index, what the reader typed, the request in flight. */
data class CommitStagedSheet(
    val key: String,
    val title: String = "",
    val preview: MobileCommitStagedPreview? = null,
    val subject: String = "",
    val body: String = "",
    val rename: Boolean = false,
    val renameTo: String = "",
    val committing: Boolean = false,
    val writing: Boolean = false,
    /** Why the last attempt did not land, in the machine's words; the sheet stays open under it. */
    val error: String? = null,
) {
    val busy: Boolean get() = committing || writing

    val canCommit: Boolean
        get() = preview?.confirmable == true && !busy && subject.isNotBlank() && (!rename || renameTo.isNotBlank())

    val canWrite: Boolean get() = preview?.confirmable == true && !busy
}

/**
 * "Commit staged changes…" on the phone (M4, P22): the desk's Repository row, committing exactly
 * what is staged in the conversation's checkout, with its model-written message and branch rename.
 *
 * The typed subject, body and rename are a draft per conversation, kept when the sheet is closed
 * or a commit fails and cleared only by one that landed; the machine also keeps the message as the
 * desk dialog's draft. A written message fills only empty fields, as the desk's does. A request
 * outlasting one read is followed under the same operation id, and an answer that never arrived
 * keeps the id, so confirming the same commit again reads it rather than committing twice.
 */
class CommitStagedFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
    private val snack: (String) -> Unit,
    private val committed: (key: String) -> Unit,
    private val pollMs: Long = POLL_MS,
) {
    private data class Draft(val subject: String, val body: String, val rename: Boolean, val renameTo: String)

    private val drafts = HashMap<String, Draft>()
    private var uncertain: MobileCommitStagedRequest? = null
    private val _sheet = MutableStateFlow<CommitStagedSheet?>(null)
    val sheet: StateFlow<CommitStagedSheet?> = _sheet.asStateFlow()

    fun open(key: String, title: String = "", notice: String? = null) {
        val client = client() ?: return
        val asked = generation()
        val draft = drafts[key]
        _sheet.value = CommitStagedSheet(
            key, title, subject = draft?.subject.orEmpty(), body = draft?.body.orEmpty(),
            rename = draft?.rename == true, renameTo = draft?.renameTo.orEmpty(), error = notice,
        )
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.commitStagedPreview(key) } }
            if (asked != generation() || _sheet.value?.key != key) return@launch
            outcome.onSuccess { preview ->
                if (preview.refused != null) {
                    // Nothing to confirm: said once, and no empty sheet left behind.
                    _sheet.value = null
                    return@onSuccess snack(preview.refused!!)
                }
                // The phone's own draft first; else the one the desk dialog left for this checkout.
                val seed = preview.message.takeIf { drafts[key] == null }
                _sheet.update {
                    it?.copy(
                        preview = preview,
                        subject = seed?.let(::subjectOf) ?: it.subject,
                        body = seed?.let(::bodyOf) ?: it.body,
                        rename = it.rename && preview.renamable,
                    )
                }
            }.onFailure { error ->
                _sheet.value = null
                snack(describe(error))
            }
        }
    }

    fun editSubject(text: String) = edit { it.copy(subject = text.take(MobileCommitStagedRequest.MAX_MESSAGE_CHARS)) }

    fun editBody(text: String) = edit { it.copy(body = text.take(MobileCommitStagedRequest.MAX_MESSAGE_CHARS)) }

    fun setRename(on: Boolean) = edit { it.copy(rename = on && it.preview?.renamable == true) }

    fun editRenameTo(text: String) = edit { it.copy(renameTo = text.take(MAX_BRANCH_CHARS)) }

    fun dismiss() {
        _sheet.value = null
    }

    /** The desk's write control: a model turn on the machine, filling only what is still empty. */
    fun write() {
        val sheet = _sheet.value?.takeIf { it.canWrite } ?: return
        val preview = sheet.preview ?: return
        val request = MobileCommitStagedRequest(sheet.key, preview.previewToken, MobileCommitStagedRequest.WRITE, UUID.randomUUID().toString())
        _sheet.update { it?.copy(writing = true, error = null) }
        run(sheet.key, request) { result ->
            when (result.state) {
                // Only into the sheet it was written for: the reader may have moved to another chat's.
                MobileCommitStagedResult.WRITTEN -> if (_sheet.value?.key == sheet.key) edit {
                    it.copy(
                        writing = false,
                        subject = it.subject.ifBlank { result.subject },
                        body = it.body.ifBlank { result.body },
                        renameTo = it.renameTo.ifBlank { result.branch.orEmpty() },
                    )
                }
                else -> settle(sheet.key, result)
            }
        }
    }

    fun confirm() {
        val sheet = _sheet.value?.takeIf { it.canCommit } ?: return
        val preview = sheet.preview ?: return
        val renameTo = sheet.renameTo.trim().takeIf { sheet.rename && it.isNotEmpty() }
        // Anything the reader changed since is a different commit, and gets a different id.
        val operation = uncertain?.takeIf {
            it.key == sheet.key && it.previewToken == preview.previewToken && it.subject == sheet.subject &&
                it.body == sheet.body && it.renameTo == renameTo
        }?.operationId ?: UUID.randomUUID().toString()
        val request = MobileCommitStagedRequest(
            sheet.key, preview.previewToken, MobileCommitStagedRequest.COMMIT, operation, sheet.subject, sheet.body, renameTo,
        )
        _sheet.update { it?.copy(committing = true, error = null) }
        run(sheet.key, request) { result ->
            if (result.state == MobileCommitStagedResult.COMMITTED) {
                drafts.remove(sheet.key)
                if (_sheet.value?.key == sheet.key) _sheet.value = null
                snack(result.message + (result.commit?.let { " ($it)" }.orEmpty()))
                committed(sheet.key)
            } else {
                settle(sheet.key, result)
            }
        }
    }

    /** A different machine: another repository's drafts and a pending id mean nothing there. */
    fun forget() {
        drafts.clear()
        uncertain = null
        _sheet.value = null
    }

    /** Sends [request], repeating it while the machine answers "working", then hands over the answer. */
    private fun run(key: String, request: MobileCommitStagedRequest, done: (MobileCommitStagedResult) -> Unit) {
        val client = client() ?: return fail(key, "Not connected to the machine.")
        val asked = generation()
        val commit = request.action == MobileCommitStagedRequest.COMMIT
        scope.launch {
            var outcome: Result<MobileCommitStagedResult>
            var waited = 0L
            var acknowledged = false
            while (true) {
                outcome = withContext(Dispatchers.IO) { runCatching { client.commitStaged(request) } }
                if (asked != generation()) return@launch
                val working = outcome.getOrNull()?.state == MobileCommitStagedResult.WORKING
                acknowledged = acknowledged || working
                if (!working || waited >= MAX_WAIT_MS) break
                delay(pollMs)
                waited += pollMs
            }
            outcome.onSuccess { result ->
                if (commit) uncertain = if (result.state == MobileCommitStagedResult.WORKING) request else null
                if (result.state == MobileCommitStagedResult.WORKING) fail(key, STILL_WORKING) else done(result)
            }.onFailure { error ->
                // Once the machine said "working", a later failure leaves that commit running; the
                // same id is what finds it again. A refusal before that means nothing started.
                if (commit) uncertain = if (error is BridgeRefusal && !acknowledged) null else request
                fail(key, describe(error))
            }
        }
    }

    private fun settle(key: String, result: MobileCommitStagedResult) {
        if (result.state == MobileCommitStagedResult.STALE && _sheet.value?.key == key) {
            // The index moved: read it again and let the reader look before committing.
            open(key, _sheet.value?.title.orEmpty(), notice = result.message)
        } else {
            fail(key, result.message.ifBlank { "Nothing was committed." })
        }
    }

    /** Under the sheet when it is still open; spoken when the reader already closed it. */
    private fun fail(key: String, message: String) {
        if (_sheet.value?.key == key) _sheet.update { it?.copy(committing = false, writing = false, error = message) } else snack(message)
    }

    private fun edit(change: (CommitStagedSheet) -> CommitStagedSheet) {
        val next = _sheet.value?.let(change) ?: return
        _sheet.value = next
        drafts[next.key] = Draft(next.subject, next.body, next.rename, next.renameTo)
    }

    companion object {
        const val POLL_MS = 1_500L

        /** A model turn or a slow hook; past this the reader decides. */
        const val MAX_WAIT_MS = 5 * 60_000L

        /** `CommitWriter.sanitizeBranch` caps the name further; this only bounds the field. */
        const val MAX_BRANCH_CHARS = 100

        const val STILL_WORKING =
            "The machine is still working on this. Try again to check on it; nothing is committed twice."

        fun subjectOf(message: String): String = message.lineSequence().firstOrNull()?.trim().orEmpty()

        fun bodyOf(message: String): String = message.substringAfter('\n', "").trimStart('\n').trimEnd()
    }
}
