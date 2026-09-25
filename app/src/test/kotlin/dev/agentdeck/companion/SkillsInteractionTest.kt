package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileSkillRow
import com.github.claudeagents.core.mobile.MobileSkillsList
import com.github.claudeagents.core.mobile.MobileSkillsProject
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.skillSections
import dev.agentdeck.companion.ui.skillSourceLine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Resources › "Skills, agents and plugins" is the desk's skills, commands and subagents, read-only:
 * offered only by a machine that advertises `skills-list`, read again each time it opens and when another
 * project is picked, every state a word, and never more than the machine's description.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkillsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val asked = mutableListOf<String?>()

    private val deploy = MobileSkillRow("skill", "/deploy", "Build, tag and ship the current branch to staging.", "Project", "You · Claude", "argument: <env>")
    private val review = MobileSkillRow("skill", "/plugin:review", "Review a pull request.", "Plugin · review@acme", "Off")
    private val fmt = MobileSkillRow("command", "/fmt", "Format the staged files.", "Personal commands", "You · Claude")
    private val reviewer = MobileSkillRow(
        "agent", "reviewer", "Reviews diffs for risky changes.", "Personal", "", "model: opus · tools: Read, Grep",
    )
    private val acme = MobileSkillRow("plugin", "review", "Review pull requests.", "acme · user", "Enabled", "v1.2.0 · 3 skills · 1 hook")
    private val alpha = MobileSkillsProject("/work/alpha", "alpha")
    private val beta = MobileSkillsProject("/work/beta", "beta")

    private fun show(capable: Boolean = true, answer: suspend (String?) -> MobileSkillsList?) {
        val base = DeckFixtures.byName("settings")!!
        val state = if (capable) {
            base.copy(hello = base.hello!!.copy(capabilities = base.hello!!.capabilities + MobileProtocol.Capability.SKILLS_LIST))
        } else {
            base
        }
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                SettingsScreen(
                    state = state, onSettings = {}, onSwitchMachine = {}, onAddMachine = {}, onUnpair = {},
                    onRefreshHello = {}, onRefreshPush = {}, onChoosePush = {}, onCheckUpdate = {},
                    onDownloadUpdate = {}, onInstallUpdate = {}, onReleasePage = {},
                    onLoadSkills = { project -> asked += project; answer(project) },
                )
            }
        }
    }

    private fun open() {
        compose.onNodeWithText("Skills, agents and plugins").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a machine without the capability has no row`() {
        show(capable = false) { null }

        assertEquals(0, compose.onAllNodesWithText("Skills, agents and plugins").fetchSemanticsNodes().size)
    }

    @Test
    fun `the sheet lists skills, commands and subagents in sections with source, status and detail in words`() {
        show { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy, review, fmt, reviewer, acme)) }
        open()

        compose.onNodeWithText("Skills (2)").assertIsDisplayed()
        compose.onNodeWithText("Commands (1)").assertIsDisplayed()
        compose.onNodeWithText("Subagents (1)").assertIsDisplayed()
        compose.onNodeWithText("/deploy").assertIsDisplayed()
        compose.onNodeWithText("Project · You · Claude").assertIsDisplayed()
        compose.onNodeWithText("Build, tag and ship the current branch to staging.").assertIsDisplayed()
        compose.onNodeWithText("Plugin · review@acme · Off").assertIsDisplayed()
        compose.onNodeWithText("model: opus · tools: Read, Grep").assertIsDisplayed()
        assertEquals("one project, so no chips", 0, compose.onAllNodesWithText("alpha").fetchSemanticsNodes().size)

        // The plugin sits below the fold of the 560dp list: scrolled to, it is the desk's row — on or off, where from, what it adds.
        compose.onNodeWithText("v1.2.0 · 3 skills · 1 hook").performScrollTo()
        compose.onNodeWithText("Plugins (1)").assertIsDisplayed()
        compose.onNodeWithText("review").assertIsDisplayed()
        compose.onNodeWithText("acme · user · Enabled").assertIsDisplayed()
        compose.onNodeWithText("v1.2.0 · 3 skills · 1 hook").assertIsDisplayed()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p30/skills-sheet.png", RECORD)
    }

    @Test
    fun `a second open project shows chips, and picking one reads that project`() {
        show { project ->
            MobileSkillsList(project ?: "/work/alpha", listOf(alpha, beta), if (project == "/work/beta") listOf(fmt) else listOf(deploy))
        }
        open()
        compose.onNodeWithText("/deploy").assertIsDisplayed()

        compose.onNodeWithText("beta").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("/fmt").assertIsDisplayed()
        assertEquals(listOf<String?>(null, "/work/beta"), asked)
    }

    @Test
    fun `a project the machine did not answer for keeps the chips, so another can be picked`() {
        show { project ->
            if (project == "/work/beta") null else MobileSkillsList(project ?: "/work/alpha", listOf(alpha, beta), listOf(deploy))
        }
        open()

        compose.onNodeWithText("beta").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
        compose.onNodeWithText("alpha").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("/deploy").assertIsDisplayed()
    }

    @Test
    fun `what the machine could not read is said, not shown as an empty list`() {
        show { MobileSkillsList("/work/alpha", listOf(alpha), listOf(reviewer), listOf("The machine could not read this project's skills. See Settings › Skills in the IDE.")) }
        open()

        compose.onNodeWithText("The machine could not read this project's skills. See Settings › Skills in the IDE.").assertIsDisplayed()
        compose.onNodeWithText("reviewer").assertIsDisplayed()
    }

    @Test
    fun `a project with nothing says so`() {
        show { MobileSkillsList("/work/alpha", listOf(alpha)) }
        open()

        compose.onNodeWithText("This project has no skills, commands, subagents or plugins.").assertIsDisplayed()
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty page`() {
        show { null }
        open()

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }

    @Test
    fun `two rows with the same name do not share a list key`() {
        show { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy, deploy.copy(status = "Not active (shadowed)"))) }
        open()

        compose.onNodeWithText("Project · Not active (shadowed)").assertIsDisplayed()
    }

    @Test
    fun `the lines are words and the empty sections are left out`() {
        assertEquals("Project · You · Claude", skillSourceLine(deploy))
        assertEquals("Personal", skillSourceLine(reviewer))
        assertEquals(listOf("Skills (2)", "Subagents (1)", "Plugins (1)"), skillSections(listOf(deploy, review, reviewer, acme)).map { it.first })
        assertEquals("acme · user · Enabled", skillSourceLine(acme))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
