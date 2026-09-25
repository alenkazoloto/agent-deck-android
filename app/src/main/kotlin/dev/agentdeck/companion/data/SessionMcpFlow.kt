package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileSessionMcp
import com.github.claudeagents.core.mobile.MobileSessionMcpRequest
import com.github.claudeagents.core.mobile.MobileSessionMcpServer
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
 * The MCP-servers sheet: the chat it names, the servers the machine listed for that chat's account
 * (null while they are read), whether the chat is limited to some of them, and the machine's or the
 * link's last sentence.
 */
data class SessionMcpSheet(
    val key: String,
    val title: String,
    val servers: List<MobileSessionMcpServer>? = null,
    /** False while the chat uses every server; the sheet then offers no "Use all servers". */
    val narrowed: Boolean = false,
    /** The desk's sentence for a change it refused; the list is as the machine holds it. Cleared by the next change. */
    val refused: String? = null,
    /** Why the machine could not be read or changed, in the machine's or the link's own words. */
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * Which MCP servers a Claude chat may use, from the phone (`--strict-mcp-config`): listed when the sheet
 * opens and changed one tick at a time through `/v1/session-mcp` under the machine's per-phone grant.
 *
 * A tick sends the **whole selection** the sheet shows with that one server flipped, so the machine
 * writes exactly what the reader saw; a server the machine no longer configures comes back as its
 * sentence with the list as it stands. The chat's next reply — typed here or on the desk — runs with the
 * chosen servers. A different machine drops the sheet ([forget]): its keys mean nothing on another.
 */
class SessionMcpFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
) {
    private val _sheet = MutableStateFlow<SessionMcpSheet?>(null)
    val sheet: StateFlow<SessionMcpSheet?> = _sheet.asStateFlow()
    private var working: Job? = null

    fun open(key: String, title: String) {
        working?.cancel()
        val client = client() ?: return
        val opened = SessionMcpSheet(key, title, busy = true)
        _sheet.value = opened
        run(opened) { client.sessionMcp(key) }
    }

    /** Flips [name]; nothing is sent while a change is in flight or for a name the sheet does not list. */
    fun toggle(name: String) {
        val open = _sheet.value ?: return
        val servers = open.servers ?: return
        if (open.busy || servers.none { it.name == name }) return
        val chosen = servers.filter { if (it.name == name) !it.on else it.on }.map { it.name }
        change(open) { it.changeSessionMcp(MobileSessionMcpRequest(open.key, names = chosen)) }
    }

    /** Back to every server, the argv an untouched chat launches with. */
    fun useAll() {
        val open = _sheet.value ?: return
        if (open.busy || !open.narrowed) return
        change(open) { it.changeSessionMcp(MobileSessionMcpRequest(open.key, all = true)) }
    }

    fun dismiss() {
        working?.cancel()
        _sheet.value = null
    }

    fun forget() = dismiss()

    private fun change(open: SessionMcpSheet, send: (BridgeClient) -> MobileSessionMcp) {
        val client = client() ?: return
        val busy = open.copy(busy = true, refused = null, error = null)
        _sheet.value = busy
        run(busy) { send(client) }
    }

    private fun run(started: SessionMcpSheet, request: () -> MobileSessionMcp) {
        val asked = generation()
        working = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching(request) }
            if (asked != generation()) return@launch
            // Only onto the sheet that asked: closing it and opening another chat must not paint this one's list.
            _sheet.update { open ->
                if (open == null || open.key != started.key) return@update open
                outcome.fold(
                    { answer -> open.copy(servers = answer.servers, narrowed = answer.narrowed, refused = answer.refused, error = null, busy = false) },
                    { open.copy(error = describe(it), busy = false) },
                )
            }
        }
    }
}
