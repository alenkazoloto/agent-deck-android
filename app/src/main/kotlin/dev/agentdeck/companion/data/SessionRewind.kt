package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileRewindPoint
import com.github.claudeagents.core.mobile.MobileSessionRewindPoints
import com.github.claudeagents.core.mobile.MobileSessionRewindRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The rewind picker: the machine's messages, and once one is chosen, its scopes. */
data class RewindPicker(
    val key: String,
    /** Null while the machine is still listing. */
    val points: MobileSessionRewindPoints? = null,
    /** The message whose scopes are showing; null on the first step. */
    val chosen: MobileRewindPoint? = null,
    val rewinding: Boolean = false,
    /** Why the last attempt did not rewind, in the machine's words; the picker stays open under it. */
    val error: String? = null,
)

/**
 * The desk's `/rewind` from the phone (M4, P06): choose one of your messages, then what goes back
 * with it — the desk's two steps and its three scopes, gated per message by the machine.
 *
 * The conversation half is `/v1/session-rewind`; the code half is the Changes tab's revert sheet
 * in its `before` scope, opened by [restoreCode] with a preview to confirm file by file. The
 * desk's order is kept for "Restore code and conversation": the conversation goes back first, and
 * the code sheet opens only once that has landed — rolled-back files under a conversation that
 * still remembers them is the one half-way state that cannot be recovered by re-sending.
 *
 * A rewind whose answer never arrived keeps its operation id, so a retry is answered with the
 * first attempt's outcome rather than appending a second anchor.
 */
class SessionRewindFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
    private val snack: (String) -> Unit,
    /** The conversation went back: reload it, and put [promptText] in an empty composer. */
    private val rewound: (key: String, promptText: String?) -> Unit,
    /** The chat's first message: it continues in a new chat; [open] false leaves the reader where they are. */
    private val newChat: (key: String, promptText: String?, open: Boolean) -> Unit,
    /** Opens the revert sheet's `before` scope over [point]'s request, on [key]'s conversation. */
    private val restoreCode: (key: String, point: MobileRewindPoint) -> Unit,
) {
    private var uncertain: MobileSessionRewindRequest? = null
    private val _picker = MutableStateFlow<RewindPicker?>(null)
    val picker: StateFlow<RewindPicker?> = _picker.asStateFlow()

    fun open(key: String) {
        val client = client() ?: return
        val asked = generation()
        val reading = RewindPicker(key)
        _picker.value = reading
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.rewindPoints(key) } }
            if (asked != generation() || _picker.value !== reading) return@launch
            outcome.onSuccess { points ->
                when {
                    points.refused != null -> { _picker.value = null; snack(points.refused!!) }
                    points.points.isEmpty() -> { _picker.value = null; snack(NOTHING) }
                    else -> _picker.value = reading.copy(points = points)
                }
            }.onFailure { error ->
                _picker.value = null
                snack(describe(error))
            }
        }
    }

    /** Step one: a message with one scope runs it, as the desk's picker does; more open step two. */
    fun choose(point: MobileRewindPoint) {
        val picker = _picker.value?.takeIf { !it.rewinding } ?: return
        if (point.scopes.size == 1) return run(point, point.scopes.single())
        _picker.value = picker.copy(chosen = point, error = null)
    }

    fun back() = _picker.update { it?.takeIf { p -> !p.rewinding }?.copy(chosen = null, error = null) ?: it }

    fun dismiss() {
        _picker.value = null
    }

    /** A different machine: a pending id means nothing there. */
    fun forget() {
        uncertain = null
        _picker.value = null
    }

    fun run(point: MobileRewindPoint, rewindScope: String) {
        val picker = _picker.value?.takeIf { !it.rewinding } ?: return
        if (rewindScope !in point.scopes) return
        val key = picker.key
        if (rewindScope == MobileRewindPoint.CODE) {
            _picker.value = null
            return restoreCode(key, point)
        }
        val client = client() ?: return
        val asked = generation()
        val request = uncertain?.takeIf { it.key == key && it.point == point.id }
            ?: MobileSessionRewindRequest(key, point.id, UUID.randomUUID().toString())
        val running = picker.copy(chosen = point, rewinding = true, error = null)
        _picker.value = running
        // Uncertain from the moment it is sent: a picker reopened while this one is in flight
        // must send the same id, which the machine answers with this rewind instead of a second.
        uncertain = request
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.rewind(request) } }
            if (asked != generation()) return@launch
            // Only the picker that asked is closed: one reopened since belongs to another choice.
            fun close() { if (_picker.value === running) _picker.value = null }
            outcome.onSuccess { result ->
                uncertain = null
                val code = rewindScope == MobileRewindPoint.BOTH
                when {
                    result.rewound -> {
                        close()
                        snack(result.message)
                        rewound(key, result.promptText)
                        if (code) restoreCode(key, point)
                    }
                    result.newChat -> {
                        close()
                        snack(result.message)
                        // With code to restore too, the reader stays on this chat for its sheet;
                        // the message waits in New chat either way.
                        newChat(key, result.promptText, !code)
                        if (code) restoreCode(key, point)
                    }
                    else -> fail(running, result.message)
                }
            }.onFailure { error ->
                val code = (error as? BridgeRefusal)?.code
                uncertain = request.takeIf { code == MobileRefusal.REWIND_UNCONFIRMED.code || error is BridgeDeliveryUncertain }
                fail(running, describe(error))
            }
        }
    }

    private fun fail(asked: RewindPicker, message: String) {
        if (_picker.value === asked) _picker.value = asked.copy(rewinding = false, error = message) else snack(message)
    }

    private companion object {
        const val NOTHING = "Nothing to rewind to yet."
    }
}
