package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileSideAsk
import com.github.claudeagents.core.mobile.MobileSideQuestionRequest
import com.github.claudeagents.core.mobile.MobileSideQuestionState
import java.util.UUID
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

/** The `/btw` sheet: the chat it names, the asks the machine holds for it, and the question being typed. */
data class SideQuestionSheet(
    val key: String,
    val title: String,
    /** Oldest first, as the desk's panel stacks them; empty until the machine has been read. */
    val asks: List<MobileSideAsk> = emptyList(),
    /** The question being typed; kept through a refusal, a failed link and a close, cleared only when the machine took it. */
    val draft: String = "",
    /** The desk's sentence for a question the machine did not start; the draft stays. Cleared by the next edit. */
    val refused: String? = null,
    /** Why the machine could not be reached, in the link's own words. */
    val error: String? = null,
    /** The question is on its way to the machine. */
    val sending: Boolean = false,
) {
    val running: Boolean get() = asks.any { it.running }
}

/**
 * The desk's `/btw` on the phone (`side-question`): a question about a Claude chat, answered on a
 * copy the machine forks and saves nowhere. A POST starts it and answers at once; the answer is
 * read with GET while any ask is running, so a slow model turn never holds a request open.
 *
 * Closing the sheet stops only the reading — the machine finishes the ask and holds it, and the
 * next [open] shows it. A typed question is the reader's work and survives everything but the
 * machine taking it ([drafts]); a retried POST reuses its operation id so a lost answer cannot ask twice.
 * A different machine drops everything ([forget]): its keys mean nothing on another.
 */
class SideQuestionFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
    private val pollMs: Long = POLL_MS,
) {
    private val _sheet = MutableStateFlow<SideQuestionSheet?>(null)
    val sheet: StateFlow<SideQuestionSheet?> = _sheet.asStateFlow()
    private val drafts = HashMap<String, String>()
    /** The operation id of the question in flight per chat, kept while the same text may be re-sent. */
    private val operations = HashMap<String, Pair<String, String>>()
    private var polling: Job? = null

    /** Opens the sheet on [key]; a non-blank [question] — a typed `/btw <question>` — is asked at once. */
    fun open(key: String, title: String, question: String? = null) {
        polling?.cancel()
        val client = client() ?: return
        val typed = question?.takeIf { it.isNotBlank() }
        if (typed != null) drafts[key] = typed
        _sheet.value = SideQuestionSheet(key, title, draft = drafts[key].orEmpty())
        if (typed != null) ask() else read(key, client)
    }

    fun edit(text: String) {
        _sheet.update { open ->
            if (open == null) return@update null
            drafts[open.key] = text
            open.copy(draft = text, refused = null)
        }
    }

    /** Sends the typed question; nothing goes for a blank field or while it is on its way. */
    fun ask() {
        val open = _sheet.value ?: return
        val question = open.draft.trim()
        if (question.isEmpty() || open.sending) return
        val client = client() ?: return
        val asked = generation()
        val operation = operations[open.key]?.takeIf { it.second == question }?.first
            ?: UUID.randomUUID().toString().also { operations[open.key] = it to question }
        _sheet.value = open.copy(sending = true, refused = null, error = null)
        polling?.cancel()
        polling = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.askSideQuestion(MobileSideQuestionRequest(open.key, question, operation)) }
            }
            if (asked != generation()) return@launch
            var running = false
            // Only onto the sheet that asked: closing it and opening another chat must not paint this one's asks.
            _sheet.update { now ->
                if (now == null || now.key != open.key) return@update now
                outcome.fold(
                    { state ->
                        // A question the machine took is consumed; one it refused stays for the reader to fix.
                        val taken = state.refused == null
                        if (taken) {
                            operations.remove(open.key)
                            if (now.draft.trim() == question) drafts.remove(open.key)
                        }
                        running = state.running
                        now.copy(
                            asks = state.asks,
                            draft = if (taken && now.draft.trim() == question) "" else now.draft,
                            refused = state.refused,
                            error = null,
                            sending = false,
                        )
                    },
                    { now.copy(error = describe(it), sending = false) },
                )
            }
            if (running) follow(open.key, client)
        }
    }

    fun dismiss() {
        polling?.cancel()
        _sheet.value = null
    }

    fun forget() {
        drafts.clear()
        operations.clear()
        dismiss()
    }

    private fun read(key: String, client: BridgeClient) {
        val asked = generation()
        polling = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.sideQuestion(key) } }
            if (asked != generation()) return@launch
            val running = apply(key, outcome)
            if (running) follow(key, client)
        }
    }

    /** Reads again every [pollMs] while an ask runs; stops when none does, the sheet closes or the link fails [MAX_FAILURES] times running. */
    private suspend fun follow(key: String, client: BridgeClient) {
        val asked = generation()
        var failures = 0
        while (true) {
            delay(pollMs)
            if (asked != generation() || _sheet.value?.key != key) return
            val outcome = withContext(Dispatchers.IO) { runCatching { client.sideQuestion(key) } }
            if (asked != generation()) return
            failures = if (outcome.isFailure) failures + 1 else 0
            val running = apply(key, outcome)
            if (failures >= MAX_FAILURES || (outcome.isSuccess && !running)) return
        }
    }

    /** Paints [outcome] onto the sheet that asked; true while the machine still has an ask running. */
    private fun apply(key: String, outcome: Result<MobileSideQuestionState>): Boolean {
        var running = false
        _sheet.update { now ->
            if (now == null || now.key != key) return@update now
            outcome.fold(
                { state ->
                    running = state.running
                    now.copy(asks = state.asks, error = null)
                },
                { now.copy(error = describe(it)) },
            )
        }
        return running
    }

    companion object {
        /** An answer takes seconds to minutes; two seconds is quick enough to feel live and cheap enough to leave running. */
        const val POLL_MS = 2_000L

        /** A link that fails this often in a row is not coming back while the sheet is open; the reader can reopen it. */
        const val MAX_FAILURES = 5
    }
}
