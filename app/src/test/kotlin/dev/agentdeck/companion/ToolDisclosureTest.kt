package dev.agentdeck.companion

import dev.agentdeck.companion.ui.ToolDisclosure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tool-call group opens on a tap and on nothing else.
 *
 * The state this pins is the one a screenshot of a *finished* conversation cannot see: the group
 * defaulted to open whenever the page was running, and `running` describes the conversation, not
 * the turn — so every turn's calls unrolled at once, and only while an agent happened to be busy.
 */
class ToolDisclosureTest {

    @Test
    fun `a live run does not open a group the reader has not opened`() {
        assertFalse(ToolDisclosure.expanded(readerOpened = false, runLive = true))
    }

    @Test
    fun `an idle conversation does not open one either`() {
        assertFalse(ToolDisclosure.expanded(readerOpened = false, runLive = false))
    }

    @Test
    fun `the reader's tap opens it, whatever the run is doing`() {
        // Both run states, because the pre-fix default answered to the run: a group the reader
        // opened must not close itself when the run it belongs to ends, and vice versa.
        assertTrue(ToolDisclosure.expanded(readerOpened = true, runLive = false))
        assertTrue(ToolDisclosure.expanded(readerOpened = true, runLive = true))
    }
}
