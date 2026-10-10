package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.github.claudeagents.core.mobile.MobileReleaseNotes
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ReleaseNotesSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's `/release-notes` on the phone: the machine's changelog newest first, the installed build
 * marked and in view, the machine's own sentence when its cache is empty or behind, and a machine that
 * does not answer said so rather than drawn as an empty changelog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReleaseNotesInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private var loads = 0

    private val notes = MobileReleaseNotes(
        installed = "2.1.219",
        notice = null,
        releases = listOf(
            MobileReleaseNotes.Release("2.1.220", listOf("Added a thing", "Fixed a crash")),
            MobileReleaseNotes.Release("2.1.219", listOf("Faster startup")),
            MobileReleaseNotes.Release("2.1.218", listOf("Fixed the thing")),
        ),
    )

    private fun show(answer: suspend () -> MobileReleaseNotes?) {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                ReleaseNotesSheet(onLoad = { loads++; answer() }, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `each release's notes read as bullets and the installed build is marked`() {
        show { notes }

        compose.onNodeWithText("Claude Code release notes").assertIsDisplayed()
        compose.onNodeWithText("2.1.220").assertIsDisplayed()
        compose.onNodeWithText("• Added a thing").assertIsDisplayed()
        compose.onNodeWithText("• Fixed a crash").assertIsDisplayed()
        compose.onNodeWithText("2.1.219 · installed").assertIsDisplayed()
        compose.onNodeWithTag("release-notes-2.1.219").assertIsDisplayed()
        assertEquals(1, loads)
    }

    @Test
    fun `a cache the machine calls behind carries its sentence above the list`() {
        val sentence = "These notes stop at 2.1.200; the installed Claude Code is 2.1.220. Claude Code refreshes them the next time it runs."
        show { notes.copy(notice = sentence, installed = "2.1.220") }

        compose.onNodeWithText(sentence).assertIsDisplayed()
        compose.onNodeWithText("2.1.220 · installed").assertIsDisplayed()
    }

    @Test
    fun `an empty cache is the machine's sentence and no releases`() {
        val sentence = "Claude Code hasn't downloaded its changelog yet. It writes one the next time it runs."
        show { MobileReleaseNotes(installed = null, notice = sentence, releases = emptyList()) }

        compose.onNodeWithText(sentence).assertIsDisplayed()
        compose.onNodeWithTag("release-notes-list").assertIsDisplayed()
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty changelog`() {
        show { null }

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }
}
