package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileSessionSpend
import com.github.claudeagents.core.mobile.MobileSessionSpendRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The desk's "Chat Spend Limits" form as typed: text, because blank is "off" exactly as in the desk's fields. */
data class SpendForm(
    /** No limits of their own: the other fields are the global defaults the chat runs on, read-only. */
    val useDefaults: Boolean = true,
    val soft: String = "",
    val hard: String = "",
    val session: String = "",
    val weekly: String = "",
    val prompt: String = "",
    val startNewChat: Boolean = false,
) {
    internal fun toRequest(key: String) = MobileSessionSpendRequest(key, useDefaults, soft, hard, session, weekly, prompt, startNewChat)

    companion object {
        internal fun of(spend: MobileSessionSpend) =
            SpendForm(spend.useDefaults, spend.soft, spend.hard, spend.session, spend.weekly, spend.prompt, spend.startNewChat)
    }
}

/**
 * The spend-limits sheet: the chat it names, what the machine holds ([saved], null while it is read),
 * the form as the reader has it ([form], which differs from [saved] until a save is taken), and the
 * machine's or the link's last sentence.
 */
data class SessionSpendSheet(
    val key: String,
    val title: String,
    val saved: SpendForm? = null,
    val form: SpendForm? = null,
    /** The desk's sentence for a form it refused; nothing was saved. Cleared by the next edit. */
    val refused: String? = null,
    /** Why the machine could not be read or changed, in the machine's or the link's own words. */
    val error: String? = null,
    val busy: Boolean = false,
) {
    /** Save is offered only for a form the machine does not already hold. */
    val dirty: Boolean get() = form != null && form != saved
}

/**
 * One chat's own spend limits on the phone (the desk's "Chat Spend Limits"): read when the sheet opens
 * and saved whole through `/v1/session-spend` under the machine's per-phone grant.
 *
 * What the reader typed is their work, so it survives a refusal, a failed link and closing the sheet
 * ([drafts]); only the machine taking it drops it. A different machine drops everything ([forget]).
 */
class SessionSpendFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
) {
    private val _sheet = MutableStateFlow<SessionSpendSheet?>(null)
    val sheet: StateFlow<SessionSpendSheet?> = _sheet.asStateFlow()
    /** Per chat: the form the reader typed and the machine's form it was typed against. */
    private val drafts = HashMap<String, Pair<SpendForm, SpendForm>>()
    private var working: Job? = null

    fun open(key: String, title: String) {
        working?.cancel()
        val client = client() ?: return
        val opened = SessionSpendSheet(key, title, busy = true)
        _sheet.value = opened
        run(opened) { client.sessionSpend(key) }
    }

    fun edit(form: SpendForm) {
        _sheet.update { open ->
            if (open == null || open.form == null) return@update open
            // Only a form that differs from what the machine holds is a draft.
            if (form == open.saved) drafts.remove(open.key) else open.saved?.let { drafts[open.key] = it to form }
            open.copy(form = form, refused = null)
        }
    }

    fun save() {
        val open = _sheet.value ?: return
        val form = open.form ?: return
        if (open.busy || !open.dirty) return
        val client = client() ?: return
        val busy = open.copy(busy = true, refused = null, error = null)
        _sheet.value = busy
        run(busy) { client.changeSessionSpend(form.toRequest(open.key)) }
    }

    fun dismiss() {
        working?.cancel()
        _sheet.value = null
    }

    fun forget() {
        drafts.clear()
        dismiss()
    }

    private fun run(started: SessionSpendSheet, request: () -> MobileSessionSpend) {
        val asked = generation()
        working = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching(request) }
            if (asked != generation()) return@launch
            // Only onto the sheet that asked: closing it and opening another chat must not paint this one's limits.
            _sheet.update { open ->
                if (open == null || open.key != started.key) return@update open
                outcome.fold(
                    { answer ->
                        val held = SpendForm.of(answer)
                        val next = when {
                            // A refusal keeps exactly what was typed, beside the desk's sentence.
                            answer.refused != null -> open.form ?: held
                            // The first read: the reader's unsent form from an earlier visit comes back — but only
                            // over the limits it was typed against, or Save would undo a change made at the desk since.
                            open.saved == null -> drafts[open.key]?.takeIf { (base, typed) -> base == held && typed != held }?.second ?: held
                            // The machine took it: the form is now what it holds, normalised as the desk writes it.
                            else -> held
                        }
                        if (next == held) drafts.remove(open.key) else drafts[open.key] = held to next
                        open.copy(saved = held, form = next, refused = answer.refused, error = null, busy = false)
                    },
                    { open.copy(error = describe(it), busy = false) },
                )
            }
        }
    }
}
