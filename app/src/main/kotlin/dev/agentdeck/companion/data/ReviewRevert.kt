package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileReviewRevertPreview
import com.github.claudeagents.core.mobile.MobileReviewRevertRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The Changes tab's revert sheet: the machine's preview and the files the reader kept ticked. */
data class RevertSheet(
    val key: String,
    val preview: MobileReviewRevertPreview? = null,
    val chosen: Set<String> = emptySet(),
    val reverting: Boolean = false,
    /** Why the last attempt did not revert, in the machine's words; the sheet stays open under it. */
    val error: String? = null,
) {
    val canRevert: Boolean get() = preview?.confirmable == true && !reverting && chosen.isNotEmpty()
}

/**
 * Revert a conversation's files to their session start from the phone (M4, P20): preview, pick,
 * confirm. The write is the desk's own `SessionRevert`, behind the same guards.
 *
 * Every file the machine can revert starts ticked, as the desk's dialog covers all of them; a
 * stale preview is read again with the reader's own ticks, never with the unticked ones restored. An
 * answer that never arrived keeps its operation id, so the retry is answered with the first
 * attempt's outcome instead of a second write; any definite answer retires it.
 */
class ReviewRevertFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
    private val snack: (String) -> Unit,
    private val reverted: (key: String) -> Unit,
) {
    /** The request whose answer never arrived; only the identical request may reuse its id. */
    private var uncertain: MobileReviewRevertRequest? = null
    private val _sheet = MutableStateFlow<RevertSheet?>(null)
    val sheet: StateFlow<RevertSheet?> = _sheet.asStateFlow()

    /** [keep] is the reader's selection to carry over a re-read; null ticks every revertable file. */
    fun open(key: String, notice: String? = null, keep: Set<String>? = null) {
        val client = client() ?: return
        val asked = generation()
        _sheet.value = RevertSheet(key, error = notice)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.revertPreview(key) } }
            if (asked != generation() || _sheet.value?.key != key) return@launch
            outcome.onSuccess { preview ->
                if (preview.refused != null) {
                    // Nothing the reader could confirm: said once, and no empty sheet left behind.
                    _sheet.value = null
                    return@onSuccess snack(preview.refused!!)
                }
                val offered = preview.files.filter { f -> f.revertable }.map { f -> f.path }.toSet()
                // An unticked file is the one most likely to have moved; a re-read never re-ticks it.
                _sheet.update { it?.copy(preview = preview, chosen = keep?.intersect(offered) ?: offered) }
            }.onFailure { error ->
                _sheet.value = null
                snack(describe(error))
            }
        }
    }

    fun toggle(path: String) {
        _sheet.update { sheet ->
            sheet?.takeIf { s -> s.preview?.files?.any { it.path == path && it.revertable } == true }
                ?.let { it.copy(chosen = if (path in it.chosen) it.chosen - path else it.chosen + path) }
                ?: sheet
        }
    }

    fun dismiss() {
        _sheet.value = null
    }

    fun confirm() {
        val sheet = _sheet.value?.takeIf { it.canRevert } ?: return
        val preview = sheet.preview ?: return
        val client = client() ?: return
        val asked = generation()
        val operation = uncertain?.takeIf {
            it.key == sheet.key && it.previewToken == preview.previewToken && it.paths.toSet() == sheet.chosen
        }?.operationId ?: UUID.randomUUID().toString()
        val request = MobileReviewRevertRequest(sheet.key, preview.previewToken, sheet.chosen.sorted(), operation)
        _sheet.update { it?.copy(reverting = true, error = null) }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.revert(request) } }
            if (asked != generation()) return@launch
            outcome.onSuccess { result ->
                uncertain = null
                if (result.reverted) {
                    if (_sheet.value?.key == sheet.key) _sheet.value = null
                    snack(result.message)
                    reverted(sheet.key)
                } else {
                    fail(sheet.key, result.message)
                }
            }.onFailure { error ->
                val code = (error as? BridgeRefusal)?.code
                when {
                    code == MobileRefusal.REVERT_PREVIEW_STALE.code -> {
                        uncertain = null
                        // The files moved: read them again and let the reader look before reverting.
                        open(sheet.key, notice = describe(error), keep = sheet.chosen)
                    }
                    code == MobileRefusal.REVERT_UNCONFIRMED.code || error is BridgeDeliveryUncertain -> {
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

    /** A different machine: a pending id means nothing there. */
    fun forget() {
        uncertain = null
        _sheet.value = null
    }

    /** Under the sheet when it is still open; spoken when the reader already closed it. */
    private fun fail(key: String, message: String) {
        if (_sheet.value?.key == key) _sheet.update { it?.copy(reverting = false, error = message) } else snack(message)
    }
}
