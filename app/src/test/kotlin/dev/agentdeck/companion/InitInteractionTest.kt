package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.github.claudeagents.core.mobile.MobileInitSetup
import com.github.claudeagents.core.mobile.MobileInitSetupRequest
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.InitSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's `/init` picker on the phone: what to set up, where, what to do about a file that exists, what to
 * carry across and notes — the desk's words — with Propose handing the picks over as they stand, the picks
 * surviving a refusal, and an existing file's choice offered only for the repository's file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InitInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val submitted = ArrayList<MobileInitSetupRequest>()
    private val drafts = ArrayList<MobileInitSetupRequest?>()
    private var dismissed = 0
    private var refusal: String? = null

    private val setup = MobileInitSetup(
        key = "k",
        instructionsFile = "CLAUDE.md",
        personalPath = "~/.claude/CLAUDE.md",
        hasInstructions = true,
        sources = listOf(
            MobileInitSetup.Source("AGENTS.md", "AGENTS.md · AGENTS.md"),
            MobileInitSetup.Source(".cursor/rules/", "Cursor · .cursor/rules/ (2 files)"),
        ),
    )

    private fun show(answer: MobileInitSetup? = setup, notes: String = "", draft: MobileInitSetupRequest? = null) {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                InitSheet(
                    notes = notes,
                    draft = draft,
                    onDraft = { drafts += it },
                    onLoad = { answer },
                    onSubmit = { submitted += it; refusal },
                    onDismiss = { dismissed++ },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the picker opens on the desk's state and Propose hands those picks over`() {
        show()

        compose.onNodeWithText("Set up this project").assertIsDisplayed()
        compose.onNodeWithText("Review and improve it").assertIsDisplayed()
        compose.onNodeWithTag("init-propose").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileInitSetupRequest("")), submitted)
        assertEquals("a sent prompt closes the sheet", 1, dismissed)
    }

    @Test
    fun `ticking skills, a source and choosing a fresh start reach the machine`() {
        show()

        compose.onNodeWithTag("init-skills").performClick()
        compose.onNodeWithTag("init-import-AGENTS.md").performScrollTo().performClick()
        compose.onNodeWithTag("init-existing-replace").performScrollTo().performClick()
        compose.onNodeWithTag("init-propose").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(MobileInitSetupRequest("", skills = true, replace = true, imports = listOf("AGENTS.md")), submitted.single())
    }

    @Test
    fun `a personal file has no existing-file choice, since it is never the repository's`() {
        show()
        compose.onNodeWithTag("init-existing-improve").assertIsDisplayed()

        compose.onNodeWithTag("init-where-personal").performClick()

        compose.onNodeWithTag("init-existing-improve").assertDoesNotExist()
        compose.onNodeWithText("Just me — ~/.claude/CLAUDE.md").assertIsDisplayed()
    }

    @Test
    fun `a repository with no instructions file offers no existing-file choice`() {
        show(setup.copy(hasInstructions = false))
        compose.onNodeWithTag("init-existing-improve").assertDoesNotExist()
    }

    @Test
    fun `with nothing to set up Propose is off and says so`() {
        show()

        compose.onNodeWithTag("init-instructions").performClick()

        compose.onNodeWithTag("init-propose").assertIsNotEnabled()
        compose.onNodeWithText("Pick at least one thing to set up.").assertIsDisplayed()
    }

    @Test
    fun `words typed after the command seed the notes and win over a parked draft's`() {
        show(notes = "we use Bazel", draft = MobileInitSetupRequest("", skills = true, notes = "old notes"))

        compose.onNodeWithTag("init-propose").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(MobileInitSetupRequest("", skills = true, notes = "we use Bazel"), submitted.single())
    }

    @Test
    fun `a refusal keeps the sheet open on the picks and says why`() {
        refusal = "This machine is not reachable. Try again."
        show()

        compose.onNodeWithTag("init-notes").performScrollTo().performTextInput("keep me")
        compose.onNodeWithTag("init-propose").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("This machine is not reachable. Try again.").assertIsDisplayed()
        assertEquals(0, dismissed)
        assertEquals("keep me", drafts.last()?.notes)
        assertTrue(submitted.single().notes == "keep me")
    }

    @Test
    fun `a project the machine cannot read is its sentence, and a silent machine is said`() {
        show(setup.copy(notice = "This chat has no project folder on the machine, so there is nothing to set up."))
        compose.onNodeWithText("This chat has no project folder on the machine, so there is nothing to set up.").assertIsDisplayed()
        compose.onNodeWithTag("init-propose").assertDoesNotExist()
    }

    @Test
    fun `a machine that did not answer is said rather than drawn as an empty picker`() {
        show(answer = null)
        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
        assertNull(drafts.lastOrNull())
    }
}
