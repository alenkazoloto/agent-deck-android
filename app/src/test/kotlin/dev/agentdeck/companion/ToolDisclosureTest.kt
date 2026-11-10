package dev.agentdeck.companion

import dev.agentdeck.companion.data.AppSettings
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
        assertFalse(ToolDisclosure.expanded(toggled = false, runLive = true))
    }

    @Test
    fun `an idle conversation does not open one either`() {
        assertFalse(ToolDisclosure.expanded(toggled = false, runLive = false))
    }

    @Test
    fun `the reader's tap opens it, whatever the run is doing`() {
        // Both run states, because the pre-fix default answered to the run: a group the reader
        // opened must not close itself when the run it belongs to ends, and vice versa.
        assertTrue(ToolDisclosure.expanded(toggled = true, runLive = false))
        assertTrue(ToolDisclosure.expanded(toggled = true, runLive = true))
    }

    @Test
    fun `the Open tool calls setting starts a group open and a tap folds it`() {
        assertTrue(ToolDisclosure.expanded(toggled = false, runLive = false, openByDefault = true))
        assertFalse(ToolDisclosure.expanded(toggled = true, runLive = false, openByDefault = true))
    }

    @Test
    fun `the setting is off unless the reader turned it on`() {
        assertFalse(AppSettings().openToolCalls)
        assertFalse(AppSettings.fromJson(AppSettings(emojiCompletion = false).toJson().also { it.remove("openToolCalls") }).openToolCalls)
        assertTrue(AppSettings.fromJson(AppSettings(openToolCalls = true).toJson()).openToolCalls)
    }
}
