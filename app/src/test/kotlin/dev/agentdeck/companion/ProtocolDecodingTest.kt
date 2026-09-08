package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.WaitingReason
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobilePairingPayload
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledList
import com.github.claudeagents.core.mobile.MobileScheduledRow
import com.github.claudeagents.core.mobile.MobileSendAccepted
import com.github.claudeagents.core.mobile.MobileSendRequest
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.BridgeRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures are produced by the shared DTOs' own `toJson()`, then decoded back through
 * their own `fromJson()`. Hand-written JSON here would only prove that this test agrees with
 * itself; encoding through the plugin's encoder means these cases fail the moment the two
 * halves of a field stop matching in the *plugin's* source, which is the drift worth
 * catching.
 */
class ProtocolDecodingTest {

    @Test
    fun `fleet snapshot survives a round trip through the wire`() {
        val snapshot = MobileFleetSnapshot(
            rows = listOf(
                MobileFleetRow(
                    key = "v2:QUJD:ZGVm",
                    vendor = AgentVendor.CODEX,
                    accountId = "work",
                    projectPath = "/Users/x/Plugin",
                    projectName = "Plugin",
                    gitBranch = "main",
                    title = "Fix the pairing dialog",
                    attention = SessionAttentionState.WAITING_ON_YOU,
                    waitingReason = WaitingReason.QUESTION,
                    lastActivityMs = 1_700_000_000_000,
                    costUsd = 1.25,
                    costKnown = true,
                    contextPct = 42,
                    messageCount = 17,
                    liveLine = "Running the tests",
                ),
            ),
            badgeCount = 1,
            openProjects = listOf("/Users/x/Plugin"),
            usageLine = "Session 12% · Week 40%",
            generatedAtMs = 1_700_000_001_000,
        )

        val decoded = MobileFleetSnapshot.fromJson(
            MobileProtocol.parseObject(snapshot.toJson().toString())!!,
        )

        assertEquals(snapshot, decoded)
        assertEquals(AgentVendor.CODEX, decoded.rows.single().vendor)
        assertEquals(SessionAttentionState.WAITING_ON_YOU, decoded.rows.single().attention)
        assertEquals(WaitingReason.QUESTION, decoded.rows.single().waitingReason)
    }

    @Test
    fun `a row with no attention and no branch decodes to nulls rather than defaults`() {
        val row = MobileFleetRow(
            key = "v2:a",
            vendor = AgentVendor.CLAUDE,
            accountId = "default",
            projectPath = "/tmp/p",
            projectName = "p",
            gitBranch = null,
            title = "",
            attention = null,
            waitingReason = null,
            lastActivityMs = 0,
            costUsd = 0.0,
            costKnown = false,
            contextPct = null,
            messageCount = 0,
        )
        val decoded = MobileFleetRow.fromJson(MobileProtocol.parseObject(row.toJson().toString())!!)
        assertNull(decoded.attention)
        assertNull(decoded.waitingReason)
        assertNull(decoded.gitBranch)
        assertNull(decoded.contextPct)
        assertNull(decoded.liveLine)
        assertFalse(decoded.costKnown)
    }

    @Test
    fun `transcript page keeps turns, tool calls and the live line`() {
        val page = MobileTranscriptPage(
            key = "v2:key",
            title = "Fix the pairing dialog",
            turns = listOf(
                MobileTurn("t1", "user", "run the tests", 1_000, emptyList(), streaming = false),
                MobileTurn(
                    id = "t2",
                    role = "assistant",
                    text = "Running them now.\n\n```kotlin\nval x = 1\n```",
                    timestampMs = 2_000,
                    toolCalls = listOf(
                        MobileToolCall("c1", "Bash", "Bash: run the tests", "./gradlew test", MobileToolCall.RUNNING),
                        MobileToolCall("c2", "Read", "Read Main.kt", "42 lines", MobileToolCall.OK, link = "/tmp/Main.kt"),
                    ),
                    streaming = true,
                ),
            ),
            hasMore = true,
            costUsd = 0.5,
            costKnown = true,
            contextPct = 12,
            model = "claude-opus-5",
            liveLine = "Bash: ./gradlew test",
            running = true,
            generatedAtMs = 3_000,
        )

        val decoded = MobileTranscriptPage.fromJson(
            MobileProtocol.parseObject(page.toJson().toString())!!,
        )

        assertEquals(page, decoded)
        assertTrue(decoded.turns[1].streaming)
        assertEquals(MobileToolCall.RUNNING, decoded.turns[1].toolCalls[0].status)
        assertEquals("/tmp/Main.kt", decoded.turns[1].toolCalls[1].link)
    }

    @Test
    fun `send, stop, accept and schedule payloads round trip`() {
        val request = MobileSendRequest(
            key = "v2:key",
            projectPath = "/tmp/p",
            prompt = "continue",
            vendor = AgentVendor.CLAUDE,
            model = null,
            effort = null,
            permissionMode = null,
            newChat = false,
        )
        assertEquals(
            request,
            MobileSendRequest.fromJson(MobileProtocol.parseObject(request.toJson().toString())!!),
        )

        val accepted = MobileSendAccepted("task-9", MobileSendAccepted.QUEUED)
        assertEquals(
            accepted,
            MobileSendAccepted.fromJson(MobileProtocol.parseObject(accepted.toJson().toString())!!),
        )

        val list = MobileScheduledList(
            listOf(
                MobileScheduledRow("s1", "nightly build", "/tmp/p", null, 9_000, MobileScheduledRow.PAUSED, true),
                MobileScheduledRow("s2", "review", null, "sess", 10_000, MobileScheduledRow.QUEUED, false),
            ),
        )
        assertEquals(
            list,
            MobileScheduledList.fromJson(MobileProtocol.parseObject(list.toJson().toString())!!),
        )

        // Cancel-all travels as explicit ids so it cannot cancel a row that arrived after
        // the phone rendered the list.
        val command = MobileScheduledCommand(MobileScheduledCommand.CANCEL, listOf("s1", "s2"))
        assertEquals(
            command,
            MobileScheduledCommand.fromJson(MobileProtocol.parseObject(command.toJson().toString())!!),
        )
    }

    @Test
    fun `hello carries the capability list the app hides surfaces by`() {
        val hello = MobileHello(
            protocolVersion = MobileProtocol.VERSION,
            machineName = "kt-mbp",
            ideName = "IntelliJ IDEA",
            pluginVersion = "1.4.0",
            capabilities = listOf(MobileProtocol.Capability.FLEET, MobileProtocol.Capability.SEND),
        )
        val decoded = MobileHello.fromJson(MobileProtocol.parseObject(hello.toJson().toString())!!)
        assertEquals(hello, decoded)
        assertNull(decoded.servedByOtherIde)
    }

    @Test
    fun `the QR payload decodes exactly what the pairing screen needs`() {
        val payload = MobilePairingPayload(
            hosts = listOf("100.64.0.2", "192.168.1.20"),
            port = 63350,
            spkiFingerprint = "AbC-123_xyz",
            code = "01234567",
            machineName = "kt-mbp",
        )
        val decoded = MobilePairingPayload.decode(payload.encode())
        assertEquals(payload, decoded)
    }

    @Test
    fun `a QR payload from another protocol major is refused rather than half-read`() {
        val foreign = """{"v":99,"hosts":["1.2.3.4"],"port":63350,"spki":"a","code":"1"}"""
        assertNull(MobilePairingPayload.decode(foreign))
        assertNull(MobilePairingPayload.decode("not json at all"))
        // No hosts means nowhere to dial, which is not a pairing at all.
        assertNull(MobilePairingPayload.decode("""{"v":1,"hosts":[],"port":1,"spki":"a","code":"1"}"""))
    }

    @Test
    fun `a refusal is carried as the plugin's own sentence`() {
        val refusal = MobileRefusal.SPEND_LIMIT
        val body = MobileProtocol.parseObject(refusal.toJson().toString())!!
        val code = body.get("error").asString
        val message = body.get("message").asString

        assertEquals("spend-limit", code)
        assertEquals(refusal.message, message)
        // What the app raises must repeat that sentence, never reword it.
        val raised = BridgeRefusal(refusal.status, code, message)
        assertEquals(refusal.message, raised.message)
        assertFalse(raised.isRevoked)

        val revoked = MobileRefusal.DEVICE_REVOKED
        assertTrue(BridgeRefusal(revoked.status, revoked.code, revoked.message).isRevoked)
        assertTrue(BridgeRefusal(401, MobileRefusal.UNAUTHORIZED.code, "x").isRevoked)
    }
}
