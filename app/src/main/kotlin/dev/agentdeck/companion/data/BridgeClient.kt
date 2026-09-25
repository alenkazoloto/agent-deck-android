package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileDiffView
import com.github.claudeagents.core.mobile.MobileReviewSort
import com.github.claudeagents.core.mobile.MobileMachineBehaviour
import com.github.claudeagents.core.mobile.MobileActiveAccount
import com.github.claudeagents.core.mobile.MobileNewChatDefaults
import com.github.claudeagents.core.mobile.MobileScheduleSettings
import com.github.claudeagents.core.mobile.MobileReviewView
import com.github.claudeagents.core.mobile.MobileAnswerAccepted
import com.github.claudeagents.core.mobile.MobileAnswerRequest
import com.github.claudeagents.core.mobile.MobileDecisionRequest
import com.github.claudeagents.core.mobile.MobileAttachment
import com.github.claudeagents.core.mobile.MobileAttachmentAccepted
import com.github.claudeagents.core.mobile.MobileCommandList
import com.github.claudeagents.core.mobile.MobileCommandQuery
import com.github.claudeagents.core.mobile.MobileHosts
import com.github.claudeagents.core.mobile.MobileIssueList
import com.github.claudeagents.core.mobile.MobileIssueQuery
import com.github.claudeagents.core.mobile.MobileFileList
import com.github.claudeagents.core.mobile.MobileFileQuery
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileImage
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobilePhoneLog
import com.github.claudeagents.core.mobile.MobilePhoneLogAccepted
import com.github.claudeagents.core.mobile.MobilePhoneLogRequest
import com.github.claudeagents.core.mobile.MobileBadges
import com.github.claudeagents.core.mobile.MobileBadgesQuery
import com.github.claudeagents.core.mobile.MobileMemoryDelete
import com.github.claudeagents.core.mobile.MobileMcpQuery
import com.github.claudeagents.core.mobile.MobileMcpServers
import com.github.claudeagents.core.mobile.MobileSkillsList
import com.github.claudeagents.core.mobile.MobileSkillsQuery
import com.github.claudeagents.core.mobile.MobileMemoryEntries
import com.github.claudeagents.core.mobile.MobileMemoryFile
import com.github.claudeagents.core.mobile.MobileMemoryProjects
import com.github.claudeagents.core.mobile.MobileMemoryQuery
import com.github.claudeagents.core.mobile.MobileMemorySave
import com.github.claudeagents.core.mobile.MobileMemorySaved
import com.github.claudeagents.core.mobile.MobileOrchestration
import com.github.claudeagents.core.mobile.MobileOrchestrationQuery
import com.github.claudeagents.core.mobile.MobilePromptHistory
import com.github.claudeagents.core.mobile.MobilePromptQuery
import com.github.claudeagents.core.mobile.MobilePush
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledList
import com.github.claudeagents.core.mobile.MobileCommitStagedPreview
import com.github.claudeagents.core.mobile.MobileCommitStagedRequest
import com.github.claudeagents.core.mobile.MobileCommitStagedResult
import com.github.claudeagents.core.mobile.MobileReviewCommitPreview
import com.github.claudeagents.core.mobile.MobileReviewCommitRequest
import com.github.claudeagents.core.mobile.MobileReviewCommitResult
import com.github.claudeagents.core.mobile.MobileWorktreeCreateRequest
import com.github.claudeagents.core.mobile.MobileWorktreeCreateResult
import com.github.claudeagents.core.mobile.MobileWorktreeOptions
import com.github.claudeagents.core.mobile.MobileWorktreeActionRequest
import com.github.claudeagents.core.mobile.MobileWorktreeActionResult
import com.github.claudeagents.core.mobile.MobileWorktreeFleet
import com.github.claudeagents.core.mobile.MobileChangeRequestReview
import com.github.claudeagents.core.mobile.MobileChangeRequestReviewRequest
import com.github.claudeagents.core.mobile.MobileRepositorySetupOptions
import com.github.claudeagents.core.mobile.MobileRepositorySetupRequest
import com.github.claudeagents.core.mobile.MobileRepositorySetupResult
import com.github.claudeagents.core.mobile.MobileReviewRevertPreview
import com.github.claudeagents.core.mobile.MobileReviewRevertRequest
import com.github.claudeagents.core.mobile.MobileReviewRevertResult
import com.github.claudeagents.core.mobile.MobileReviewNoteRequest
import com.github.claudeagents.core.mobile.MobileReviewNotes
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileReviewMark
import com.github.claudeagents.core.mobile.MobileFolderActionRequest
import com.github.claudeagents.core.mobile.MobileFolderActionResult
import com.github.claudeagents.core.mobile.MobileSessionActionRequest
import com.github.claudeagents.core.mobile.MobileSessionActionResult
import com.github.claudeagents.core.mobile.MobileSessionSearchRequest
import com.github.claudeagents.core.mobile.MobileContextBreakdown
import com.github.claudeagents.core.mobile.MobileContextQuery
import com.github.claudeagents.core.mobile.MobileSessionDirs
import com.github.claudeagents.core.mobile.MobileSessionDirsQuery
import com.github.claudeagents.core.mobile.MobileSessionDirsRequest
import com.github.claudeagents.core.mobile.MobileSessionMcp
import com.github.claudeagents.core.mobile.MobileSessionMcpQuery
import com.github.claudeagents.core.mobile.MobileSessionMcpRequest
import com.github.claudeagents.core.mobile.MobileSessionSpend
import com.github.claudeagents.core.mobile.MobileSessionSpendQuery
import com.github.claudeagents.core.mobile.MobileSessionSpendRequest
import com.github.claudeagents.core.mobile.MobileSessionExport
import com.github.claudeagents.core.mobile.MobileSessionDeletePreview
import com.github.claudeagents.core.mobile.MobileSessionDeleteRequest
import com.github.claudeagents.core.mobile.MobileSessionDeleteResult
import com.github.claudeagents.core.mobile.MobileSessionForkPoints
import com.github.claudeagents.core.mobile.MobileAiReviewExcerpt
import com.github.claudeagents.core.mobile.MobileAiReviewFinding
import com.github.claudeagents.core.mobile.MobileAiReviewRequest
import com.github.claudeagents.core.mobile.MobileAiReviewState
import com.github.claudeagents.core.mobile.MobileSideQuestionQuery
import com.github.claudeagents.core.mobile.MobileSideQuestionRequest
import com.github.claudeagents.core.mobile.MobileSideQuestionState
import com.github.claudeagents.core.mobile.MobileWorkingDiff
import com.github.claudeagents.core.mobile.MobileSessionForkRequest
import com.github.claudeagents.core.mobile.MobileSessionForkResult
import com.github.claudeagents.core.mobile.MobileSessionHandoffRequest
import com.github.claudeagents.core.mobile.MobileSessionHandoffResult
import com.github.claudeagents.core.mobile.MobileSessionRewindPoints
import com.github.claudeagents.core.mobile.MobileSessionRewindRequest
import com.github.claudeagents.core.mobile.MobileSessionRewindResult
import com.github.claudeagents.core.mobile.MobileSessionSearchResult
import com.github.claudeagents.core.mobile.MobileSendAccepted
import com.github.claudeagents.core.mobile.MobileSendRequest
import com.github.claudeagents.core.mobile.MobileStopRequest
import com.github.claudeagents.core.mobile.MobileToolResult
import com.github.claudeagents.core.mobile.MobileUsageExport
import com.github.claudeagents.core.mobile.MobileUsageReport
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.CancellationException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * The bridge said no, and said why. [message] is the plugin's own sentence — a compile-time
 * constant on the IDE side with no user data in it — and the app shows it **verbatim**.
 * Rewording it here would put this app's guess in front of the user instead of the reason
 * the machine actually has.
 */
class BridgeRefusal(
    val status: Int,
    val code: String?,
    override val message: String,
) : IOException(message) {

    /** The pairing is gone on the machine's side; the app must return to pairing. */
    val isRevoked: Boolean
        get() = code == MobileRefusal.DEVICE_REVOKED.code || code == MobileRefusal.UNAUTHORIZED.code
}

class BridgeDeliveryUncertain(cause: IOException) : IOException(
    "The connection was interrupted before confirmation. Check the latest state before trying again.",
    cause,
)

/**
 * Every call the phone makes, over `javax.net.ssl.HttpsURLConnection`.
 *
 * Not `java.net.http.HttpClient`: that is a JDK module and does not exist on Android at any
 * API level. URLConnection is enough — the protocol is small, and SSE is a line-oriented
 * read this can do directly.
 *
 * Every connection gets the pinning socket factory and the always-true hostname verifier;
 * see [Pinning]. All methods block and are meant for a background dispatcher.
 */
class BridgeClient internal constructor(
    private val hosts: List<String>,
    private val port: Int,
    private val spkiFingerprint: String,
    private val token: String?,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : LinkConnection {

    constructor(machine: PairedMachine) : this(
        machine.dialOrder(),
        machine.port,
        machine.spkiFingerprint,
        machine.token,
    )

    /** The host that answered last, so the UI can persist it and skip the walk next time. */
    @Volatile
    override var lastGoodHost: String? = null
        private set

    // ---- routes ----------------------------------------------------------------------

    fun hello(): MobileHello = MobileHello.fromJson(get("/v1/hello", authorized = false))

    fun pair(code: String, label: String): PairAccepted {
        val body = JsonObject().apply {
            addProperty("v", MobileProtocol.VERSION)
            addProperty("code", code)
            addProperty("label", label)
        }
        val answer = post("/v1/pair", body, authorized = false)
        return PairAccepted(
            token = answer.get("token")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            deviceId = answer.get("deviceId")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            machineName = answer.get("machine")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
        )
    }

    override fun fleet(): MobileFleetSnapshot = MobileFleetSnapshot.fromJson(get("/v1/fleet"))

    fun transcript(key: String, paging: Boolean = false, before: String? = null): MobileTranscriptPage {
        require(before == null || paging)
        val query = if (paging) "?paging=1" + (before?.let {
            "&before=" + URLEncoder.encode(it, "UTF-8")
        } ?: "") else ""
        return MobileTranscriptPage.fromJson(get("/v1/session/" + URLEncoder.encode(key, "UTF-8") + query))
    }

    /**
     * `GET /v1/tool/{key}/{callId}` — the whole body behind a call whose page copy was cut.
     *
     * Asked for only when the reader opens a result the page had to shorten: the page already
     * carries the head and the tail of every call, so this is a second request for the one
     * output somebody is actually reading.
     */
    fun toolResult(key: String, callId: String): MobileToolResult = MobileToolResult.fromJson(
        get("/v1/tool/" + URLEncoder.encode(key, "UTF-8") + "/" + URLEncoder.encode(callId, "UTF-8")),
    )

    /**
     * `GET /v1/image` — one picture out of a conversation's markdown, as the PNG the machine
     * decoded and re-encoded for it ([MobileImage]). A refusal is the machine's "not this one".
     */
    fun image(key: String, src: String): ByteArray = walkHosts { host ->
        val query = "?key=" + URLEncoder.encode(key, "UTF-8") + "&src=" + URLEncoder.encode(src, "UTF-8")
        val connection = open(host, MobileImage.ROUTE + query, authorized = true)
        try {
            connection.setRequestProperty("Accept", MobileImage.CONTENT_TYPE)
            val status = connection.responseCode
            if (status !in 200..299) throw refusalFrom(connection, status)
            val bytes = connection.inputStream.use { it.readNBytes(MobileImage.MAX_BYTES + 1) }
            if (bytes.size > MobileImage.MAX_BYTES) throw IOException("This machine sent a picture larger than it promised.")
            bytes
        } finally {
            release(connection)
        }
    }

    /**
     * `GET /v1/review/{key}` — which files this conversation changed, and which are ticked.
     *
     * Carries no line of any file: the checklist is what the reader opens first, and paying for
     * every diff to show it is how a twelve-file conversation becomes a megabyte.
     */
    fun review(key: String, requests: Boolean = false): MobileReviewList =
        MobileReviewList.fromJson(get("/v1/review/" + URLEncoder.encode(key, "UTF-8") + if (requests) "?requests=1" else ""))

    /**
     * `GET /v1/review/{key}/file?path=` — one file's hunks, already cut by the machine;
     * `&request=` for that request's own change of the file, `&base=` for it against a git revision.
     */
    fun reviewFile(key: String, path: String, request: String? = null, base: String? = null): MobileReviewFileDiff = MobileReviewFileDiff.fromJson(
        get(
            "/v1/review/" + URLEncoder.encode(key, "UTF-8") + "/file?path=" +
                URLEncoder.encode(path, "UTF-8") +
                request.orEmpty().let { if (it.isEmpty()) "" else "&request=" + URLEncoder.encode(it, "UTF-8") } +
                base.orEmpty().let { if (it.isEmpty()) "" else "&base=" + URLEncoder.encode(it, "UTF-8") },
        ),
    )

    /**
     * `POST /v1/diff-view` — the desk's "Ignore whitespace when comparing files"; answers the value
     * now in force on the machine.
     */
    fun setDiffView(ignoreWhitespace: Boolean): MobileDiffView =
        MobileDiffView.fromJson(post(MobileDiffView.ROUTE, MobileDiffView(ignoreWhitespace).toJson()))
            ?: throw java.io.IOException("The machine answered without its whitespace setting.")

    /** `GET /v1/machine-behaviour` — the desk's "Keep this computer awake while an agent works". */
    fun machineBehaviour(): MobileMachineBehaviour =
        MobileMachineBehaviour.fromJson(get(MobileMachineBehaviour.ROUTE))
            ?: throw java.io.IOException("The machine answered without its keep-awake setting.")

    /** `POST /v1/machine-behaviour` — writes the switch; answers the value now stored. */
    fun setPreventSleep(on: Boolean): MobileMachineBehaviour =
        MobileMachineBehaviour.fromJson(post(MobileMachineBehaviour.ROUTE, MobileMachineBehaviour(on).toJson()))
            ?: throw java.io.IOException("The machine answered without its keep-awake setting.")

    /** `GET /v1/new-chat-defaults` — the desk's "Start new chats in" pin and the words it offers. */
    fun newChatDefaults(): MobileNewChatDefaults =
        MobileNewChatDefaults.fromJson(get(MobileNewChatDefaults.ROUTE))
            ?: throw java.io.IOException("The machine answered without its new-chat mode.")

    /** `POST /v1/new-chat-defaults` — pins new chats to [mode]; answers the pin now stored. */
    fun setNewChatMode(mode: String): MobileNewChatDefaults =
        MobileNewChatDefaults.fromJson(post(MobileNewChatDefaults.ROUTE, MobileNewChatDefaults(mode).toJson()))
            ?: throw java.io.IOException("The machine answered without its new-chat mode.")

    /** `POST /v1/active-account` — pins new chats of [vendor] to [id], the desk's "Set active"; answers the account now stored. */
    fun setActiveAccount(vendor: AgentVendor, id: String): MobileActiveAccount =
        MobileActiveAccount.fromJson(post(MobileActiveAccount.ROUTE, MobileActiveAccount(vendor, id).toJson()))
            ?: throw java.io.IOException("The machine answered without its active account.")

    /** `POST /v1/schedule-settings` — Settings › Scheduled's overlapping-runs switch; answers the value now stored. */
    fun setScheduleOverlap(allow: Boolean): MobileScheduleSettings =
        MobileScheduleSettings.fromJson(post(MobileScheduleSettings.ROUTE, MobileScheduleSettings(allow).toJson()))
            ?: throw java.io.IOException("The machine answered without its overlapping-runs setting.")

    /** `POST /v1/review-sort` — the desk review's "Sort by"; answers the order now in force. */
    fun setReviewSort(order: String): MobileReviewSort =
        MobileReviewSort.fromJson(post(MobileReviewSort.ROUTE, MobileReviewSort(order).toJson()))
            ?: throw java.io.IOException("The machine answered without its sort order.")

    /** `POST /v1/review-view` — the desk checklist's view options; answers both as the desk now stores them. */
    fun setReviewView(view: MobileReviewView): MobileReviewView =
        MobileReviewView.fromJson(post(MobileReviewView.ROUTE, view.toJson()))

    /** `POST /v1/review/{key}` — empty [paths] is the header's own "mark everything". */
    fun markReviewed(key: String, paths: List<String>, reviewed: Boolean): MobileReviewList =
        MobileReviewList.fromJson(
            post("/v1/review/" + URLEncoder.encode(key, "UTF-8"), MobileReviewMark(paths, reviewed).toJson()),
        )

    /** `GET /v1/review/{key}/commit` — what committing this conversation's files would record. */
    fun commitPreview(key: String): MobileReviewCommitPreview = MobileReviewCommitPreview.fromJson(
        get("/v1/review/" + URLEncoder.encode(key, "UTF-8") + "/" + MobileReviewCommitRequest.SUFFIX),
    )

    /**
     * `POST /v1/review/{key}/commit` — the confirmed commit. The machine answers a repeated
     * [MobileReviewCommitRequest.operationId] with its first outcome, so a retry after a lost
     * answer is safe to send with the same id.
     */
    fun commit(request: MobileReviewCommitRequest): MobileReviewCommitResult = MobileReviewCommitResult.fromJson(
        post("/v1/review/" + URLEncoder.encode(request.key, "UTF-8") + "/" + MobileReviewCommitRequest.SUFFIX, request.toJson()),
    )

    /**
     * `GET /v1/review/{key}/revert` — what reverting this conversation's files to its start would
     * do, or with a [request] what the per-request [scope] would.
     */
    fun revertPreview(
        key: String,
        scope: String = MobileReviewRevertRequest.SESSION,
        request: String? = null,
    ): MobileReviewRevertPreview = MobileReviewRevertPreview.fromJson(
        get(
            "/v1/review/" + URLEncoder.encode(key, "UTF-8") + "/" + MobileReviewRevertRequest.SUFFIX +
                (request?.let { "?scope=" + URLEncoder.encode(scope, "UTF-8") + "&request=" + URLEncoder.encode(it, "UTF-8") } ?: ""),
        ),
    )

    /**
     * `POST /v1/review/{key}/revert` — the confirmed revert. The machine answers a repeated
     * [MobileReviewRevertRequest.operationId] with its first outcome, so a retry is safe.
     */
    fun revert(request: MobileReviewRevertRequest): MobileReviewRevertResult = MobileReviewRevertResult.fromJson(
        post("/v1/review/" + URLEncoder.encode(request.key, "UTF-8") + "/" + MobileReviewRevertRequest.SUFFIX, request.toJson()),
    )

    /** `GET /v1/review/{key}/notes` — the conversation's saved review notes. */
    fun reviewNotes(key: String): MobileReviewNotes = MobileReviewNotes.fromJson(
        get("/v1/review/" + URLEncoder.encode(key, "UTF-8") + "/" + MobileReviewNoteRequest.SUFFIX),
    )

    /** `GET /v1/review/{key}/ai-review` — Codex's review of this chat's project: running, or its findings. */
    fun aiReviewState(key: String): MobileAiReviewState = MobileAiReviewState.fromJson(
        get("/v1/review/" + URLEncoder.encode(key, "UTF-8") + "/" + MobileAiReviewRequest.SUFFIX),
    )

    /** `GET /v1/review/{key}/ai-review?finding=` — finding [index]'s file around its lines, the desk dialog's opening of it. */
    fun aiReviewExcerpt(key: String, index: Int, finding: MobileAiReviewFinding): MobileAiReviewExcerpt = MobileAiReviewExcerpt.fromJson(
        get(
            "/v1/review/" + URLEncoder.encode(key, "UTF-8") + "/" + MobileAiReviewRequest.SUFFIX +
                "?" + MobileAiReviewExcerpt.FINDING + "=" + index +
                "&" + MobileAiReviewExcerpt.PATH + "=" + URLEncoder.encode(finding.path, "UTF-8") +
                "&" + MobileAiReviewExcerpt.LINE + "=" + finding.startLine,
        ),
    )

    /** `POST /v1/review/{key}/ai-review` — starts or stops that review; answers at once with the state. */
    fun aiReview(request: MobileAiReviewRequest): MobileAiReviewState = MobileAiReviewState.fromJson(
        post("/v1/review/" + URLEncoder.encode(request.key, "UTF-8") + "/" + MobileAiReviewRequest.SUFFIX, request.toJson()),
    )

    /**
     * `GET /v1/review/{key}/working-diff[?path=]` — a Codex chat's `/diff`: the repository's
     * uncommitted files, or [path] alone. git may take a while on a large tree; the machine answers
     * "slow" before this read runs out.
     */
    fun workingDiff(key: String, path: String? = null): MobileWorkingDiff = MobileWorkingDiff.fromJson(
        get(
            "/v1/review/" + URLEncoder.encode(key, "UTF-8") + "/" + MobileWorkingDiff.SUFFIX +
                (path?.let { "?path=" + URLEncoder.encode(it, "UTF-8") } ?: ""),
            readTimeoutMs = LinkPolicy.EXPORT_READ_TIMEOUT_MS,
            // A slow read is still running git on the machine; another address would start a second one.
            retry = { it !is java.net.SocketTimeoutException },
        ),
    )

    /** `POST /v1/review/{key}/notes` — one note write; answers the whole list, and the token for an attach. */
    fun reviewNote(request: MobileReviewNoteRequest): MobileReviewNotes = MobileReviewNotes.fromJson(
        post("/v1/review/" + URLEncoder.encode(request.key, "UTF-8") + "/" + MobileReviewNoteRequest.SUFFIX, request.toJson()),
    )

    /** `POST /v1/session-actions` — pin, Done, reopen or rename; answers the machine's resulting state. */
    fun sessionAction(request: MobileSessionActionRequest): MobileSessionActionResult =
        MobileSessionActionResult.fromJson(post(MobileSessionActionRequest.ROUTE, request.toJson()))

    /**
     * `retitle` on `/v1/session-actions` — a model turn on the machine, so it waits past the usual
     * read; the machine answers "still writing" before this runs out.
     */
    fun retitle(key: String): MobileSessionActionResult = MobileSessionActionResult.fromJson(
        post(
            MobileSessionActionRequest.ROUTE,
            MobileSessionActionRequest(key, MobileSessionActionRequest.RETITLE).toJson(),
            readTimeoutMs = LinkPolicy.RETITLE_READ_TIMEOUT_MS,
        ),
    )

    fun folderAction(request: MobileFolderActionRequest): MobileFolderActionResult =
        MobileFolderActionResult.fromJson(post(MobileFolderActionRequest.ROUTE, request.toJson()))

    /** `GET /v1/session-export?key=` — the chat as the desk's `/export` text, for the share sheet. */
    fun exportSession(key: String): MobileSessionExport = MobileSessionExport.fromJson(
        get(
            MobileSessionExport.ROUTE + "?key=" + URLEncoder.encode(key, "UTF-8"),
            readTimeoutMs = LinkPolicy.EXPORT_READ_TIMEOUT_MS,
            // A slow export is still running on the machine; another address would start a second one.
            retry = { it !is java.net.SocketTimeoutException },
        ),
    )

    /** `GET /v1/context?key=` — how the chat's context window is divided, in the desk's words. */
    fun contextBreakdown(key: String): MobileContextBreakdown = MobileContextBreakdown.fromJson(
        get(MobileContextQuery.ROUTE + "?key=" + URLEncoder.encode(key, "UTF-8")),
    )

    /** `GET /v1/side-question?key=` — the `/btw` asks the machine holds for this chat, answered or still running. */
    fun sideQuestion(key: String): MobileSideQuestionState = MobileSideQuestionState.fromJson(
        get(MobileSideQuestionQuery.ROUTE + "?key=" + URLEncoder.encode(key, "UTF-8")),
    )

    /** `POST /v1/side-question` — starts one ask; answers at once with the state, the answer arrives through [sideQuestion]. */
    fun askSideQuestion(request: MobileSideQuestionRequest): MobileSideQuestionState = MobileSideQuestionState.fromJson(
        post(MobileSideQuestionQuery.ROUTE, request.toJson()),
    )

    /** `GET /v1/session-dirs?key=` — the chat's additional working directories; refused with `permissions-disabled` for a phone without the grant. */
    fun sessionDirs(key: String): MobileSessionDirs = MobileSessionDirs.fromJson(
        get(MobileSessionDirsQuery.ROUTE + "?key=" + URLEncoder.encode(key, "UTF-8")),
    )

    /** `POST /v1/session-dirs` — one typed add or one removal; a path the desk would refuse comes back as [MobileSessionDirs.refused], not an error. */
    fun changeSessionDirs(request: MobileSessionDirsRequest): MobileSessionDirs =
        MobileSessionDirs.fromJson(post(MobileSessionDirsQuery.ROUTE, request.toJson()))

    /** `GET /v1/session-mcp?key=` — the Claude chat's MCP servers and which it uses; refused with `permissions-disabled` for a phone without the grant. */
    fun sessionMcp(key: String): MobileSessionMcp = MobileSessionMcp.fromJson(
        get(MobileSessionMcpQuery.ROUTE + "?key=" + URLEncoder.encode(key, "UTF-8")),
    )

    /** `POST /v1/session-mcp` — the whole selection, or all; a server the machine no longer configures comes back as [MobileSessionMcp.refused], not an error. */
    fun changeSessionMcp(request: MobileSessionMcpRequest): MobileSessionMcp =
        MobileSessionMcp.fromJson(post(MobileSessionMcpQuery.ROUTE, request.toJson()))

    /** `GET /v1/session-spend?key=` — the chat's own spend limits, or the global defaults it inherits; refused with `permissions-disabled` for a phone without the grant. */
    fun sessionSpend(key: String): MobileSessionSpend = MobileSessionSpend.fromJson(
        get(MobileSessionSpendQuery.ROUTE + "?key=" + URLEncoder.encode(key, "UTF-8")),
    )

    /** `GET /v1/session-spend?defaults=1` — the machine-wide defaults every chat without limits of its own runs on, with the generation a save must name; needs `session-spend-defaults`. */
    fun sessionSpendDefaults(): MobileSessionSpend = MobileSessionSpend.fromJson(get(MobileSessionSpendQuery.DEFAULTS))

    /** `POST /v1/session-spend` — save the whole form, or drop the chat's own limits; a form the desk would refuse comes back as [MobileSessionSpend.refused], not an error. */
    fun changeSessionSpend(request: MobileSessionSpendRequest): MobileSessionSpend =
        MobileSessionSpend.fromJson(post(MobileSessionSpendQuery.ROUTE, request.toJson()))

    /** `POST /v1/session-delete` without a token — what deleting [key] would do; nothing is reserved. */
    fun previewDelete(key: String): MobileSessionDeletePreview =
        MobileSessionDeletePreview.fromJson(post(MobileSessionDeleteRequest.ROUTE, MobileSessionDeleteRequest(key).toJson()))

    /** `POST /v1/session-delete` with the confirmed preview's token — the desk's own delete. */
    fun delete(preview: MobileSessionDeletePreview): MobileSessionDeleteResult = MobileSessionDeleteResult.fromJson(
        post(MobileSessionDeleteRequest.ROUTE, MobileSessionDeleteRequest(preview.key, preview.previewToken).toJson()),
    )

    /** `POST /v1/session-fork` without a point — the messages [key] can be forked from; nothing is written. */
    fun forkPoints(key: String): MobileSessionForkPoints =
        MobileSessionForkPoints.fromJson(post(MobileSessionForkRequest.ROUTE, MobileSessionForkRequest(key).toJson()))

    /** `POST /v1/session-handoff` — the desk's "Continue on <account>": the chat copied onto [MobileSessionHandoffRequest.accountId]. */
    fun handoff(request: MobileSessionHandoffRequest): MobileSessionHandoffResult =
        MobileSessionHandoffResult.fromJson(post(MobileSessionHandoffRequest.ROUTE, request.toJson()))

    /** `POST /v1/session-fork` naming a point (Claude) or the whole thread (Codex) — the desk's own fork. */
    fun fork(request: MobileSessionForkRequest): MobileSessionForkResult =
        MobileSessionForkResult.fromJson(post(MobileSessionForkRequest.ROUTE, request.toJson()))

    /** `GET /v1/worktrees?project=` — the name and bases the desk's "New chat in worktree…" would offer. */
    fun worktreeOptions(projectPath: String): MobileWorktreeOptions = MobileWorktreeOptions.fromJson(
        get(MobileWorktreeCreateRequest.ROUTE + "?project=" + URLEncoder.encode(projectPath, "UTF-8")),
    )

    /** `POST /v1/worktrees` — create, or read the create this operation id started (`creating` until it settles). */
    fun createWorktree(request: MobileWorktreeCreateRequest): MobileWorktreeCreateResult =
        MobileWorktreeCreateResult.fromJson(post(MobileWorktreeCreateRequest.ROUTE, request.toJson()))

    /** `GET /v1/commit-staged?key=` — what is staged in the conversation's checkout. */
    fun commitStagedPreview(key: String): MobileCommitStagedPreview = MobileCommitStagedPreview.fromJson(
        get(MobileCommitStagedRequest.ROUTE + "?key=" + URLEncoder.encode(key, "UTF-8")),
    )

    /** `POST /v1/commit-staged` — commit or write the message, or read the one this operation id started (`working` until it settles). */
    fun commitStaged(request: MobileCommitStagedRequest): MobileCommitStagedResult =
        MobileCommitStagedResult.fromJson(post(MobileCommitStagedRequest.ROUTE, request.toJson()))

    /** `GET /v1/worktree-fleet?project=` — the rows the desk's "Manage worktrees…" would show. */
    fun worktreeFleet(projectPath: String): MobileWorktreeFleet = MobileWorktreeFleet.fromJson(
        get(MobileWorktreeActionRequest.ROUTE + "?project=" + URLEncoder.encode(projectPath, "UTF-8")),
    )

    /** `POST /v1/worktree-fleet` — merge, remove or prune, or the state of the one this operation id started. */
    fun worktreeAction(request: MobileWorktreeActionRequest): MobileWorktreeActionResult =
        MobileWorktreeActionResult.fromJson(post(MobileWorktreeActionRequest.ROUTE, request.toJson()))

    /** `GET /v1/repository-setup` — the desk's Clone/Publish dialogs' hosts and directories, and whether Publish applies. */
    fun repositorySetup(projectPath: String): MobileRepositorySetupOptions =
        MobileRepositorySetupOptions.fromJson(get(MobileRepositorySetupRequest.ROUTE + "?project=" + URLEncoder.encode(projectPath, "UTF-8")))

    /** `POST /v1/repository-setup` — clone, publish or open the clone, or the state of the one this operation id started. */
    fun repositorySetupAction(request: MobileRepositorySetupRequest): MobileRepositorySetupResult =
        MobileRepositorySetupResult.fromJson(post(MobileRepositorySetupRequest.ROUTE, request.toJson()))

    /** `GET /v1/change-request-review` — the worktree branch's open request and threads, as the desk's review dialog reads them. */
    fun changeRequestReview(projectPath: String, path: String, branch: String, requestBranch: String? = null): MobileChangeRequestReview =
        MobileChangeRequestReview.fromJson(
            get(
                MobileChangeRequestReviewRequest.ROUTE + "?project=" + URLEncoder.encode(projectPath, "UTF-8") +
                    "&path=" + URLEncoder.encode(path, "UTF-8") + "&branch=" + URLEncoder.encode(branch, "UTF-8") +
                    requestBranch?.let { "&request=" + URLEncoder.encode(it, "UTF-8") }.orEmpty(),
            ),
        )

    /** `POST /v1/change-request-review` — reply, resolve or a verdict, or the state of the one this operation id started. */
    fun changeRequestReviewAction(request: MobileChangeRequestReviewRequest): MobileWorktreeActionResult =
        MobileWorktreeActionResult.fromJson(post(MobileChangeRequestReviewRequest.ROUTE, request.toJson()))

    /** `POST /v1/session-rewind` without a point — the messages a Claude chat can go back to before. */
    fun rewindPoints(key: String): MobileSessionRewindPoints =
        MobileSessionRewindPoints.fromJson(post(MobileSessionRewindRequest.ROUTE, MobileSessionRewindRequest(key).toJson()))

    /** `POST /v1/session-rewind` with a point — the conversation half of the desk's `/rewind`. */
    fun rewind(request: MobileSessionRewindRequest): MobileSessionRewindResult =
        MobileSessionRewindResult.fromJson(post(MobileSessionRewindRequest.ROUTE, request.toJson()))

    /** `POST /v1/session-search` — one page of the desk's message search over the chats named. */
    fun searchMessages(request: MobileSessionSearchRequest): MobileSessionSearchResult =
        MobileSessionSearchResult.fromJson(post(MobileSessionSearchRequest.ROUTE, request.toJson()))

    /**
     * `GET /v1/usage` — the machine's period cards and every account's live plan windows.
     *
     * One read for the whole screen: the per-day merge is the expensive half and the plan
     * windows are already in memory on the other side, so splitting them would buy a second
     * round trip for a page the reader opens once.
     */
    /** `GET /v1/hosts` — where the machine answers now; see [PairedMachine.learningHosts]. */
    fun hosts(): MobileHosts = MobileHosts.fromJson(get(MobileHosts.ROUTE))

    fun usage(agent: AgentVendor? = null, account: String? = null): MobileUsageReport =
        MobileUsageReport.fromJson(get(MobileUsageReport.ROUTE + usageQuery(agent, account)))

    /**
     * `GET /v1/usage-export` — the desk's CSV of the spend the screen shows under this Agent / Account
     * pick, for the share sheet. Read timeout as the chat export's: the machine walks its whole index.
     */
    fun usageExport(agent: AgentVendor? = null, account: String? = null): MobileUsageExport = MobileUsageExport.fromJson(
        get(
            MobileUsageExport.ROUTE + usageQuery(agent, account),
            readTimeoutMs = LinkPolicy.EXPORT_READ_TIMEOUT_MS,
            retry = { it !is java.net.SocketTimeoutException },
        ),
    )

    private fun usageQuery(agent: AgentVendor?, account: String?): String = listOfNotNull(
        agent?.let { "${MobileUsageReport.AGENT_PARAM}=" + URLEncoder.encode(it.name, "UTF-8") },
        account?.let { "${MobileUsageReport.ACCOUNT_PARAM}=" + URLEncoder.encode(it, "UTF-8") },
    ).joinToString("&", prefix = "?").takeIf { agent != null || account != null }.orEmpty()

    /** `GET /v1/orchestration` — Settings › Orchestration's team boards and workflow runs, read-only. */
    fun orchestration(): MobileOrchestration = MobileOrchestration.fromJson(get(MobileOrchestrationQuery.ROUTE))

    /** `GET /v1/badges` — Resources › Badges' gallery and each earned badge's share line, read-only. */
    fun badges(): MobileBadges = MobileBadges.fromJson(get(MobileBadgesQuery.ROUTE))

    /** `GET /v1/mcp[?project=]` — the desk's MCP server tables, redacted to names, programs and hosts; read-only. */
    fun mcpServers(project: String?): MobileMcpServers = MobileMcpServers.fromJson(
        get(MobileMcpQuery.ROUTE + (project?.let { "?project=" + URLEncoder.encode(it, "UTF-8") } ?: "")),
    )

    /** `GET /v1/skills[?project=]` — the desk's skills, commands and subagents of an open project as descriptions; read-only. */
    fun skillsList(project: String?): MobileSkillsList = MobileSkillsList.fromJson(
        get(MobileSkillsQuery.ROUTE + (project?.let { "?project=" + URLEncoder.encode(it, "UTF-8") } ?: "")),
    )

    /** `GET /v1/memory` — the projects open on the machine whose memory files can be listed. */
    fun memoryProjects(): MobileMemoryProjects = MobileMemoryProjects.fromJson(get(MobileMemoryQuery.ROUTE))

    /** `GET /v1/memory?project=` — the desk Memory tab's files for one open project, and whether this phone may save. */
    fun memoryEntries(project: String): MobileMemoryEntries =
        MobileMemoryEntries.fromJson(get(MobileMemoryQuery.ROUTE + "?project=" + URLEncoder.encode(project, "UTF-8")))

    /** `GET /v1/memory?project=&id=` — one file's text and the revision a save must quote. */
    fun memoryFile(project: String, id: String): MobileMemoryFile = MobileMemoryFile.fromJson(
        get(MobileMemoryQuery.ROUTE + "?project=" + URLEncoder.encode(project, "UTF-8") + "&id=" + URLEncoder.encode(id, "UTF-8")),
    )

    /** `POST /v1/memory` — saves the whole text against [MobileMemorySave.revision]; a changed file is a conflict answer, not an error. */
    fun saveMemory(request: MobileMemorySave): MobileMemorySaved =
        MobileMemorySaved.fromJson(post(MobileMemoryQuery.ROUTE, request.toJson()))

    /** `POST /v1/memory-delete` — removes one agent-memory note against the revision read; a note that changed since is a conflict answer, not an error. */
    fun deleteMemory(request: MobileMemoryDelete): MobileMemorySaved =
        MobileMemorySaved.fromJson(post(MobileMemoryQuery.DELETE_ROUTE, request.toJson()))

    /** `GET /v1/files` — the `@` popup's completions. Names only; the machine reads no file. */
    fun files(
        project: String,
        query: String,
        limit: Int = MobileFileQuery.MAX_LIMIT,
        key: String? = null,
        vendor: AgentVendor? = null,
    ): MobileFileList =
        MobileFileList.fromJson(
            get(
                MobileFileQuery.ROUTE + "?project=" + URLEncoder.encode(project, "UTF-8") +
                    "&q=" + URLEncoder.encode(query, "UTF-8") + "&limit=" + limit +
                    key.orEmpty().let { if (it.isEmpty()) "" else "&key=" + URLEncoder.encode(it, "UTF-8") } +
                    vendor?.let { "&vendor=" + it.name }.orEmpty(),
            ),
        )

    /** `GET /v1/commands` — the `/` and `$` rows a send from this conversation can run. */
    fun commands(project: String, vendor: AgentVendor, key: String): MobileCommandList =
        MobileCommandList.fromJson(
            get(
                MobileCommandQuery.ROUTE + "?project=" + URLEncoder.encode(project, "UTF-8") +
                    "&vendor=" + vendor.name + "&key=" + URLEncoder.encode(key, "UTF-8"),
            ),
        )

    /** `GET /v1/issues` — the `#` popup's issues and pull requests from the machine's trackers. */
    fun issues(project: String, query: String): MobileIssueList =
        MobileIssueList.fromJson(
            get(
                MobileIssueQuery.ROUTE + "?project=" + URLEncoder.encode(project, "UTF-8") +
                    "&q=" + URLEncoder.encode(query, "UTF-8"),
            ),
        )

    /** `GET /v1/prompts` — earlier prompts for "Earlier prompts", newest first. */
    fun prompts(project: String, vendor: AgentVendor, key: String): MobilePromptHistory =
        MobilePromptHistory.fromJson(
            get(
                MobilePromptQuery.ROUTE + "?project=" + URLEncoder.encode(project, "UTF-8") +
                    "&vendor=" + vendor.name + "&key=" + URLEncoder.encode(key, "UTF-8"),
            ),
        )

    /**
     * `POST /v1/attach` — one re-encoded JPEG, one UTF-8 text file, one PDF, one ZIP archive, one gzip file, one tar archive, one xz file, one bzip2 file, one Zstandard file, one 7z archive or one RAR archive ([contentType]), each at its own cap.
     *
     * Deliberately *not* [post]: that one refuses at [MobileProtocol.MAX_BODY_BYTES] before the
     * round trip, which is the guard that keeps this a prompt wire, and a photo is the single
     * documented exception to it ([MobileAttachment]). The uncertain-delivery fork is the same
     * as a send's for the same reason — the stream was acquired or it was not — but an upload
     * that may have landed is safe to repeat: an orphaned staged file expires in an hour,
     * where a repeated *prompt* would run twice.
     */
    fun attach(bytes: ByteArray, contentType: String = MobileAttachment.CONTENT_TYPE, name: String? = null): MobileAttachmentAccepted = walkHosts { host ->
        val cap = when {
            MobileAttachment.isTextContentType(contentType) -> MobileAttachment.MAX_TEXT_BYTES
            MobileAttachment.isPdfContentType(contentType) -> MobileAttachment.MAX_PDF_BYTES
            MobileAttachment.isZipContentType(contentType) -> MobileAttachment.MAX_ZIP_BYTES
            MobileAttachment.isGzipContentType(contentType) -> MobileAttachment.MAX_GZIP_BYTES
            MobileAttachment.isTarContentType(contentType) -> MobileAttachment.MAX_TAR_BYTES
            MobileAttachment.isXzContentType(contentType) -> MobileAttachment.MAX_XZ_BYTES
            MobileAttachment.isBzip2ContentType(contentType) -> MobileAttachment.MAX_BZIP2_BYTES
            MobileAttachment.isZstdContentType(contentType) -> MobileAttachment.MAX_ZSTD_BYTES
            MobileAttachment.is7zContentType(contentType) -> MobileAttachment.MAX_7Z_BYTES
            MobileAttachment.isRarContentType(contentType) -> MobileAttachment.MAX_RAR_BYTES
            else -> MobileAttachment.MAX_ATTACH_BYTES
        }
        if (bytes.size > cap) {
            throw BridgeRefusal(
                MobileRefusal.ATTACHMENT_TOO_LARGE.status,
                MobileRefusal.ATTACHMENT_TOO_LARGE.code,
                MobileRefusal.ATTACHMENT_TOO_LARGE.message,
            )
        }
        val connection = open(host, MobileAttachment.ROUTE, authorized = true)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            // Percent-encoded because a header is ASCII; a machine that predates the header ignores it.
            name?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty(MobileAttachment.NAME_HEADER, java.net.URLEncoder.encode(it.take(MobileAttachment.MAX_NAME * 2), "UTF-8"))
            }
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            MobileAttachmentAccepted.fromJson(readJson(connection))
                ?: throw IOException("This machine answered without an attachment id.")
        } finally {
            release(connection)
        }
    }

    /** `POST /v1/phone-log` — this app's own log, for the machine's owner (or an agent) to read. */
    fun sendPhoneLog(text: String): MobilePhoneLogAccepted =
        MobilePhoneLogAccepted.fromJson(post(MobilePhoneLog.ROUTE, MobilePhoneLogRequest(text).toJson()))

    fun send(request: MobileSendRequest): MobileSendAccepted =
        MobileSendAccepted.fromJson(post("/v1/send", request.toJson()))

    fun stop(request: MobileStopRequest) {
        post("/v1/stop", request.toJson())
    }

    /**
     * Answers a parked `AskUserQuestion`. The reply says which route the machine took —
     * [MobileAnswerAccepted.parked] false means no ask was parked any more and the pick was
     * sent as an ordinary prompt, which starts a new turn rather than resuming the blocked one.
     */
    fun answer(request: MobileAnswerRequest): MobileAnswerAccepted =
        MobileAnswerAccepted.fromJson(post("/v1/answer", request.toJson()))

    /**
     * Allows or denies the tool call the run is parked on. A refusal — `permission-gone` when the
     * desk answered first or the agent moved on, `permissions-disabled` when the owner switched it
     * off — is the whole answer: a decision is never retried automatically.
     */
    fun decide(request: MobileDecisionRequest) {
        post("/v1/decision", request.toJson())
    }

    /**
     * Tells the machine to forget this device. The bridge takes the device id from the bearer
     * token, so this can only ever revoke the caller — there is nothing to send but the
     * envelope.
     */
    fun unpair() {
        post("/v1/unpair", JsonObject().apply { addProperty("v", MobileProtocol.VERSION) })
    }

    /**
     * Tells the machine where to reach this phone while the app is not running.
     *
     * The keys travel *to the machine only*. Nothing here goes to the distributor, which is
     * what makes the relay in the middle a forwarder of bytes it cannot read.
     */
    fun registerPush(subscription: MobilePush.Subscription) {
        post(
            MobilePush.REGISTER_PATH,
            JsonObject().apply {
                addProperty("v", MobileProtocol.VERSION)
                addProperty("endpoint", subscription.endpoint)
                addProperty("p256dh", subscription.publicKey)
                addProperty("auth", subscription.authSecret)
            },
        )
    }

    /** Asks the machine to forget this phone's endpoint, keeping the pairing. */
    fun unregisterPush() {
        post(MobilePush.UNREGISTER_PATH, JsonObject().apply { addProperty("v", MobileProtocol.VERSION) })
    }

    fun scheduleEdit(id: String): com.github.claudeagents.core.mobile.MobileScheduleEditDetail =
        com.github.claudeagents.core.mobile.MobileScheduleEditDetail.fromJson(get("/v1/scheduled/${java.net.URLEncoder.encode(id, "UTF-8")}"))

    fun saveScheduleEdit(id: String, request: com.github.claudeagents.core.mobile.MobileScheduleEditRequest) {
        post("/v1/scheduled/${java.net.URLEncoder.encode(id, "UTF-8")}", request.toJson())
    }

    fun scheduleSources(project: String): com.github.claudeagents.core.mobile.MobileScheduleSources =
        com.github.claudeagents.core.mobile.MobileScheduleSources.fromJson(
            get("${com.github.claudeagents.core.mobile.MobileScheduleSources.ROUTE}?project=${java.net.URLEncoder.encode(project, "UTF-8")}"),
        )

    fun scheduled(): MobileScheduledList = MobileScheduledList.fromJson(get("/v1/scheduled"))

    fun scheduledCommand(command: MobileScheduledCommand) {
        post("/v1/scheduled", command.toJson())
    }

    /**
     * Server-Sent Events. Blocks until [onFrame] returns false, the stream ends, or the
     * socket dies; the caller runs it on its own coroutine and cancels by closing.
     *
     * **There is no resume.** The client used to send `Last-Event-ID` and three comments — here,
     * on `MobileProtocol` and on `MobileBridgeService` — described the plugin replaying from a
     * per-conversation ring buffer. No handler ever read the header; the replay did not exist
     * (MP-06). What hid it is that the server sends a fresh `fleet` frame on connect, so the
     * *list* self-heals and only an open transcript stayed stale. The header is gone rather
     * than implemented: the fleet frame already re-syncs everything else, and a ring buffer
     * would be a second source of truth about what moved. [onAlive] is what replaces it — the
     * caller reloads whatever conversation is open when a reconnect proves live.
     *
     * [onAlive] fires on every line the socket delivers, keep-alive comments included, so a
     * caller can tell "idle but connected" from "frozen". Frames alone cannot: an idle machine
     * emits none for minutes at a time.
     */
    fun stream(onFrame: (SseFrame) -> Boolean) = stream({}, onFrame)

    override fun stream(onAlive: () -> Unit, onFrame: (SseFrame) -> Boolean) {
        var connected = false
        walkHosts(retry = { !connected }) { host ->
            val connection = open(host, "/v1/fleet/stream", authorized = true)
            connection.setRequestProperty("Accept", "text/event-stream")
            // A stream *does* have a deadline — three missed keep-alives. It used to be 0,
            // meaning none: a half-open socket then blocked in readLine() forever, so nothing
            // ever threw, nothing reconnected, and the app kept saying "live" over a snapshot
            // that had stopped moving (MP-02). The machine's own heartbeat only observes the
            // other direction.
            connection.readTimeout = LinkPolicy.STREAM_READ_TIMEOUT_MS
            try {
                val status = connection.responseCode
                if (status !in 200..299) throw refusalFrom(connection, status)
                lastGoodHost = host
                connected = true
                BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    SseReader.read(reader, onAlive, onFrame)
                }
            } finally {
                release(connection)
            }
        }
    }

    // ---- transport -------------------------------------------------------------------

    private fun get(
        path: String,
        authorized: Boolean = true,
        readTimeoutMs: Int = LinkPolicy.REQUEST_READ_TIMEOUT_MS,
        retry: (Exception) -> Boolean = { true },
    ): JsonObject = walkHosts(retry) { host ->
        val connection = open(host, path, authorized, readTimeoutMs)
        try {
            readJson(connection)
        } finally {
            release(connection)
        }
    }

    private fun post(
        path: String,
        body: JsonObject,
        authorized: Boolean = true,
        readTimeoutMs: Int = LinkPolicy.REQUEST_READ_TIMEOUT_MS,
    ): JsonObject =
        walkHosts { host ->
            val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
            // The bridge refuses anything larger; failing here names the size instead of
            // spending the round trip to be told.
            if (payload.size > MobileProtocol.MAX_BODY_BYTES) {
                throw BridgeRefusal(413, MobileRefusal.BODY_TOO_LARGE.code, MobileRefusal.BODY_TOO_LARGE.message)
            }
            val connection = open(host, path, authorized, readTimeoutMs)
            var mayHaveReachedMachine = false
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(payload.size)
                val output = connection.outputStream
                mayHaveReachedMachine = true
                output.use { it.write(payload) }
                readJson(connection)
            } catch (refusal: BridgeRefusal) {
                throw refusal
            } catch (error: IOException) {
                if (mayHaveReachedMachine) throw BridgeDeliveryUncertain(error)
                throw error
            } finally {
                release(connection)
            }
        }

    private val connectionLock = Any()
    private val connections = mutableSetOf<HttpsURLConnection>()
    @Volatile private var closed = false

    override fun close() {
        val pending = synchronized(connectionLock) {
            closed = true
            connections.toList().also { connections.clear() }
        }
        pending.forEach { runCatching { it.disconnect() } }
    }

    private fun release(connection: HttpsURLConnection) {
        synchronized(connectionLock) { connections.remove(connection) }
        connection.disconnect()
    }

    private fun checkOpen() {
        if (closed) throw CancellationException("Connection replaced")
    }

    private fun open(
        host: String,
        path: String,
        authorized: Boolean,
        readTimeoutMs: Int = LinkPolicy.REQUEST_READ_TIMEOUT_MS,
    ): HttpsURLConnection {
        checkOpen()
        val url = URL("https", host, port, path)
        val connection = openConnection(url)
        connection.sslSocketFactory = Pinning.socketFactory(spkiFingerprint)
        connection.hostnameVerifier = Pinning.hostnameVerifier
        connection.connectTimeout = LinkPolicy.CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        if (authorized) {
            val bearer = token ?: throw BridgeRefusal(
                MobileRefusal.UNAUTHORIZED.status,
                MobileRefusal.UNAUTHORIZED.code,
                MobileRefusal.UNAUTHORIZED.message,
            )
            connection.setRequestProperty("Authorization", "Bearer $bearer")
        }
        synchronized(connectionLock) {
            checkOpen()
            connections += connection
        }
        return connection
    }

    private fun readJson(connection: HttpsURLConnection): JsonObject {
        val status = connection.responseCode
        if (status !in 200..299) throw refusalFrom(connection, status)
        val text = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return MobileProtocol.parseObject(text)
            ?: throw IOException("This machine answered with something Agent Deck could not read.")
    }

    /**
     * A refusal is any non-2xx carrying `{v, error, message}`. When the body is not that
     * shape it is not a refusal the plugin authored, so the app reports the status rather
     * than inventing a sentence and attributing it to the machine.
     */
    private fun refusalFrom(connection: HttpsURLConnection, status: Int): BridgeRefusal {
        val text = runCatching {
            connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
        val body = text?.let(MobileProtocol::parseObject)
        val code = body?.get("error")?.takeIf { it.isJsonPrimitive }?.asString
        val message = body?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        return BridgeRefusal(
            status = status,
            code = code,
            message = message ?: "This machine answered HTTP $status.",
        )
    }

    /**
     * Tries each address in turn. A pin mismatch stops the walk immediately — it means this
     * machine is answering with the wrong key, and trying its other address would only turn
     * a security refusal into a connection error.
     */
    private fun <T> walkHosts(retry: (Exception) -> Boolean = { true }, attempt: (String) -> T): T {
        var last: Exception? = null
        for (host in (listOfNotNull(lastGoodHost) + hosts).distinct()) {
            checkOpen()
            try {
                val result = attempt(host)
                lastGoodHost = host
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (uncertain: BridgeDeliveryUncertain) {
                throw uncertain
            } catch (refusal: BridgeRefusal) {
                // The machine answered, so it is reachable; another address would answer the
                // same way. Surface the plugin's sentence now.
                lastGoodHost = host
                throw refusal
            } catch (error: Exception) {
                checkOpen()
                Pinning.pinFailure(error)?.let { throw it }
                if (!retry(error)) throw error
                last = error
            }
        }
        throw last ?: IOException("No address was configured for this machine.")
    }

    data class PairAccepted(val token: String, val deviceId: String, val machineName: String)

    data class SseFrame(val id: String?, val event: String?, val data: String)
}

/**
 * The SSE line grammar, lifted out of the socket so it can be asked questions.
 *
 * It is here rather than inline in [BridgeClient.stream] for one reason: the interesting
 * behaviour is what happens on the lines that are *not* frames. A `: keep-alive` comment is the
 * only thing an idle machine sends for minutes at a time, and whether it counts as proof of
 * life is the difference between a supervised connection and a hung one (MP-02).
 */
internal object SseReader {

    /** Reads until the stream ends or [onFrame] returns false. Blocking, by design. */
    fun read(reader: BufferedReader, onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
        var id: String? = null
        var event: String? = null
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            // Before the dispatch, and for every line including comments: this is the signal
            // that the socket is alive, and frames alone would not carry it.
            onAlive()
            when {
                line.isEmpty() -> {
                    if (event != null || data.isNotEmpty()) {
                        if (!onFrame(BridgeClient.SseFrame(id, event, data.toString()))) return
                    }
                    event = null
                    data.setLength(0)
                }
                line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                // ":" comments and unknown fields carry no frame — but they did carry a line,
                // which is the whole point.
            }
        }
    }
}
