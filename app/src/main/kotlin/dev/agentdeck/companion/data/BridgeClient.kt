package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileAnswerAccepted
import com.github.claudeagents.core.mobile.MobileAnswerRequest
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobilePush
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledList
import com.github.claudeagents.core.mobile.MobileSendAccepted
import com.github.claudeagents.core.mobile.MobileSendRequest
import com.github.claudeagents.core.mobile.MobileStopRequest
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
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
class BridgeClient(
    private val hosts: List<String>,
    private val port: Int,
    private val spkiFingerprint: String,
    private val token: String?,
) {

    constructor(machine: PairedMachine) : this(
        machine.dialOrder(),
        machine.port,
        machine.spkiFingerprint,
        machine.token,
    )

    /** The host that answered last, so the UI can persist it and skip the walk next time. */
    @Volatile
    var lastGoodHost: String? = null
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

    fun fleet(): MobileFleetSnapshot = MobileFleetSnapshot.fromJson(get("/v1/fleet"))

    fun transcript(key: String): MobileTranscriptPage =
        MobileTranscriptPage.fromJson(get("/v1/session/" + URLEncoder.encode(key, "UTF-8")))

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
    fun stream(onAlive: () -> Unit = {}, onFrame: (SseFrame) -> Boolean) {
        walkHosts { host ->
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
                BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    SseReader.read(reader, onAlive, onFrame)
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    // ---- transport -------------------------------------------------------------------

    private fun get(path: String, authorized: Boolean = true): JsonObject = walkHosts { host ->
        val connection = open(host, path, authorized)
        try {
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun post(path: String, body: JsonObject, authorized: Boolean = true): JsonObject =
        walkHosts { host ->
            val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
            // The bridge refuses anything larger; failing here names the size instead of
            // spending the round trip to be told.
            if (payload.size > MobileProtocol.MAX_BODY_BYTES) {
                throw BridgeRefusal(413, MobileRefusal.BODY_TOO_LARGE.code, MobileRefusal.BODY_TOO_LARGE.message)
            }
            val connection = open(host, path, authorized)
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
                readJson(connection)
            } finally {
                connection.disconnect()
            }
        }

    private fun open(host: String, path: String, authorized: Boolean): HttpsURLConnection {
        val url = URL("https", host, port, path)
        val connection = url.openConnection() as HttpsURLConnection
        connection.sslSocketFactory = Pinning.socketFactory(spkiFingerprint)
        connection.hostnameVerifier = Pinning.hostnameVerifier
        connection.connectTimeout = LinkPolicy.CONNECT_TIMEOUT_MS
        connection.readTimeout = LinkPolicy.REQUEST_READ_TIMEOUT_MS
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
    private fun <T> walkHosts(attempt: (String) -> T): T {
        var last: Exception? = null
        for (host in hosts) {
            try {
                val result = attempt(host)
                lastGoodHost = host
                return result
            } catch (refusal: BridgeRefusal) {
                // The machine answered, so it is reachable; another address would answer the
                // same way. Surface the plugin's sentence now.
                lastGoodHost = host
                throw refusal
            } catch (error: Exception) {
                Pinning.pinFailure(error)?.let { throw it }
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
