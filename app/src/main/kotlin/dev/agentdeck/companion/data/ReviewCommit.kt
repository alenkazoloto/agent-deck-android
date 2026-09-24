package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileReviewCommitPreview
import com.github.claudeagents.core.mobile.MobileReviewCommitRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The Changes tab's commit sheet: the machine's preview and what the reader made of it. */
data class CommitSheet(
    val key: String,
    val preview: MobileReviewCommitPreview? = null,
    val message: String = "",
    val chosen: Set<String> = emptySet(),
    val committing: Boolean = false,
    /** Why the last attempt did not commit, in the machine's words; the sheet stays open under it. */
    val error: String? = null,
) {
    val canCommit: Boolean
        get() = preview?.confirmable == true && !committing && chosen.isNotEmpty() &&
            message.lineSequence().firstOrNull { it.isNotBlank() } != null
}

/**
 * Commit a conversation's files from the phone (M4, P21): preview, edit, confirm.
 *
 * The typed message and the ticks are a draft per conversation, kept when the sheet is closed or a
 * commit fails and cleared only by a commit that landed. An answer that never arrived keeps its
 * operation id, so the retry is answered with the first attempt's outcome instead of a second
 * commit; any definite answer retires it.
 */
class ReviewCommitFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
    private val snack: (String) -> Unit,
    private val committed: (key: String) -> Unit,
) {
    private data class Draft(val message: String, val chosen: Set<String>)

    private val drafts = HashMap<String, Draft>()
    /** The request whose answer never arrived; only the identical request may reuse its id. */
    private var uncertain: MobileReviewCommitRequest? = null
    private val _sheet = MutableStateFlow<CommitSheet?>(null)
    val sheet: StateFlow<CommitSheet?> = _sheet.asStateFlow()

    fun open(key: String, notice: String? = null) {
        val client = client() ?: return
        val asked = generation()
        _sheet.value = CommitSheet(key, message = drafts[key]?.message.orEmpty(), error = notice)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.commitPreview(key) } }
            if (asked != generation() || _sheet.value?.key != key) return@launch
            outcome.onSuccess { preview ->
                if (preview.refused != null) {
                    // Nothing the reader could confirm: said once, and no empty sheet left behind.
                    _sheet.value = null
                    return@onSuccess snack(preview.refused!!)
                }
                val offered = preview.files.filter { it.committable }.map { it.path }.toSet()
                val draft = drafts[key]
                _sheet.update {
                    it?.copy(
                        preview = preview,
                        message = draft?.message ?: preview.message,
                        chosen = draft?.chosen?.intersect(offered)?.takeIf(Set<String>::isNotEmpty)
                            ?: preview.files.filter { f -> f.included && f.committable }.map { f -> f.path }.toSet(),
                    )
                }
            }.onFailure { error ->
                _sheet.value = null
                snack(describe(error))
            }
        }
    }

    fun editMessage(text: String) = edit { it.copy(message = text.take(MobileReviewCommitRequest.MAX_MESSAGE_CHARS)) }

    fun toggle(path: String) = edit { it.copy(chosen = if (path in it.chosen) it.chosen - path else it.chosen + path) }

    fun dismiss() {
        _sheet.value = null
    }

    fun confirm() {
        val sheet = _sheet.value?.takeIf { it.canCommit } ?: return
        val preview = sheet.preview ?: return
        val client = client() ?: return
        val asked = generation()
        // Anything the reader changed since is a different commit, and gets a different id: the
        // machine then answers it on its own merits (stale, if the first one landed).
        val operation = uncertain?.takeIf {
            it.key == sheet.key && it.previewToken == preview.previewToken &&
                it.paths.toSet() == sheet.chosen && it.message == sheet.message
        }?.operationId ?: UUID.randomUUID().toString()
        val request = MobileReviewCommitRequest(sheet.key, preview.previewToken, sheet.chosen.sorted(), sheet.message, operation)
        _sheet.update { it?.copy(committing = true, error = null) }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.commit(request) } }
            if (asked != generation()) return@launch
            outcome.onSuccess { result ->
                uncertain = null
                if (result.committed) {
                    drafts.remove(sheet.key)
                    if (_sheet.value?.key == sheet.key) _sheet.value = null
                    snack(result.message + (result.commit?.let { " ($it)" }.orEmpty()))
                    committed(sheet.key)
                } else {
                    fail(sheet.key, result.message)
                }
            }.onFailure { error ->
                val code = (error as? BridgeRefusal)?.code
                when {
                    code == MobileRefusal.COMMIT_PREVIEW_STALE.code -> {
                        uncertain = null
                        // The tree moved: read it again and let the reader look before committing.
                        open(sheet.key, notice = describe(error))
                    }
                    code == MobileRefusal.COMMIT_UNCONFIRMED.code || error is BridgeDeliveryUncertain -> {
                        uncertain = request
                        fail(sheet.key, describe(error))
                    }
                    else -> {
                        uncertain = null
                        fail(sheet.key, describe(error))
                    }
                }
            }
        }
    }

    /** A different machine: another repository's drafts and a pending id mean nothing there. */
    fun forget() {
        drafts.clear()
        uncertain = null
        _sheet.value = null
    }

    /** Under the sheet when it is still open; spoken when the reader already closed it. */
    private fun fail(key: String, message: String) {
        if (_sheet.value?.key == key) _sheet.update { it?.copy(committing = false, error = message) } else snack(message)
    }

    private fun edit(change: (CommitSheet) -> CommitSheet) {
        val next = _sheet.value?.let(change) ?: return
        _sheet.value = next
        drafts[next.key] = Draft(next.message, next.chosen)
    }
}
