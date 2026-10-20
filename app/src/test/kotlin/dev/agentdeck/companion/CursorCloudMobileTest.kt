package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileCursorCloudSession
import com.github.claudeagents.core.mobile.MobileDeskCommands.Action
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetGrouping
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.data.RowAction
import dev.agentdeck.companion.data.RowActions
import dev.agentdeck.companion.ui.ScheduleIntoChatOffer
import dev.agentdeck.companion.ui.acpAgentVoice
import dev.agentdeck.companion.ui.acpDeskActions
import dev.agentdeck.companion.ui.conversationHello
import dev.agentdeck.companion.ui.offersChanges
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class CursorCloudMobileTest {
    private val session = MobileCursorCloudSession(
        key = "cursor-cloud:bc_phone", title = "Fix the parser", repository = "https://github.com/example/repo",
        running = true, lastActivityMs = 1L,
    )

    @Test fun `cloud rows and conversation identify Cursor rather than their wire vendor`() {
        assertEquals("Cursor cloud", acpAgentVoice(session.key, session.toRow().accountLabel))
        assertEquals("Cursor cloud", acpAgentVoice(session.key, null))
        assertEquals("repo", session.toRow().projectName)
        assertEquals(session.repository, session.toRow().projectPath)
    }

    @Test fun `cloud rows never appear under a Claude vendor filter`() {
        assertTrue(FleetFilter().matches(session.toRow()))
        assertFalse(FleetFilter(vendor = AgentVendor.CLAUDE).matches(session.toRow()))
        assertTrue(FleetGrouping.vendors(listOf(session.toRow())).isEmpty())
    }

    @Test fun `cloud row actions keep open stop and copy even when every native operation is available`() {
        val actions = RowActions.of(
            FleetGroup.RUNNING, row = session.toRow(), canReview = true, canOrganize = true, canShare = true,
            canDelete = true, canFolder = true, canFork = true, canPinOrder = true, paintedPins = 2,
            canRetitle = true, canBranch = true, canRewind = true, canCommitStaged = true, canContext = true,
            canDirs = true, canMcp = true, canCodex = true, canSpend = true, canHandoff = true,
        )
        assertEquals(listOf(RowAction.OPEN, RowAction.STOP, RowAction.COPY_TITLE), actions)
        assertEquals(listOf(RowAction.OPEN, RowAction.COPY_TITLE), RowActions.of(FleetGroup.RECENT, row = session.toRow()))
    }

    @Test fun `cloud conversations offer no native commands attachments run selectors review or schedule`() {
        val nativeCaps = listOf(
            MobileProtocol.Capability.ATTACHMENTS, MobileProtocol.Capability.FILES, MobileProtocol.Capability.ISSUES,
            MobileProtocol.Capability.MODELS, MobileProtocol.Capability.COMMANDS, MobileProtocol.Capability.SCHEDULE_CREATE,
            MobileProtocol.Capability.REVIEW, MobileProtocol.Capability.ACP_REVIEW, MobileProtocol.Capability.TOOL_RESULTS,
        )
        val hello = MobileHello(1, "desk", "IDEA", "1", nativeCaps)
        assertEquals(listOf(MobileProtocol.Capability.TOOL_RESULTS), conversationHello(session.key, hello)!!.capabilities)
        assertSame(hello, conversationHello("native", hello))
        assertTrue(acpDeskActions(session.key, Action.entries.toSet()).isEmpty())
        assertFalse(offersChanges(session.key, nativeCaps))
        assertFalse(ScheduleIntoChatOffer.offered(nativeCaps, session.key))
    }

    @Test fun `watch renew and release carry authenticated chat keys to their own route`() {
        val bodies = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        val bridge = BridgeClient(listOf("desk.test"), 8443, "00", "paired-token") { url ->
            assertEquals(MobileCursorCloudSession.WATCH_ROUTE, url.path)
            object : HttpsURLConnection(url) {
                val body = ByteArrayOutputStream()
                override fun connect() = Unit
                override fun disconnect() { bodies += body.toString(Charsets.UTF_8.name()) }
                override fun usingProxy() = false
                override fun getCipherSuite() = "test"
                override fun getLocalCertificates(): Array<Certificate>? = null
                override fun getServerCertificates(): Array<Certificate> = emptyArray()
                override fun getResponseCode(): Int {
                    assertEquals("POST", requestMethod)
                    authorizations += getRequestProperty("Authorization")
                    return 200
                }
                override fun getInputStream() = "{}".byteInputStream()
                override fun getOutputStream() = body
            }
        }
        bridge.watchCursorCloud(session.key, true)
        bridge.watchCursorCloud(session.key, false)
        assertEquals(listOf("Bearer paired-token", "Bearer paired-token"), authorizations)
        assertEquals(listOf(true, false), bodies.map { MobileProtocol.parseObject(it)!!.get("visible").asBoolean })
        assertTrue(bodies.all { MobileProtocol.parseObject(it)!!.get("key").asString == session.key })
    }
}
