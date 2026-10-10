package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.*
import com.google.gson.JsonObject
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.fixture.DeckFixtures
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Loopback HTTPS exercises the shipped pinning, transport, serialization and ViewModel route. */
internal class StoryBridge(
    private val name: String = "Story workstation",
    private val deviceId: String = "story-device",
) : AutoCloseable {
    private val fixture = requireNotNull(DeckFixtures.byName("convo-idle"))
    private val fleetFixture = requireNotNull(DeckFixtures.byName("settings"))
    private val runOptions = requireNotNull(DeckFixtures.byName("new-chat-run-options")?.hello)
    private val certificate = HeldCertificate.Builder().commonName("mobile-story-test").build()
    private val server = MockWebServer()
    val commands = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val transcriptReads = CopyOnWriteArrayList<String>()
    val pages = ConcurrentHashMap<String, MobileTranscriptPage>()
    val earlierPages = ConcurrentHashMap<String, MobileTranscriptPage>()
    val earlierReads = CopyOnWriteArrayList<String>()
    @Volatile var paging = false
    @Volatile var expireHistory = false
    private val transcriptGates = ConcurrentHashMap<String, TranscriptGate>()

    /** How the machine answers a confirmed revert (J06). */
    enum class RevertAnswer {
        /** Writes the named files and says so. */
        REVERTS,
        /** A file moved since the preview: nothing is written, and the machine's token moves on. */
        STALE,
        /** The write lands and the socket drops before the answer: the phone cannot know. */
        LOST_ACK,
    }
    @Volatile var refuseSend = false
    /** The send is read, recorded and then the socket drops: the acknowledgement is lost (J03/J07). */
    @Volatile var loseSendAck = false
    @Volatile var unavailable = false
    /** Every route answers the machine's revoked-device refusal, as after "Remove device" on the desk (J13). */
    @Volatile var revoked = false
    /** Advertises the M3 session routes: actions, folders and message search (J08). */
    @Volatile var organizes = false
    @Volatile var folders = emptyList<MobileFolder>()
    /** Message text per chat key, read by `/v1/session-search`. */
    val bodies = ConcurrentHashMap<String, String>()
    /** Keys one search page reads before it reports `timedOut`, the desk's 6 s bound (J08 partial search). */
    @Volatile var searchPage = Int.MAX_VALUE
    /** Advertises the M4 review routes: checklist, diffs, ticks and revert (J05/J06). */
    @Volatile var reviews = false
    /** The machine's checklist per chat key; a tick, a desk edit or a revert changes it as its own stores would. */
    val reviewFiles = ConcurrentHashMap<String, List<MobileReviewFile>>()
    /** Hunks per file path. */
    val reviewHunks = ConcurrentHashMap<String, List<MobileReviewHunk>>()
    /** What a confirmed revert answers next; see [RevertAnswer]. */
    @Volatile var revertAnswer = RevertAnswer.REVERTS
    /** The preview the machine will still accept; a desk edit or a stale answer moves it on. */
    @Volatile var revertToken = "preview-1"
    /** Every path a revert actually wrote, in order — the desk's files the revert touched. */
    val revertedPaths = CopyOnWriteArrayList<String>()
    private val revertOutcomes = ConcurrentHashMap<String, MobileReviewRevertResult>()
    @Volatile private var revertGate: TranscriptGate? = null
    @Volatile var page = requireNotNull(fixture.transcript).copy(
        key = "story-chat", title = "Review navigation", running = false, hasMore = false,
        turns = listOf(MobileTurn("opening", "assistant", "Navigation review is ready.", System.currentTimeMillis())),
        // What the desk would send next; the composer opens on it (PLAN-MOBILE-PARITY M2).
        selection = MobileRunSelection(model = "opus", effort = null, permissionMode = "plan"),
    )
    private val row = requireNotNull(fleetFixture.snapshot).rows.first().copy(
        key = page.key, title = page.title, projectPath = "/work/project", projectName = "project",
        attention = SessionAttentionState.DONE_UNREVIEWED, liveLine = null,
    )
    @Volatile var rows = listOf(row, row.copy(key = "second-chat", title = "Build account settings"))
    @Volatile var projects = listOf("/work/project")
    @Volatile var scheduled = emptyList<MobileScheduledRow>()
    /** Runs that already finished, as the desk's list would carry them (J09 "last run"). */
    @Volatile var outcomes = emptyList<MobileScheduledOutcome>()
    val scheduleDetails = ConcurrentHashMap<String, MobileScheduleEditDetail>()
    @Volatile var refuseScheduleEdit = false
    /** `MobileHello.timeZoneId`; null (the fixture's own) until a J09 test names the machine's zone. */
    @Volatile var timeZoneId: String? = null
    /** The ACP agents a new chat may start on (J10); empty until a test names one. */
    @Volatile var acpAgents: List<MobileAcpAgent> = emptyList()
    /** The whole Usage screen (J10 "cost unknown" / J12 account round trip); null until a test sets it. */
    @Volatile var usageReport: MobileUsageReport? = null
    /** Resources › MCP servers (J12 resource row); null until a test sets it. */
    @Volatile var mcpServers: MobileMcpServers? = null
    /** Settings › "Restricted mode for Claude chats" (J12 setting row); null until a test sets it. */
    @Volatile var restrictedMode: Boolean? = null
    /** Capabilities a test needs beyond the fixed set below, e.g. `account-edits` (J12). */
    @Volatile var extraCapabilities: Set<String> = emptySet()
    /** The one open project Memory lists (J11); null until a test sets it. */
    @Volatile var memoryProject: MobileMemoryProject? = null
    @Volatile var memoryEntries: MobileMemoryEntries? = null
    /** Memory files by id; a test mutates this directly to fake a desk edit mid-flight (J11). */
    val memoryFiles = ConcurrentHashMap<String, MobileMemoryFile>()
    /** Whether `/v1/attach` is advertised at all (J11); the bytes it accepts are recorded in [attachments]. */
    @Volatile var attachmentsEnabled = false
    /** Bytes accepted through `/v1/attach`, by the id handed back (J11). */
    val attachments = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var nextAttachmentId = 0
    val snapshot get() = requireNotNull(fleetFixture.snapshot).copy(rows = rows, badgeCount = 0,
        openProjects = projects, generatedAtMs = System.currentTimeMillis(), folders = folders)
    val machine: PairedMachine

    init {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val transcriptKey = request.requestUrl!!.takeIf { it.encodedPath.startsWith("/v1/session/") }?.pathSegments?.last()
                if (transcriptKey != null) transcriptReads += transcriptKey
                if (revoked) return json(JsonObject().apply {
                    addProperty("error", MobileRefusal.DEVICE_REVOKED.code)
                    addProperty("message", MobileRefusal.DEVICE_REVOKED.message)
                }, 401)
                if (unavailable) return json(JsonObject().apply { addProperty("message", "Machine unavailable") }, 503)
                val path = request.requestUrl!!.encodedPath
                if (request.method == "POST") {
                    // Binary, never JSON: read before the generic parse below would consume it as text (J11).
                    if (path == MobileAttachment.ROUTE) return attach(request)
                    val body = MobileProtocol.parseObject(request.body.readUtf8()) ?: JsonObject()
                    commands += path to body
                    if (path.startsWith("/v1/review/")) return review(path, body)
                    if (path.startsWith("/v1/scheduled/")) {
                        if (refuseScheduleEdit) return json(JsonObject().apply {
                            addProperty("message", "The schedule changed. Try saving again.")
                        }, 409)
                        val id = path.substringAfterLast('/')
                        val edit = MobileScheduleEditRequest.fromJson(body)
                        scheduleDetails[id]?.let { old ->
                            scheduleDetails[id] = old.copy(prompt = edit.prompt, model = edit.model,
                                sessionId = old.sessionId.takeUnless { edit.newChat }, accountId = edit.accountId ?: old.accountId)
                            scheduled = scheduled.map { if (it.id == id) it.copy(prompt = edit.prompt) else it }
                        }
                        return json(JsonObject().apply { addProperty("updated", true) })
                    }
                    return when (path) {
                        "/v1/pair" -> json(JsonObject().apply {
                            addProperty("token", "story-token"); addProperty("deviceId", "story-device")
                            addProperty("machine", "Story workstation")
                        })
                        "/v1/send" -> {
                            if (refuseSend) return json(JsonObject().apply {
                                addProperty("message", "This project is not open in the IDE.")
                                addProperty("code", "project_not_open")
                            }, 409)
                            if (loseSendAck) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            val prompt = body["prompt"].asString
                            if (body.has("dueAtMs")) {
                                val repeatEvery = body["repeatEveryMs"]?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                                val repeatAt = body["repeatAtTime"]?.takeIf { !it.isJsonNull }?.asString
                                scheduled = scheduled + MobileScheduledRow(
                                    id = "queued-story", prompt = prompt, projectPath = "/work/project", sessionId = null,
                                    dueAtMs = body["dueAtMs"].asLong, state = MobileScheduledRow.QUEUED,
                                    repeating = repeatEvery > 0 || repeatAt != null,
                                    cadence = repeatAt?.let { "Daily at $it" } ?: repeatEvery.takeIf { it > 0 }?.let { "Every ${it / 60_000}m" },
                                )
                            } else if (body["newChat"]?.asBoolean == true) {
                                rows = rows + row.copy(key = "created-chat", title = prompt)
                                // Started on an ACP agent (J10): no cost line exists for it, ever — the
                                // conversation must read "cost unknown", never a fabricated "$0.00".
                                if (body.has("acpAgentId")) pages["created-chat"] = page.copy(
                                    key = "created-chat", title = prompt, costUsd = 0.0, costKnown = false,
                                    turns = listOf(MobileTurn("acp-opening", "assistant", "Ready.", System.currentTimeMillis())),
                                )
                            } else {
                                page = page.copy(turns = page.turns + MobileTurn("reply-${commands.size}", "user", prompt,
                                    System.currentTimeMillis()))
                            }
                            json(MobileSendAccepted("story-task", MobileSendAccepted.QUEUED).toJson())
                        }
                        MobileSessionActionRequest.ROUTE -> {
                            val action = MobileSessionActionRequest.fromJson(body)
                            val old = rows.firstOrNull { it.key == action.key } ?: return json(JsonObject(), 404)
                            val new = when (action.action) {
                                MobileSessionActionRequest.PIN -> old.copy(pinned = true, done = false)
                                MobileSessionActionRequest.UNPIN -> old.copy(pinned = false)
                                MobileSessionActionRequest.DONE -> old.copy(done = true, pinned = false)
                                MobileSessionActionRequest.REOPEN -> old.copy(done = false)
                                MobileSessionActionRequest.RENAME -> old.copy(title = action.title.orEmpty())
                                MobileSessionActionRequest.FOLDER -> old.copy(folderId = action.folderId?.takeIf { it.isNotBlank() })
                                else -> return json(JsonObject(), 400)
                            }
                            rows = rows.map { if (it.key == new.key) new else it }
                            json(MobileSessionActionResult(new.key, new.title, new.pinned, new.done, new.folderId, folders).toJson())
                        }
                        MobileSessionSearchRequest.ROUTE -> {
                            val search = MobileSessionSearchRequest.fromJson(body)
                            val read = search.keys.take(searchPage)
                            val hits = read.mapNotNull { key ->
                                bodies[key]?.takeIf { it.contains(search.query, ignoreCase = true) }
                                    ?.let { MobileSessionSearchHit(key, "…$it…") }
                            }
                            json(MobileSessionSearchResult(search.query, hits, scanned = read.size, examined = read.size,
                                timedOut = read.size < search.keys.size).toJson())
                        }
                        "/v1/answer" -> {
                            val request = MobileAnswerRequest.fromJson(body)
                            val runningAsk = page.turns.flatMap { it.toolCalls }
                                .firstOrNull { it.status == MobileToolCall.RUNNING && it.questions.isNotEmpty() }
                            // A pick made on a card the machine has since replaced with a newer ask
                            // must not settle that newer ask (J01): only the ask's own id may answer it.
                            if (request.askId != null && runningAsk != null && request.askId != runningAsk.id) {
                                return json(JsonObject().apply {
                                    addProperty("error", MobileRefusal.QUESTION_SUPERSEDED.code)
                                    addProperty("message", MobileRefusal.QUESTION_SUPERSEDED.message)
                                }, MobileRefusal.QUESTION_SUPERSEDED.status)
                            }
                            page = page.copy(turns = page.turns.map { turn ->
                                turn.copy(toolCalls = turn.toolCalls.map {
                                    if (it.id == (runningAsk?.id ?: it.id)) it.copy(status = MobileToolCall.OK) else it
                                })
                            })
                            clearWaiting(request.key)
                            json(MobileAnswerAccepted(parked = true).toJson())
                        }
                        "/v1/decision" -> {
                            val request = MobileDecisionRequest.fromJson(body)
                            val pending = page.pendingPermission
                            // A decision made on a card the machine has since replaced (a new ask,
                            // or the run moved on) must not settle the ask parked now (J01).
                            if (pending == null || pending.requestId != request.requestId) {
                                return json(JsonObject().apply {
                                    addProperty("error", MobileRefusal.PERMISSION_GONE.code)
                                    addProperty("message", MobileRefusal.PERMISSION_GONE.message)
                                }, MobileRefusal.PERMISSION_GONE.status)
                            }
                            page = page.copy(pendingPermission = null)
                            clearWaiting(request.key)
                            json(JsonObject())
                        }
                        MobileRestrictedMode.ROUTE -> {
                            val request = MobileRestrictedMode.fromJson(body) ?: return json(JsonObject(), 400)
                            restrictedMode = request.enabled
                            json(MobileRestrictedMode(request.enabled).toJson())
                        }
                        MobileAccountEdit.ROUTE -> {
                            val edit = MobileAccountEdit.fromJson(body) ?: return json(JsonObject(), 400)
                            val report = usageReport ?: return json(JsonObject(), 404)
                            when (edit.op) {
                                MobileAccountEdit.Op.RENAME -> {
                                    usageReport = report.copy(accounts = report.accounts.map {
                                        if (it.vendor == edit.vendor && it.id == edit.id) it.copy(label = edit.name.orEmpty()) else it
                                    })
                                    json(MobileAccountEdited(edit.id, edit.name).toJson())
                                }
                                else -> {
                                    usageReport = report.copy(accounts = report.accounts.filterNot { it.vendor == edit.vendor && it.id == edit.id })
                                    json(MobileAccountEdited(edit.id, null).toJson())
                                }
                            }
                        }
                        MobileMemoryQuery.ROUTE -> {
                            // A stale [MobileMemorySave.revision] is a conflict, never an overwrite (J11).
                            val save = MobileMemorySave.fromJson(body)
                            val stored = memoryFiles[save.id]
                            if (stored == null || stored.revision != save.revision) {
                                json(MobileMemorySaved(MobileMemorySaved.CONFLICT, current = stored).toJson())
                            } else {
                                val revision = MobileMemoryFile.revisionOf(true, save.content)
                                memoryFiles[save.id] = stored.copy(content = save.content, revision = revision, existed = true)
                                json(MobileMemorySaved(MobileMemorySaved.SAVED, revision = revision).toJson())
                            }
                        }
                        "/v1/unpair" -> json(JsonObject())
                        "/v1/stop" -> { page = page.copy(running = false, liveLine = null); json(JsonObject()) }
                        "/v1/scheduled" -> {
                            val ids = body["ids"].asJsonArray.map { it.asString }
                            when (body["action"].asString) {
                                MobileScheduledCommand.CANCEL -> scheduled = scheduled.filterNot { it.id in ids }
                                MobileScheduledCommand.PAUSE -> scheduled = scheduled.map { if (it.id in ids) it.copy(state = MobileScheduledRow.PAUSED) else it }
                                MobileScheduledCommand.RESUME -> scheduled = scheduled.map { if (it.id in ids) it.copy(state = MobileScheduledRow.QUEUED) else it }
                                // J09 "Run now": the row's series gains a past tense, and the run it
                                // wrote joins Recent runs — the desk's own outcome list — pointed at
                                // this fixture's one conversation so a tap has somewhere real to land.
                                MobileScheduledCommand.RUN_NOW -> ids.forEach { id ->
                                    val row = scheduled.firstOrNull { it.id == id } ?: return@forEach
                                    val now = System.currentTimeMillis()
                                    scheduled = scheduled.map { if (it.id == id) it.copy(lastRunAtMs = now, lastRunFailed = false) else it }
                                    outcomes = outcomes + MobileScheduledOutcome(
                                        taskId = id, prompt = row.prompt, projectPath = row.projectPath,
                                        key = page.key, vendor = AgentVendor.CLAUDE, finishedAtMs = now, failed = false, detail = null,
                                    )
                                }
                            }
                            json(JsonObject())
                        }
                        else -> json(JsonObject(), 404)
                    }
                }
                return when {
                    path == "/v1/hello" -> json(requireNotNull(fleetFixture.hello).let {
                        it.copy(
                            capabilities = it.capabilities + MobileProtocol.Capability.SCHEDULE_EDIT +
                                MobileProtocol.Capability.SCHEDULE_REPEAT +
                                MobileProtocol.Capability.ANSWER + MobileProtocol.Capability.ACCOUNTS +
                                MobileProtocol.Capability.EFFORT + MobileProtocol.Capability.PERMISSION_MODES +
                                MobileProtocol.Capability.PERMISSION_DECISIONS + extraCapabilities +
                                (if (organizes) setOf(MobileProtocol.Capability.SESSION_ACTIONS,
                                    MobileProtocol.Capability.SESSION_FOLDERS, MobileProtocol.Capability.SESSION_SEARCH) else emptySet()) +
                                (if (reviews) setOf(MobileProtocol.Capability.REVIEW, MobileProtocol.Capability.REVIEW_REVERT) else emptySet()) +
                                (if (acpAgents.isNotEmpty()) setOf(MobileProtocol.Capability.ACP_AGENTS) else emptySet()) +
                                (if (usageReport != null) setOf(MobileProtocol.Capability.USAGE) else emptySet()) +
                                (if (mcpServers != null) setOf(MobileProtocol.Capability.MCP_SERVERS) else emptySet()) +
                                (if (restrictedMode != null) setOf(MobileProtocol.Capability.RESTRICTED_MODE) else emptySet()) +
                                (if (memoryProject != null) setOf(MobileProtocol.Capability.MEMORY_FILES) else emptySet()) +
                                (if (attachmentsEnabled) setOf(MobileProtocol.Capability.ATTACHMENTS) else emptySet()) +
                                if (paging) setOf(MobileProtocol.Capability.TRANSCRIPT_PAGING) else emptySet(),
                            acpAgents = acpAgents,
                            timeZoneId = timeZoneId,
                            effort = runOptions.effort,
                            permissionModes = runOptions.permissionModes,
                            // One Claude account (no picker) and two Codex ones (a picker), so M06
                            // exercises both sides of the gate.
                            accounts = mapOf(
                                AgentVendor.CLAUDE to listOf(MobileScheduleAccountOption("default", "Personal")),
                                AgentVendor.CODEX to listOf(
                                    MobileScheduleAccountOption("codex-default", "ChatGPT"),
                                    MobileScheduleAccountOption("codex-work", "Work ChatGPT"),
                                ),
                            ),
                            activeAccounts = mapOf(AgentVendor.CLAUDE to "default", AgentVendor.CODEX to "codex-default"),
                        )
                    }.toJson())
                    path == "/v1/fleet" -> json(snapshot.toJson())
                    transcriptKey != null -> {
                        val before = request.requestUrl?.queryParameter("before")
                        if (before != null) {
                            earlierReads += before
                            if (expireHistory) return json(JsonObject().apply {
                                addProperty("error", "stale-cursor")
                                addProperty("message", "History changed. Refresh to load earlier messages.")
                            }, 409)
                        }
                        val response = json((before?.let { earlierPages[it] } ?: pages[transcriptKey] ?: page.copy(key = transcriptKey)).toJson())
                        transcriptGates.remove(transcriptKey)?.awaitRelease()
                        response
                    }
                    path.startsWith("/v1/review/") -> review(path, null, request.requestUrl?.queryParameter("path"))
                    path.startsWith("/v1/scheduled/") -> scheduleDetails[path.substringAfterLast('/')]?.let { json(it.toJson()) }
                        ?: json(JsonObject(), 404)
                    path == "/v1/scheduled" -> json(MobileScheduledList(scheduled, outcomes).toJson())
                    path == MobileMemoryQuery.ROUTE -> {
                        val project = request.requestUrl?.queryParameter("project")
                        val id = request.requestUrl?.queryParameter("id")
                        when {
                            project == null -> json(MobileMemoryProjects(listOfNotNull(memoryProject)).toJson())
                            id != null -> memoryFiles[id]?.let { json(it.toJson()) } ?: json(JsonObject(), 404)
                            else -> memoryEntries?.let { json(it.toJson()) } ?: json(JsonObject(), 404)
                        }
                    }
                    path == MobileUsageReport.ROUTE -> usageReport?.let { json(it.toJson()) } ?: json(JsonObject(), 404)
                    path == MobileMcpQuery.ROUTE -> mcpServers?.let { json(it.toJson()) } ?: json(JsonObject(), 404)
                    path == MobileRestrictedMode.ROUTE -> restrictedMode?.let { json(MobileRestrictedMode(it).toJson()) } ?: json(JsonObject(), 404)
                    path == "/v1/fleet/stream" -> MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody(": ready\n\n".repeat(10000)).throttleBody(9, 1, TimeUnit.SECONDS)
                    else -> json(JsonObject(), 404)
                }
            }
        }
        server.start()
        machine = requireNotNull(fixture.machine).copy(machineName = name, hosts = listOf("127.0.0.1"),
            preferredHost = "127.0.0.1", port = server.port, token = "story-token", deviceId = deviceId,
            spkiFingerprint = MobilePairing.fingerprint(certificate.certificate.publicKey.encoded))
    }

    /** The desk's user edits [path] of [key] after it was ticked: the machine clears the tick, as the exact-revision store does (J05). */
    fun editOnDesk(key: String, path: String, line: String) {
        reviewFiles[key] = reviewFiles.getValue(key).map { if (it.path == path) it.copy(reviewed = false, added = it.added + 1) else it }
        reviewHunks[path] = reviewHunks[path].orEmpty().map { it.copy(lines = it.lines + "+$line") }
        revertToken = "preview-${revertToken.substringAfter('-').toInt() + 1}"
        syncRow(key)
    }

    /** The next confirmed revert is read and then held until released; the desk has not answered yet. */
    fun holdNextRevert() = TranscriptGate().also { revertGate = it }

    /** The fleet row follows the checklist, as the desk's badge does: reviewed when every file is ticked. */
    fun syncRow(key: String) {
        val files = reviewFiles[key].orEmpty()
        rows = rows.map {
            if (it.key != key) it else it.copy(
                changedFiles = files.size, reviewedFiles = files.count { f -> f.reviewed },
                attention = if (files.isNotEmpty() && files.any { f -> !f.reviewed }) SessionAttentionState.DONE_UNREVIEWED else null,
            )
        }
    }

    /** The fleet row's own "Waiting on you" clears once its ask settles, as the desk's own row does (J01). */
    private fun clearWaiting(key: String) {
        rows = rows.map { if (it.key == key) it.copy(attention = null, waitingReason = null) else it }
    }

    private fun reviewList(key: String) = reviewFiles[key].orEmpty().let { files ->
        MobileReviewList(key, files, files.sumOf { it.added }, files.sumOf { it.removed }, files.count { it.reviewed })
    }

    private fun review(path: String, body: JsonObject?, filePath: String? = null): MockResponse {
        val parts = path.removePrefix("/v1/review/").split('/')
        val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
        if (reviewFiles[key] == null) return json(JsonObject(), 404)
        val files = reviewFiles.getValue(key)
        return when {
            parts.size == 1 && body == null -> json(reviewList(key).toJson())
            parts.size == 1 -> {
                val mark = MobileReviewMark.fromJson(body!!)
                val ticked = mark.paths.ifEmpty { files.map { it.path } }.toSet()
                reviewFiles[key] = files.map { if (it.path in ticked) it.copy(reviewed = mark.reviewed) else it }
                syncRow(key)
                json(reviewList(key).toJson())
            }
            parts[1] == "file" -> {
                val file = files.firstOrNull { it.path == filePath } ?: return json(JsonObject(), 404)
                json(MobileReviewFileDiff(file, reviewHunks[file.path].orEmpty()).toJson())
            }
            parts[1] == MobileReviewRevertRequest.SUFFIX && body == null -> json(MobileReviewRevertPreview(
                key,
                files.map { MobileReviewRevertFile(it.path, if (it.status == MobileReviewFile.ADDED) MobileReviewRevertFile.DELETE else MobileReviewRevertFile.RESTORE) },
                notes = listOf("Git is untouched.", "Edits made by hand since this chat are overwritten."),
                previewToken = revertToken,
            ).toJson())
            parts[1] == MobileReviewRevertRequest.SUFFIX -> revert(MobileReviewRevertRequest.fromJson(body!!))
            else -> json(JsonObject(), 404)
        }
    }

    private fun revert(request: MobileReviewRevertRequest): MockResponse {
        // A repeated operation id is answered with its first outcome, never a second write.
        revertOutcomes[request.operationId]?.let { return json(it.toJson()) }
        revertGate?.also { revertGate = null }?.awaitRelease()
        if (revertAnswer == RevertAnswer.STALE || request.previewToken != revertToken) {
            revertToken = "preview-${revertToken.substringAfter('-').toInt() + 1}"
            return json(JsonObject().apply {
                addProperty("error", MobileRefusal.REVERT_PREVIEW_STALE.code)
                addProperty("message", MobileRefusal.REVERT_PREVIEW_STALE.message)
            }, 409)
        }
        revertedPaths += request.paths
        reviewFiles[request.key] = reviewFiles.getValue(request.key).filterNot { it.path in request.paths }
        syncRow(request.key)
        val result = MobileReviewRevertResult(request.key, true, "Reverted ${request.paths.size} files.")
        revertOutcomes[request.operationId] = result
        return if (revertAnswer == RevertAnswer.LOST_ACK) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        else json(result.toJson())
    }

    /** `POST /v1/attach`: whatever bytes were sent are kept as they arrived and handed an id back (J11). */
    private fun attach(request: RecordedRequest): MockResponse {
        val bytes = request.body.readByteArray()
        val id = "attach-${nextAttachmentId++}"
        attachments[id] = bytes
        return json(MobileAttachmentAccepted(id, bytes.size.toLong()).toJson())
    }

    fun holdNextTranscript(key: String) = TranscriptGate().also { transcriptGates[key] = it }

    internal class TranscriptGate {
        val received = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val released = CountDownLatch(1)
        fun release() { release.countDown() }
        fun awaitRelease() {
            received.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "Held transcript was not released" }
            released.countDown()
        }
    }

    private fun json(body: JsonObject, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString())

    override fun close() { server.shutdown() }
}
