package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRunSelection
import com.github.claudeagents.core.mobile.MobileRunSelection.Field
import com.github.claudeagents.core.mobile.MobileSendRequest
import dev.agentdeck.companion.data.ComposerPicks
import dev.agentdeck.companion.data.OutgoingQueue
import dev.agentdeck.companion.data.OutgoingSend
import dev.agentdeck.companion.fixture.DeckFixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * t3code #5278: a follow-up carried the model, effort and mode of the page the phone last
 * loaded, so a chat switched at the desk since ran on the old ones.
 *
 * A machine that advertises `send-desk-selection` is now sent only what the reader picked, plus
 * the names of the fields left to the desk; an older one still gets the page's copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeskSelectionSendTest {

    private val pills = DeckFixtures.byName("convo-composer-pills")!!
    private val key = "convo-1"
    private val page = pills.transcript!!.selection!!

    private fun withDesk(capable: Boolean, picks: ComposerPicks? = null): DeckState {
        val hello = pills.hello!!
        val caps = hello.capabilities.filterNot { it == MobileProtocol.Capability.SEND_DESK_SELECTION } +
            listOfNotNull(MobileProtocol.Capability.SEND_DESK_SELECTION.takeIf { capable })
        return pills.copy(
            hello = hello.copy(capabilities = caps),
            composerPicks = picks?.let { mapOf(key to it) }.orEmpty(),
        )
    }

    @Test fun `an untouched composer names no field and leaves all three to the desk`() {
        val (named, desk) = withDesk(capable = true).followUpSelection(key)

        assertEquals("a value from the page's copy reached the wire", MobileRunSelection(null, null, null), named)
        assertEquals(setOf(Field.MODEL, Field.EFFORT, Field.MODE), desk)
    }

    @Test fun `only what was picked is named, and a picked Default stays a pick`() {
        val picks = ComposerPicks().with(ComposerPicks.Field.MODEL, "sonnet").with(ComposerPicks.Field.EFFORT, null)

        val (named, desk) = withDesk(capable = true, picks).followUpSelection(key)

        assertEquals(MobileRunSelection("sonnet", null, null), named)
        assertEquals("a picked Default was handed back to the desk", setOf(Field.MODE), desk)
    }

    @Test fun `an older machine still gets the page's selection and no field to fill`() {
        val (named, desk) = withDesk(capable = false).followUpSelection(key)

        assertEquals(page, named)
        assertEquals(emptySet<Field>(), desk)
    }

    @Test fun `a queued send keeps its desk fields through the file and onto the request`() {
        val queued = OutgoingSend(
            clientMessageId = "a", key = "claude:/repo:session", projectPath = "/repo",
            vendor = AgentVendor.CLAUDE, label = "Fix the parser", prompt = "continue",
            effort = "max", deskFields = setOf(Field.MODEL, Field.MODE),
        )

        val restored = OutgoingQueue.fromJson(MobileProtocol.parseObject(OutgoingQueue.toJson(OutgoingQueue(listOf(queued))).toString()))

        assertEquals(queued, restored.items.single())
        val request = MobileSendRequest.fromJson(restored.items.single().request().toJson())
        assertEquals(setOf(Field.MODEL, Field.MODE), request.deskFields)
        assertEquals("max", request.effort)
    }
}
