package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileContextBreakdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The context sheet: the chat it names, and what the machine said — neither while it is read. */
data class ContextSheet(
    val key: String,
    val title: String,
    val breakdown: MobileContextBreakdown? = null,
    /** Why the machine could not be read, in the machine's or the link's own words. */
    val error: String? = null,
)

/**
 * The desk's context gauge grid on the phone (M2 P15, `/context`): how one chat's window is
 * divided and which instruction files loaded, read from `/v1/context` when the sheet opens.
 *
 * Nothing is kept: the grid describes the chat as it is now, so reopening asks again, and a
 * different machine drops the sheet ([forget]) because its keys mean nothing there.
 */
class ContextFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
) {
    private val _sheet = MutableStateFlow<ContextSheet?>(null)
    val sheet: StateFlow<ContextSheet?> = _sheet.asStateFlow()
    private var reading: Job? = null

    fun open(key: String, title: String) {
        reading?.cancel()
        val client = client() ?: return
        val asked = generation()
        val opened = ContextSheet(key, title)
        _sheet.value = opened
        reading = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.contextBreakdown(key) } }
            if (asked != generation()) return@launch
            // Only onto the sheet that asked: closing it and opening another chat must not paint this one's grid.
            _sheet.update { open ->
                if (open !== opened) open
                else outcome.fold({ open.copy(breakdown = it) }, { open.copy(error = describe(it)) })
            }
        }
    }

    fun dismiss() {
        reading?.cancel()
        _sheet.value = null
    }

    fun forget() = dismiss()
}
