package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileRepositoryHost
import com.github.claudeagents.core.mobile.MobileRepositorySetupOptions
import com.github.claudeagents.core.mobile.MobileRepositorySetupRequest
import com.github.claudeagents.core.mobile.MobileRepositorySetupResult
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

/** What a landed clone or publish left: the machine's sentence, the clone's path or the repository's page. */
data class RepositoryDone(
    val message: String,
    val path: String? = null,
    val url: String? = null,
    /** The machine's answer to "Open on the machine", once asked. */
    val opened: String? = null,
)

/**
 * The "Clone repository" or "Publish repository" sheet: the machine's options, what the reader
 * typed and picked, the write in flight, why the last one did not land, and what the landed one left.
 */
data class RepositorySheet(
    val projectPath: String,
    val publish: Boolean,
    val options: MobileRepositorySetupOptions? = null,
    /** Clone: a Git URL, a repository page or `owner/repository`. Publish: the repository to create. */
    val input: String = "",
    val host: String = "",
    val protocol: String = MobileRepositorySetupRequest.HTTPS,
    val visibility: String = MobileRepositorySetupRequest.PRIVATE,
    val parentDir: String = "",
    val working: Boolean = false,
    val error: String? = null,
    val done: RepositoryDone? = null,
) {
    val loaded: Boolean get() = options != null
    val chosenHost: MobileRepositoryHost? get() = options?.hosts?.firstOrNull { it.id == host }

    /** A URL names its own host, so the host pick only matters for `owner/repository`; the desk disables it then. */
    val inputIsUrl: Boolean get() = input.trim().let { "://" in it || scpLike(it) }

    /**
     * An ssh remote already is its protocol — the desk disables the choice rather than re-spell the
     * key-based access it was chosen for. A web page of a known host may still be cloned over either.
     */
    val protocolApplies: Boolean get() = input.trim().let { !scpLike(it) && !it.startsWith("ssh://", ignoreCase = true) }

    private fun scpLike(value: String): Boolean =
        "://" !in value && value.indexOf(':') > 0 && '/' !in value.substringBefore(':')

    val canSubmit: Boolean
        get() = loaded && !working && done == null && input.isNotBlank() && chosenHost != null &&
            (if (publish) options?.publishable == true else parentDir.isNotBlank())
}

/**
 * The desk's "Clone repository…" and "Publish repository…" on the phone (M4, P22), run on the
 * machine by the desk's own service. A write outlasting one request is followed under its operation
 * id, and one whose answer never arrived keeps that id, so trying again reads it back rather than
 * cloning twice or creating a second hosted repository.
 *
 * Typed text is a draft — the clone paste per machine, the repository name per project — kept on
 * close and on failure, cleared only by a write that landed. The host and protocol follow the last
 * pick for the session, as the desk's launcher keeps them.
 */
class RepositorySetupFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
    private val pollMs: Long = POLL_MS,
) {
    private var cloneDraft: String? = null
    private val publishDrafts = HashMap<String, String>()
    private var lastHost: String? = null
    private var lastProtocol = MobileRepositorySetupRequest.HTTPS
    private var uncertain: MobileRepositorySetupRequest? = null
    private var reading: Job? = null
    private var checking: Job? = null
    private var running: Job? = null

    private val _sheet = MutableStateFlow<RepositorySheet?>(null)
    val sheet: StateFlow<RepositorySheet?> = _sheet.asStateFlow()

    /** Projects the machine says may be published — New chat offers the row only for these. */
    private val _publishable = MutableStateFlow<Set<String>>(emptySet())
    val publishable: StateFlow<Set<String>> = _publishable.asStateFlow()

    /** Asks whether [projectPath] may be published, so New chat shows the row only where it acts. */
    fun check(projectPath: String) {
        val client = client() ?: return
        val asked = generation()
        checking?.cancel()
        checking = scope.launch {
            val options = withContext(Dispatchers.IO) { runCatching { client.repositorySetup(projectPath) } }.getOrNull()
            if (asked != generation()) return@launch
            _publishable.update { if (options?.publishable == true) it + projectPath else it - projectPath }
        }
    }

    fun open(projectPath: String, publish: Boolean) {
        // A running write owns the sheet until it settles: re-aiming it would land that write's
        // outcome on another project's sheet, and a fresh id would clone or create a second time.
        if (_sheet.value?.working == true) return
        val client = client() ?: return
        val asked = generation()
        _sheet.value = RepositorySheet(projectPath, publish)
        reading?.cancel()
        reading = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.repositorySetup(projectPath) } }
            if (asked != generation() || _sheet.value?.projectPath != projectPath) return@launch
            outcome.onSuccess { options ->
                val host = lastHost?.takeIf { id -> options.hosts.any { it.id == id } } ?: options.hosts.firstOrNull()?.id.orEmpty()
                _sheet.update {
                    it?.copy(
                        options = options,
                        input = if (publish) publishDrafts[projectPath] ?: options.suggestedRepository else cloneDraft ?: options.cloneDraft,
                        host = host,
                        protocol = lastProtocol,
                        parentDir = options.cloneParents.firstOrNull().orEmpty(),
                    )
                }
                _publishable.update { if (options.publishable) it + projectPath else it - projectPath }
            }.onFailure { error ->
                _sheet.update { it?.copy(options = MobileRepositorySetupOptions(projectPath), error = describe(error)) }
            }
        }
    }

    fun close() {
        if (_sheet.value?.working == true) return
        reading?.cancel()
        _sheet.value = null
    }

    fun editInput(text: String) = edit { it.copy(input = text.take(MobileRepositorySetupRequest.MAX_INPUT_CHARS), error = null) }

    fun setHost(id: String) = edit { it.copy(host = id) }

    fun setProtocol(protocol: String) = edit { it.copy(protocol = protocol) }

    fun setVisibility(visibility: String) = edit { it.copy(visibility = visibility) }

    fun setParent(dir: String) = edit { it.copy(parentDir = dir) }

    /** Clones or publishes what the sheet holds; nothing is retried behind the reader's back. */
    fun submit() {
        val sheet = _sheet.value?.takeIf { it.canSubmit } ?: return
        val action = if (sheet.publish) MobileRepositorySetupRequest.PUBLISH else MobileRepositorySetupRequest.CLONE
        val fresh = MobileRepositorySetupRequest(
            sheet.projectPath, action, "", sheet.input.trim(), sheet.host, sheet.protocol,
            if (sheet.publish) sheet.visibility else MobileRepositorySetupRequest.PRIVATE,
            if (sheet.publish) "" else sheet.parentDir,
        )
        lastHost = sheet.host
        lastProtocol = sheet.protocol
        run(fresh) { result ->
            if (sheet.publish) {
                publishDrafts.remove(sheet.projectPath)
                _publishable.update { it - sheet.projectPath }
            } else {
                // Null, not empty: the machine cleared the desk's copy, and a later desk paste should show.
                cloneDraft = null
            }
            _sheet.update { it?.copy(done = RepositoryDone(result.message, result.path, result.url)) }
        }
    }

    /** The desk notification's "Open": the clone becomes a project on the machine, and New chat's target next. */
    fun openCloned() {
        val sheet = _sheet.value ?: return
        val path = sheet.done?.path?.takeIf { sheet.done.opened == null && !sheet.working } ?: return
        run(MobileRepositorySetupRequest(sheet.projectPath, MobileRepositorySetupRequest.OPEN, "", path = path)) { result ->
            _sheet.update { it?.copy(done = it.done?.copy(opened = result.message)) }
        }
    }

    private fun run(fresh: MobileRepositorySetupRequest, landed: (MobileRepositorySetupResult) -> Unit) {
        val client = client() ?: return
        val asked = generation()
        val request = fresh.copy(operationId = uncertain?.takeIf { it.copy(operationId = "") == fresh }?.operationId ?: UUID.randomUUID().toString())
        _sheet.update { it?.copy(working = true, error = null) }
        running = scope.launch {
            var outcome: Result<MobileRepositorySetupResult>
            var waited = 0L
            var acknowledged = false
            while (true) {
                outcome = withContext(Dispatchers.IO) { runCatching { client.repositorySetupAction(request) } }
                if (asked != generation()) return@launch
                val working = outcome.getOrNull()?.state == MobileRepositorySetupResult.WORKING
                acknowledged = acknowledged || working
                if (!working || waited >= MAX_WAIT_MS) break
                delay(pollMs)
                waited += pollMs
            }
            val error = outcome.fold(
                onSuccess = { result ->
                    when {
                        result.done -> { uncertain = null; null }
                        result.state == MobileRepositorySetupResult.WORKING -> { uncertain = request; STILL_WORKING }
                        else -> { uncertain = null; result.message.ifBlank { "The repository could not be set up." } }
                    }
                },
                // Once the machine said "working", a later refusal leaves that write running.
                onFailure = { uncertain = if (it is BridgeRefusal && !acknowledged) null else request; describe(it) },
            )
            _sheet.update { it?.copy(working = false, error = error) }
            outcome.getOrNull()?.takeIf { it.done }?.let(landed)
        }
    }

    /** A different machine: its directories, drafts and a pending id mean nothing there. */
    fun forget() {
        checking?.cancel()
        running?.cancel()
        cloneDraft = null
        publishDrafts.clear()
        uncertain = null
        _publishable.value = emptySet()
        close()
    }

    private fun edit(change: (RepositorySheet) -> RepositorySheet) {
        val next = _sheet.value?.takeIf { !it.working && it.loaded && it.done == null }?.let(change) ?: return
        _sheet.value = next
        if (next.publish) publishDrafts[next.projectPath] = next.input else cloneDraft = next.input
    }

    companion object {
        const val POLL_MS = 1_500L

        /** A clone of a large repository, or a first push; past this the reader decides. */
        const val MAX_WAIT_MS = 10 * 60_000L

        const val STILL_WORKING =
            "The machine is still working on this. Try again to check on it; nothing is cloned or created twice."
    }
}
