package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileReviewRevertChoice
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
    /** [MobileReviewRevertRequest.SESSION], or a per-request scope over [request]. */
    val scope: String = MobileReviewRevertRequest.SESSION,
    val request: MobileReviewRevertChoice? = null,
    /** The chat's requests, kept from the last preview that listed them; empty from an older machine. */
    val requests: List<MobileReviewRevertChoice> = emptyList(),
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
 *
 * A machine with `review-request-revert` lists the chat's requests in the preview, and the sheet
 * then also offers the desk's "Undo this request's changes" and "Revert project to after this
 * request". Only such a machine ever lists them, so a per-request scope is never sent to one that
 * would ignore it and revert the whole session.
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
        val current = _sheet.value?.takeIf { it.key == key }
        read(key, current?.scope ?: MobileReviewRevertRequest.SESSION, current?.request, current?.requests.orEmpty(), notice, keep)
    }

    /**
     * Switches the open sheet to another revert of the same chat: [request]'s own changes
     * ([MobileReviewRevertRequest.REQUEST]), everything after it ([MobileReviewRevertRequest.AFTER]),
     * or with a null [request] the whole session again.
     */
    fun choose(scope: String, request: MobileReviewRevertChoice?) {
        val sheet = _sheet.value?.takeIf { !it.reverting } ?: return
        if (scope != MobileReviewRevertRequest.SESSION && request == null) return
        read(sheet.key, scope, request.takeIf { scope != MobileReviewRevertRequest.SESSION }, sheet.requests, null, null)
    }

    /**
     * Opens the sheet straight on the code half of the desk's `/rewind`: every file [request] and
     * the later requests changed, back to just before [request] ran
     * ([MobileReviewRevertRequest.BEFORE]). Only a machine with `session-rewind` lists rewind
     * points, so this is never sent to one that would ignore the scope.
     */
    fun openBefore(key: String, request: MobileReviewRevertChoice) {
        read(key, MobileReviewRevertRequest.BEFORE, request, emptyList(), null, null)
    }

    private fun read(
        key: String,
        revertScope: String,
        request: MobileReviewRevertChoice?,
        requests: List<MobileReviewRevertChoice>,
        notice: String?,
        keep: Set<String>?,
    ) {
        val client = client() ?: return
        val asked = generation()
        val reading = RevertSheet(key, revertScope, request, requests, error = notice)
        _sheet.value = reading
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.revertPreview(key, revertScope, request?.request) } }
            if (asked != generation() || _sheet.value !== reading) return@launch
            outcome.onSuccess { preview ->
                // A machine that answers another revert than the one asked for (an older plugin
                // ignores `scope`) is never shown, or confirmed, under this one's title.
                if (preview.scope != revertScope || (request != null && preview.refused == null && preview.request?.request != request.request)) {
                    _sheet.value = null
                    return@onSuccess snack(SCOPE_UNSUPPORTED)
                }
                val listed = preview.requests
                if (preview.refused != null) {
                    if (listed.isEmpty()) {
                        // Nothing the reader could confirm or choose instead: said once, no empty sheet left behind.
                        _sheet.value = null
                        return@onSuccess snack(preview.refused!!)
                    }
                    // Another request may still be revertable; the reason stays over the choices.
                    _sheet.value = reading.copy(requests = listed, preview = preview, chosen = emptySet(), error = preview.refused)
                    return@onSuccess
                }
                val offered = preview.files.filter { f -> f.revertable }.map { f -> f.path }.toSet()
                // An unticked file is the one most likely to have moved; a re-read never re-ticks it.
                _sheet.value = reading.copy(
                    request = preview.request ?: request,
                    requests = listed,
                    preview = preview,
                    chosen = keep?.intersect(offered) ?: offered,
                )
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
            it.key == sheet.key && it.previewToken == preview.previewToken && it.paths.toSet() == sheet.chosen &&
                it.scope == sheet.scope && it.request == sheet.request?.request
        }?.operationId ?: UUID.randomUUID().toString()
        val request = MobileReviewRevertRequest(
            sheet.key, preview.previewToken, sheet.chosen.sorted(), operation, sheet.scope, sheet.request?.request,
        )
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

    private companion object {
        const val SCOPE_UNSUPPORTED = "This machine's plugin can't revert one request. Update it, or revert the whole session."
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
