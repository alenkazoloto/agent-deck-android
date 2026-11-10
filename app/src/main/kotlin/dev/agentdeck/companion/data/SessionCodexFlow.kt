package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileSessionCodex
import com.github.claudeagents.core.mobile.MobileSessionCodexControl
import com.github.claudeagents.core.mobile.MobileSessionCodexRequest
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
 * The "Codex settings" dialog: the chat it names, the three controls the machine listed for it (null while
 * they are read), and the machine's or the link's last sentence.
 */
data class SessionCodexSheet(
    val key: String,
    val title: String,
    val controls: List<MobileSessionCodexControl>? = null,
    /** The machine's sentence for a change it refused, or for a chat that is not on Codex. Cleared by the next change. */
    val refused: String? = null,
    /** Why the machine could not be read or changed, in the machine's or the link's own words. */
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * A Codex chat's communication style, web-search mode and config profile, from the phone: listed when the
 * dialog opens and changed one choice at a time through `/v1/session-codex` under the machine's per-phone
 * grant. Each choice sends exactly that one control's value ("" is Codex's own answer), so two phones
 * cannot undo each other's other controls; the machine answers with all three as it now holds them and a
 * per-control note on whether the phone's own replies use the choice. A different machine drops the
 * dialog ([forget]): its keys mean nothing on another.
 */
class SessionCodexFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
) {
    private val _sheet = MutableStateFlow<SessionCodexSheet?>(null)
    val sheet: StateFlow<SessionCodexSheet?> = _sheet.asStateFlow()
    private var working: Job? = null

    fun open(key: String, title: String) {
        working?.cancel()
        val client = client() ?: return
        val opened = SessionCodexSheet(key, title, busy = true)
        _sheet.value = opened
        run(opened) { client.sessionCodex(key) }
    }

    /** Sends [value] for [control]; nothing goes while a change is in flight, for an unlisted control or for the choice already held. */
    fun choose(control: String, value: String) {
        val open = _sheet.value ?: return
        val held = open.controls?.firstOrNull { it.id == control } ?: return
        if (open.busy || held.chosen == value || held.options.none { it.value == value }) return
        val client = client() ?: return
        val busy = open.copy(busy = true, refused = null, error = null)
        _sheet.value = busy
        run(busy) { client.changeSessionCodex(MobileSessionCodexRequest(open.key, control, value)) }
    }

    fun dismiss() {
        working?.cancel()
        _sheet.value = null
    }

    fun forget() = dismiss()

    private fun run(started: SessionCodexSheet, request: () -> MobileSessionCodex) {
        val asked = generation()
        working = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching(request) }
            if (asked != generation()) return@launch
            // Only onto the dialog that asked: closing it and opening another chat must not paint this one's controls.
            _sheet.update { open ->
                if (open == null || open.key != started.key) return@update open
                outcome.fold(
                    { answer -> open.copy(controls = answer.controls, refused = answer.refused, error = null, busy = false) },
                    { open.copy(error = describe(it), busy = false) },
                )
            }
        }
    }
}
