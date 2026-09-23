package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileAnswerAccepted
import com.github.claudeagents.core.mobile.MobileAnswerRequest
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
import com.github.claudeagents.core.mobile.MobilePromptHistory
import com.github.claudeagents.core.mobile.MobilePromptQuery
import com.github.claudeagents.core.mobile.MobilePush
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledList
import com.github.claudeagents.core.mobile.MobileReviewCommitPreview
import com.github.claudeagents.core.mobile.MobileReviewCommitRequest
import com.github.claudeagents.core.mobile.MobileReviewCommitResult
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileReviewMark
import com.github.claudeagents.core.mobile.MobileFolderActionRequest
import com.github.claudeagents.core.mobile.MobileFolderActionResult
import com.github.claudeagents.core.mobile.MobileSessionActionRequest
import com.github.claudeagents.core.mobile.MobileSessionActionResult
import com.github.claudeagents.core.mobile.MobileSessionSearchRequest
import com.github.claudeagents.core.mobile.MobileSessionExport
import com.github.claudeagents.core.mobile.MobileSessionDeletePreview
import com.github.claudeagents.core.mobile.MobileSessionDeleteRequest
import com.github.claudeagents.core.mobile.MobileSessionDeleteResult
import com.github.claudeagents.core.mobile.MobileSessionForkPoints
import com.github.claudeagents.core.mobile.MobileSessionForkRequest
import com.github.claudeagents.core.mobile.MobileSessionForkResult
import com.github.claudeagents.core.mobile.MobileSessionSearchResult
import com.github.claudeagents.core.mobile.MobileSendAccepted
import com.github.claudeagents.core.mobile.MobileSendRequest
import com.github.claudeagents.core.mobile.MobileStopRequest
import com.github.claudeagents.core.mobile.MobileToolResult
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
    fun review(key: String): MobileReviewList =
        MobileReviewList.fromJson(get("/v1/review/" + URLEncoder.encode(key, "UTF-8")))

    /** `GET /v1/review/{key}/file?path=` — one file's hunks, already cut by the machine. */
    fun reviewFile(key: String, path: String): MobileReviewFileDiff = MobileReviewFileDiff.fromJson(
        get(
            "/v1/review/" + URLEncoder.encode(key, "UTF-8") + "/file?path=" +
                URLEncoder.encode(path, "UTF-8"),
        ),
    )

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

    /** `POST /v1/session-fork` naming a point (Claude) or the whole thread (Codex) — the desk's own fork. */
    fun fork(request: MobileSessionForkRequest): MobileSessionForkResult =
        MobileSessionForkResult.fromJson(post(MobileSessionForkRequest.ROUTE, request.toJson()))

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

    fun usage(): MobileUsageReport = MobileUsageReport.fromJson(get(MobileUsageReport.ROUTE))

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
     * `POST /v1/attach` — one re-encoded JPEG, at its own cap.
     *
     * Deliberately *not* [post]: that one refuses at [MobileProtocol.MAX_BODY_BYTES] before the
     * round trip, which is the guard that keeps this a prompt wire, and a photo is the single
     * documented exception to it ([MobileAttachment]). The uncertain-delivery fork is the same
     * as a send's for the same reason — the stream was acquired or it was not — but an upload
     * that may have landed is safe to repeat: an orphaned staged file expires in an hour,
     * where a repeated *prompt* would run twice.
     */
    fun attach(jpeg: ByteArray): MobileAttachmentAccepted = walkHosts { host ->
        if (jpeg.size > MobileAttachment.MAX_ATTACH_BYTES) {
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
            connection.setRequestProperty("Content-Type", MobileAttachment.CONTENT_TYPE)
            connection.setFixedLengthStreamingMode(jpeg.size)
            connection.outputStream.use { it.write(jpeg) }
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
