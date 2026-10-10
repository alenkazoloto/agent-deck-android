package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileAiReviewExcerpt
import com.github.claudeagents.core.mobile.MobileAiReviewFinding
import com.github.claudeagents.core.mobile.MobileAiReviewRequest
import com.github.claudeagents.core.mobile.MobileAiReviewState
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

/** The review sheet: the machine's state for the chat's project, and the form being edited. */
data class AiReviewSheet(
    val key: String,
    /** Null while the machine is first read. */
    val state: MobileAiReviewState? = null,
    val kind: String = MobileAiReviewRequest.UNCOMMITTED,
    val branch: String = "",
    val commit: String = "",
    /** A start or stop is on its way. */
    val sending: Boolean = false,
    /** Why the last start or stop did nothing, or why the machine could not be read. */
    val error: String? = null,
    /** The desk's "Review rules…" editor, one rule per line; null while it is closed. */
    val rulesText: String? = null,
    /** The list the editor opened on, so a save over a machine's newer list is refused. */
    val rulesOpenedOn: List<String>? = null,
    /** What the last rule write did, in the desk dialog's words. */
    val notice: String? = null,
    /** A finding opened at its lines, the desk dialog's Enter on it; null while the findings show. */
    val location: AiReviewLocation? = null,
)

/** [finding]'s file around its lines, read from the machine; neither [excerpt] nor [error] while it is read. */
data class AiReviewLocation(
    val index: Int,
    val finding: MobileAiReviewFinding,
    val excerpt: MobileAiReviewExcerpt? = null,
    val error: String? = null,
)

/**
 * Codex's `/review` from the phone (M4, P20): pick what to review, start it on the machine, read
 * the findings when it ends — the desk's `CodexReviewDialog` over `/v1/review/{key}/ai-review`.
 *
 * The run belongs to the machine, not to this sheet: closing the sheet stops only the reading,
 * and reopening it shows the run still going or its findings. While it runs the sheet reads the
 * state every [POLL_MS]; a review is minutes of agent work, which no single request can wait for.
 *
 * A start whose answer was lost keeps its operation id, so a retry is the same run, not a second.
 *
 * The dialog's rule buttons write the project's review rules on the machine: [neverReport] learns
 * one from a finding, [openRules]…[saveRules] edit the whole list. A refused save keeps the text.
 * [openLocation] is the dialog's opening of a finding: the phone has no editor, so it reads the
 * file's lines around the finding from the machine and shows them over the findings.
 */
class AiReviewFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
) {
    private val _sheet = MutableStateFlow<AiReviewSheet?>(null)
    val sheet: StateFlow<AiReviewSheet?> = _sheet.asStateFlow()
    private var polling: Job? = null
    private var uncertain: MobileAiReviewRequest? = null

    /**
     * The chat's draft with a finding's fix request under it, a blank line apart — the desk's
     * `AskAnywhere.appended`, so an unsent message is kept rather than spent.
     */
    fun withFix(draft: String, fixPrompt: String): String {
        val kept = draft.trimEnd()
        if (kept.isEmpty()) return fixPrompt
        if (fixPrompt.isEmpty()) return kept
        return "$kept\n\n$fixPrompt"
    }

    fun open(key: String) {
        polling?.cancel()
        _sheet.value = AiReviewSheet(key)
        read(key, first = true)
    }

    fun dismiss() {
        polling?.cancel()
        _sheet.value = null
    }

    /** A different machine: its runs and ids mean nothing there. */
    fun forget() {
        uncertain = null
        dismiss()
    }

    fun edit(kind: String? = null, branch: String? = null, commit: String? = null) = _sheet.update { sheet ->
        sheet?.copy(
            kind = kind ?: sheet.kind,
            branch = branch ?: sheet.branch,
            commit = commit ?: sheet.commit,
            error = null,
        )
    }

    fun start() {
        val sheet = _sheet.value?.takeIf { !it.sending && it.state?.running != true } ?: return
        val request = uncertain?.takeIf {
            it.key == sheet.key && it.kind == sheet.kind && it.branch == sheet.branch.trim() && it.commit == sheet.commit.trim()
        } ?: MobileAiReviewRequest(
            sheet.key, sheet.kind, sheet.branch.trim(), sheet.commit.trim(), operationId = UUID.randomUUID().toString(),
        )
        uncertain = request
        send(sheet, request)
    }

    fun stop() {
        val sheet = _sheet.value?.takeIf { !it.sending && it.state?.running == true } ?: return
        send(sheet, MobileAiReviewRequest(sheet.key, stop = true))
    }

    /** "Never report this": the next review, from either side, skips findings like [finding]. */
    fun neverReport(finding: MobileAiReviewFinding) {
        val sheet = _sheet.value?.takeIf { !it.sending && it.state?.rules != null } ?: return
        writeRules(sheet, MobileAiReviewRequest(sheet.key, neverReport = finding), LEARNED)
    }

    /** Finding [index] of the report, at its lines; a failed read says why and Back returns to the findings. */
    fun openLocation(index: Int, finding: MobileAiReviewFinding) {
        val sheet = _sheet.value ?: return
        val client = client() ?: return
        val asked = generation()
        val opened = AiReviewLocation(index, finding)
        _sheet.value = sheet.copy(location = opened)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.aiReviewExcerpt(sheet.key, index, finding) } }
            if (asked != generation()) return@launch
            // Only onto the same opening: Back, then another finding, must not show this file.
            _sheet.update { open ->
                if (open?.key != sheet.key || open.location != opened) open
                else open.copy(location = outcome.fold({ opened.copy(excerpt = it) }, { opened.copy(error = describe(it)) }))
            }
        }
    }

    fun closeLocation() = _sheet.update { it?.copy(location = null) }

    fun openRules() = _sheet.update { sheet ->
        val rules = sheet?.state?.rules ?: return@update sheet
        sheet.copy(rulesText = rules.joinToString("\n"), rulesOpenedOn = rules, error = null, notice = null)
    }

    fun editRules(text: String) = _sheet.update { it?.takeIf { s -> s.rulesText != null }?.copy(rulesText = text, error = null) }

    fun closeRules() = _sheet.update { it?.copy(rulesText = null, rulesOpenedOn = null, error = null) }

    fun saveRules() {
        val sheet = _sheet.value?.takeIf { !it.sending } ?: return
        val text = sheet.rulesText ?: return
        writeRules(sheet, MobileAiReviewRequest(sheet.key, rules = text.lines(), expectedRules = sheet.rulesOpenedOn), notice = null)
    }

    private fun writeRules(sheet: AiReviewSheet, request: MobileAiReviewRequest, notice: String?) {
        val client = client() ?: return
        val asked = generation()
        _sheet.value = sheet.copy(sending = true, error = null, notice = null)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.aiReview(request) } }
            if (asked != generation()) return@launch
            outcome.onSuccess { state ->
                land(sheet.key, state, keepForm = true)
                _sheet.update { open ->
                    open?.takeIf { it.key == sheet.key }?.let {
                        // Refused: the editor stays open on what was typed, the reason under it, and now
                        // knows the machine's list — so a second Save replaces it knowingly, not forever refused.
                        if (state.refused != null) it.copy(sending = false, error = state.refused, rulesOpenedOn = state.rules ?: it.rulesOpenedOn)
                        else it.copy(sending = false, rulesText = null, rulesOpenedOn = null, notice = notice)
                    }
                }
            }.onFailure { error ->
                _sheet.update { it?.takeIf { s -> s.key == sheet.key }?.copy(sending = false, error = describe(error)) }
            }
        }
    }

    private fun send(sheet: AiReviewSheet, request: MobileAiReviewRequest) {
        val client = client() ?: return
        val asked = generation()
        _sheet.value = sheet.copy(sending = true, error = null)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.aiReview(request) } }
            if (asked != generation()) return@launch
            outcome.onSuccess { state ->
                if (!request.stop) uncertain = null
                land(sheet.key, state, keepForm = true)
                _sheet.update { it?.takeIf { s -> s.key == sheet.key }?.copy(sending = false, error = state.refused) }
            }.onFailure { error ->
                _sheet.update { it?.takeIf { s -> s.key == sheet.key }?.copy(sending = false, error = describe(error)) }
            }
        }
    }

    private fun read(key: String, first: Boolean) {
        val client = client() ?: return
        val asked = generation()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.aiReviewState(key) } }
            if (asked != generation()) return@launch
            outcome.onSuccess { land(key, it, keepForm = !first) }
                .onFailure { error -> _sheet.update { it?.takeIf { s -> s.key == key }?.copy(error = describe(error)) } }
        }
    }

    /** The machine's state onto the open sheet; the form is taken from it only when first read. */
    private fun land(key: String, state: MobileAiReviewState, keepForm: Boolean) {
        // A late answer for a sheet since closed or reopened on another chat changes nothing here.
        val open = _sheet.value?.takeIf { it.key == key } ?: return
        _sheet.value = if (keepForm) open.copy(state = state)
        else open.copy(state = state, kind = state.kind, branch = state.branch, commit = state.commit)
        // Nothing running: whatever the unanswered start became, it has ended or never arrived, so
        // the next Review is a new one rather than a retry answered with that old run.
        if (!state.running && uncertain?.key == key) uncertain = null
        if (state.running) poll(key) else polling?.cancel()
    }

    private fun poll(key: String) {
        if (polling?.isActive == true) return
        polling = scope.launch {
            while (_sheet.value?.key == key && _sheet.value?.state?.running == true) {
                delay(POLL_MS)
                if (_sheet.value?.key != key) break
                val client = client() ?: break
                val asked = generation()
                val state = withContext(Dispatchers.IO) { runCatching { client.aiReviewState(key) } }.getOrNull() ?: continue
                if (asked != generation()) break
                _sheet.update { it?.takeIf { s -> s.key == key }?.copy(state = state) }
            }
        }
    }

    companion object {
        const val POLL_MS = 2_000L

        /** The desk dialog's status after "Never report this". */
        const val LEARNED = "Rule added. The next review skips findings like this one — edit it in Review rules."
    }
}
