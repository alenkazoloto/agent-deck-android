package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileWorktreeCreateRequest
import com.github.claudeagents.core.mobile.MobileWorktreeCreateResult
import com.github.claudeagents.core.mobile.MobileWorktreeFleet
import com.github.claudeagents.core.mobile.MobileWorktreeRow
import com.github.claudeagents.core.mobile.MobileWorktreeOptions
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

/**
 * New chat's worktree pick: the machine's suggestion, what the reader typed, and the create in
 * flight — or, when [existing], the project's own worktrees to choose one from. Null in
 * [WorktreeStartFlow.choice] means the chat runs in the project's own checkout.
 */
data class WorktreeChoice(
    val projectPath: String,
    val options: MobileWorktreeOptions? = null,
    /** "Existing worktree": start in one of [fleet]'s rows instead of creating one. */
    val existing: Boolean = false,
    val fleet: MobileWorktreeFleet? = null,
    /** The [MobileWorktreeRow.path] the reader picked; only a row of [fleet] can be started in. */
    val picked: String? = null,
    val name: String = "",
    /** The desk dialog's second base: this checkout's HEAD rather than the remote default. */
    val fromHead: Boolean = false,
    val creating: Boolean = false,
    /** Why the last create or read did not succeed; the pick stays so the reader can fix the name. */
    val error: String? = null,
) {
    val loaded: Boolean get() = if (existing) fleet != null else options != null
    val refused: String? get() = if (existing) fleet?.refused else options?.refused

    /** Rows a chat can start in: a directory the machine no longer has cannot be one. */
    val rows: List<MobileWorktreeRow> get() = fleet?.rows.orEmpty().filter { !it.missing }
    val pickedRow: MobileWorktreeRow? get() = rows.firstOrNull { it.path == picked }

    /** The desk's own collision check, so a taken name is said before git is asked. */
    val nameTaken: Boolean get() = options?.takenNames.orEmpty().contains(name.trim())

    val canCreate: Boolean
        get() = loaded && refused == null && !creating &&
            if (existing) pickedRow != null else name.isNotBlank() && !nameTaken
}

/**
 * "New chat in worktree…" on the phone (M4, P22): the machine creates the worktree through the
 * desk's own writer, then the chat starts there as any New chat does. "Existing worktree" skips the
 * create: the rows are the machine's `/v1/worktree-fleet` and the send names one row's path, which
 * the machine judges as it does any project path — a worktree removed since the list was read is
 * still accepted and the run reports it, since starting a chat is never refused for that.
 *
 * The typed name is a draft per project, kept when the pick is switched off or a create fails and
 * cleared only by a create that landed. A create outlasting one request is followed by repeating
 * it under the same operation id; an answer that never arrived keeps the id, so starting again
 * with the same name reads that create instead of making a second one.
 */
class WorktreeStartFlow(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val generation: () -> Long,
    private val describe: (Throwable) -> String,
    private val pollMs: Long = POLL_MS,
) {
    private data class Draft(val name: String, val fromHead: Boolean)

    private val drafts = HashMap<String, Draft>()
    private var uncertain: MobileWorktreeCreateRequest? = null
    private var reading: Job? = null
    private val _choice = MutableStateFlow<WorktreeChoice?>(null)
    val choice: StateFlow<WorktreeChoice?> = _choice.asStateFlow()

    /** Switches the pick on for [projectPath] (or re-aims it there) and reads the machine's suggestion. */
    fun choose(projectPath: String) {
        val client = client() ?: return
        val asked = generation()
        val draft = drafts[projectPath]
        _choice.value = WorktreeChoice(projectPath, name = draft?.name.orEmpty(), fromHead = draft?.fromHead == true)
        reading?.cancel()
        reading = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.worktreeOptions(projectPath) } }
            if (asked != generation() || _choice.value?.projectPath != projectPath) return@launch
            outcome.onSuccess { options ->
                _choice.update { it?.copy(options = options, name = it.name.ifBlank { options.suggestedName }) }
            }.onFailure { error ->
                _choice.update { it?.copy(options = MobileWorktreeOptions(projectPath, refused = describe(error))) }
            }
        }
    }

    /** Switches the pick to one of [projectPath]'s existing worktrees and reads the machine's rows. */
    fun chooseExisting(projectPath: String) {
        val client = client() ?: return
        val asked = generation()
        _choice.value = WorktreeChoice(projectPath, existing = true)
        reading?.cancel()
        reading = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { client.worktreeFleet(projectPath) } }
            val current = _choice.value
            if (asked != generation() || current?.projectPath != projectPath || !current.existing) return@launch
            val fleet = outcome.getOrElse { MobileWorktreeFleet(projectPath, refused = describe(it)) }
            _choice.update { it?.copy(fleet = fleet) }
        }
    }

    fun pickExisting(path: String) = edit { if (it.existing) it.copy(picked = path) else it }

    /** The project picker moved: a worktree belongs to one repository. */
    fun projectChanged(projectPath: String) {
        val current = _choice.value ?: return
        if (current.projectPath == projectPath || current.creating) return
        if (current.existing) chooseExisting(projectPath) else choose(projectPath)
    }

    fun off() {
        if (_choice.value?.creating == true) return
        reading?.cancel()
        _choice.value = null
    }

    fun editName(text: String) = edit { it.copy(name = text.take(MobileWorktreeCreateRequest.MAX_NAME_CHARS), error = null) }

    fun setFromHead(fromHead: Boolean) = edit { it.copy(fromHead = fromHead) }

    /**
     * Creates the worktree and hands its path to [started], which sends the prompt there. Nothing is
     * started on a failure: the reader asked for isolation, and the pick stays with the reason.
     */
    fun create(started: (path: String, name: String) -> Unit) {
        val choice = _choice.value?.takeIf { it.canCreate } ?: return
        if (choice.existing) {
            val row = choice.pickedRow ?: return
            _choice.value = null
            return started(row.path, row.name)
        }
        val client = client() ?: return
        val asked = generation()
        val name = choice.name.trim()
        val operation = uncertain?.takeIf {
            it.projectPath == choice.projectPath && it.name == name && it.fromHead == choice.fromHead
        }?.operationId ?: UUID.randomUUID().toString()
        val request = MobileWorktreeCreateRequest(choice.projectPath, name, choice.fromHead, operation)
        _choice.update { it?.copy(creating = true, error = null) }
        scope.launch {
            var outcome: Result<MobileWorktreeCreateResult>
            var waited = 0L
            var acknowledged = false
            while (true) {
                outcome = withContext(Dispatchers.IO) { runCatching { client.createWorktree(request) } }
                if (asked != generation()) return@launch
                val creating = outcome.getOrNull()?.state == MobileWorktreeCreateResult.CREATING
                acknowledged = acknowledged || creating
                if (!creating || waited >= MAX_WAIT_MS) break
                delay(pollMs)
                waited += pollMs
            }
            outcome.onSuccess { result ->
                when {
                    result.created -> {
                        uncertain = null
                        drafts.remove(choice.projectPath)
                        _choice.value = null
                        started(result.path!!, result.name.ifBlank { name })
                    }
                    result.state == MobileWorktreeCreateResult.CREATING -> {
                        // Still going after minutes: the same id reads it next time rather than racing it.
                        uncertain = request
                        fail(STILL_CREATING)
                    }
                    else -> {
                        uncertain = null
                        fail(result.message.ifBlank { "The worktree could not be created." })
                    }
                }
            }.onFailure { error ->
                // A refusal of the first request means nothing started; once the machine said
                // "creating", a later refusal (a closed project) leaves that create running, and the
                // same id is what finds it again.
                uncertain = if (error is BridgeRefusal && !acknowledged) null else request
                fail(describe(error))
            }
        }
    }

    /** A different machine: another repository's names and a pending id mean nothing there. */
    fun forget() {
        reading?.cancel()
        drafts.clear()
        uncertain = null
        _choice.value = null
    }

    private fun fail(message: String) = _choice.update { it?.copy(creating = false, error = message) }

    private fun edit(change: (WorktreeChoice) -> WorktreeChoice) {
        val next = _choice.value?.takeIf { !it.creating }?.let(change) ?: return
        _choice.value = next
        if (!next.existing) drafts[next.projectPath] = Draft(next.name, next.fromHead)
    }

    companion object {
        const val POLL_MS = 1_500L

        /** A fetch and checkout of a large repository; past this the reader decides. */
        const val MAX_WAIT_MS = 10 * 60_000L

        const val STILL_CREATING =
            "The machine is still creating this worktree. Start again to check on it; nothing new is created."
    }
}
