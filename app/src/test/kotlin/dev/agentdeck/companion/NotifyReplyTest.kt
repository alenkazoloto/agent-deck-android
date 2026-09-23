package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileRunSelection
import dev.agentdeck.companion.notify.NotifyReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A reply typed in the shade has no composer to pick from, so the desk's own selectors decide the run. */
class NotifyReplyTest {

    @Test fun `a shade reply leaves every field to the desk and names no pick`() {
        val request = NotifyReceiver.replyRequest("k", "/p", AgentVendor.CLAUDE, "go on")

        assertNull(request.model)
        assertNull(request.effort)
        assertNull(request.permissionMode)
        assertEquals("a field was left out, so it runs on CLI defaults", MobileRunSelection.Field.entries.toSet(), request.deskFields)
        assertEquals(false, request.newChat)
        assertEquals("go on", request.prompt)
    }
}
