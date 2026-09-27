package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol.Capability
import dev.agentdeck.companion.ui.ScheduleIntoChatOffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which conversations offer "Schedule into this chat": an ACP chat only on a machine that can queue it. */
class ScheduleIntoChatOfferTest {
    private val claude = "claude|/work/repo|9f2c"
    private val acp = "acp:gemini/sess-1"

    @Test
    fun `a Claude chat needs only the schedule capability`() {
        assertTrue(ScheduleIntoChatOffer.offered(listOf(Capability.SCHEDULE_CREATE), claude))
        assertFalse(ScheduleIntoChatOffer.offered(emptyList(), claude))
    }

    @Test
    fun `an ACP chat also needs the machine to queue ACP prompts`() {
        assertFalse("an older plugin would refuse the prompt", ScheduleIntoChatOffer.offered(listOf(Capability.SCHEDULE_CREATE), acp))
        assertFalse(ScheduleIntoChatOffer.offered(listOf(Capability.ACP_SCHEDULE), acp))
        assertTrue(ScheduleIntoChatOffer.offered(listOf(Capability.SCHEDULE_CREATE, Capability.ACP_SCHEDULE), acp))
    }
}
