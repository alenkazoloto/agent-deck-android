package dev.agentdeck.companion

import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.*
import com.google.gson.JsonObject
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.fixture.DeckFixtures
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Loopback HTTPS exercises the shipped pinning, transport, serialization and ViewModel route. */
internal class StoryBridge : AutoCloseable {
    private val fixture = requireNotNull(DeckFixtures.byName("convo-idle"))
    private val fleetFixture = requireNotNull(DeckFixtures.byName("settings"))
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
    @Volatile var refuseSend = false
    @Volatile var unavailable = false
    @Volatile var page = requireNotNull(fixture.transcript).copy(
        key = "story-chat", title = "Review navigation", running = false, hasMore = false,
        turns = listOf(MobileTurn("opening", "assistant", "Navigation review is ready.", System.currentTimeMillis())),
    )
    private val row = requireNotNull(fleetFixture.snapshot).rows.first().copy(
        key = page.key, title = page.title, projectPath = "/work/project", projectName = "project",
        attention = SessionAttentionState.DONE_UNREVIEWED, liveLine = null,
    )
    @Volatile var rows = listOf(row, row.copy(key = "second-chat", title = "Build account settings"))
    @Volatile var projects = listOf("/work/project")
    @Volatile var scheduled = emptyList<MobileScheduledRow>()
    val snapshot get() = requireNotNull(fleetFixture.snapshot).copy(rows = rows, badgeCount = 0,
        openProjects = projects, generatedAtMs = System.currentTimeMillis())
    val machine: PairedMachine

    init {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val transcriptKey = request.requestUrl!!.takeIf { it.encodedPath.startsWith("/v1/session/") }?.pathSegments?.last()
                if (transcriptKey != null) transcriptReads += transcriptKey
                if (unavailable) return json(JsonObject().apply { addProperty("message", "Machine unavailable") }, 503)
                val path = request.requestUrl!!.encodedPath
                if (request.method == "POST") {
                    val body = MobileProtocol.parseObject(request.body.readUtf8()) ?: JsonObject()
                    commands += path to body
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
                            val prompt = body["prompt"].asString
                            if (body.has("dueAtMs")) {
                                scheduled = scheduled + MobileScheduledRow("queued-story", prompt, "/work/project", null,
                                    body["dueAtMs"].asLong, MobileScheduledRow.QUEUED, false)
                            } else if (body["newChat"]?.asBoolean == true) {
                                rows = rows + row.copy(key = "created-chat", title = prompt)
                            } else {
                                page = page.copy(turns = page.turns + MobileTurn("reply-${commands.size}", "user", prompt,
                                    System.currentTimeMillis()))
                            }
                            json(MobileSendAccepted("story-task", MobileSendAccepted.QUEUED).toJson())
                        }
                        "/v1/answer" -> {
                            page = page.copy(turns = page.turns.map { turn -> turn.copy(toolCalls = turn.toolCalls.map { it.copy(status = MobileToolCall.OK) }) })
                            json(MobileAnswerAccepted(parked = true).toJson())
                        }
                        "/v1/unpair" -> json(JsonObject())
                        "/v1/stop" -> { page = page.copy(running = false, liveLine = null); json(JsonObject()) }
                        "/v1/scheduled" -> {
                            val ids = body["ids"].asJsonArray.map { it.asString }
                            scheduled = when (body["action"].asString) {
                                MobileScheduledCommand.CANCEL -> scheduled.filterNot { it.id in ids }
                                MobileScheduledCommand.PAUSE -> scheduled.map { if (it.id in ids) it.copy(state = MobileScheduledRow.PAUSED) else it }
                                MobileScheduledCommand.RESUME -> scheduled.map { if (it.id in ids) it.copy(state = MobileScheduledRow.QUEUED) else it }
                                else -> scheduled
                            }
                            json(JsonObject())
                        }
                        else -> json(JsonObject(), 404)
                    }
                }
                return when {
                    path == "/v1/hello" -> json(requireNotNull(fleetFixture.hello).let { it.copy(capabilities = it.capabilities + MobileProtocol.Capability.ANSWER + if (paging) setOf(MobileProtocol.Capability.TRANSCRIPT_PAGING) else emptySet()) }.toJson())
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
                    path == "/v1/scheduled" -> json(MobileScheduledList(scheduled).toJson())
                    path == "/v1/fleet/stream" -> MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody(": ready\n\n".repeat(10000)).throttleBody(9, 1, TimeUnit.SECONDS)
                    else -> json(JsonObject(), 404)
                }
            }
        }
        server.start()
        machine = requireNotNull(fixture.machine).copy(machineName = "Story workstation", hosts = listOf("127.0.0.1"),
            preferredHost = "127.0.0.1", port = server.port, token = "story-token", deviceId = "story-device",
            spkiFingerprint = MobilePairing.fingerprint(certificate.certificate.publicKey.encoded))
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
