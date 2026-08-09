package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
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
     * Tells the machine to forget this device. The bridge takes the device id from the bearer
     * token, so this can only ever revoke the caller — there is nothing to send but the
     * envelope.
     */
    fun unpair() {
        post("/v1/unpair", JsonObject().apply { addProperty("v", MobileProtocol.VERSION) })
    }

    fun scheduled(): MobileScheduledList = MobileScheduledList.fromJson(get("/v1/scheduled"))

    fun scheduledCommand(command: MobileScheduledCommand) {
        post("/v1/scheduled", command.toJson())
    }

    /**
     * Server-Sent Events. Blocks until [onFrame] returns false, the stream ends, or the
     * socket dies; the caller runs it on its own coroutine and cancels by closing.
     *
     * [lastEventId] resumes where a dropped connection stopped — the plugin replays from its
     * per-conversation ring buffer — so a reconnect is not automatically a full refetch.
     */
    fun stream(lastEventId: String?, onFrame: (SseFrame) -> Boolean) {
        walkHosts { host ->
            val connection = open(host, "/v1/fleet/stream", authorized = true)
            connection.setRequestProperty("Accept", "text/event-stream")
            lastEventId?.let { connection.setRequestProperty("Last-Event-ID", it) }
            // A stream has no response deadline; the connect timeout still bounds the dial.
            connection.readTimeout = 0
            try {
                val status = connection.responseCode
                if (status !in 200..299) throw refusalFrom(connection, status)
                BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var id: String? = null
                    var event: String? = null
                    val data = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.isEmpty() -> {
                                if (event != null || data.isNotEmpty()) {
                                    val keepGoing = onFrame(SseFrame(id, event, data.toString()))
                                    if (!keepGoing) return@walkHosts Unit
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
                            // ":" heartbeat comments and unknown fields are ignored by spec.
                        }
                    }
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
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
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

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
