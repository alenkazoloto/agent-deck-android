package dev.agentdeck.companion

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileMarketplaceChange
import com.github.claudeagents.core.mobile.MobilePluginToggle
import com.github.claudeagents.core.mobile.MobilePluginUninstall
import com.github.claudeagents.core.mobile.MobileAvailablePlugin
import com.github.claudeagents.core.mobile.MobilePluginInstall
import com.github.claudeagents.core.mobile.MobilePluginUpdate
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileSkillCopy
import com.github.claudeagents.core.mobile.MobileSkillCreate
import com.github.claudeagents.core.mobile.MobileSkillRow
import com.github.claudeagents.core.mobile.MobileSkillState
import com.github.claudeagents.core.mobile.MobileSkillsList
import com.github.claudeagents.core.mobile.MobileSkillsProject
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.claudeagents.core.mobile.MobileMemoryFile
import dev.agentdeck.companion.data.OpenMemoryFile
import dev.agentdeck.companion.data.SkillFileSheet
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.SkillFileActions
import dev.agentdeck.companion.ui.SkillFileDialog
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.PLUGIN_UNINSTALL_WORDS
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.marketplaceRemoveWords
import dev.agentdeck.companion.ui.availableMatching
import dev.agentdeck.companion.ui.availableSourceLine
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
    private val acme = MobileSkillRow("plugin", "review", "Review pull requests.", "acme · user", "Enabled", "v1.2.0 · 3 skills · 1 hook", "review@acme", true, true)
    private val alpha = MobileSkillsProject("/work/alpha", "alpha")
    private val beta = MobileSkillsProject("/work/beta", "beta")

    private val UNPINNED_SENTENCE = "That plugin is a zip archive with no sha256 pin, which the IDE asks you to confirm before fetching it. Update it there."

    private val toggled = mutableListOf<MobilePluginToggle>()
    private val uninstalled = mutableListOf<MobilePluginUninstall>()
    private val updated = mutableListOf<MobilePluginUpdate>()
    private val installed = mutableListOf<MobilePluginInstall>()
    private val stated = mutableListOf<MobileSkillState>()
    private val copied = mutableListOf<MobileSkillCopy>()
    private val createdSkills = mutableListOf<MobileSkillCreate>()
    private val editedFiles = mutableListOf<Pair<String, MobileSkillRow>>()
    private var drafts = emptySet<String>()
    private val refreshed = mutableListOf<MobileMarketplaceChange>()
    private val removed = mutableListOf<MobileMarketplaceChange>()
    private val market = MobileSkillRow("marketplace", "acme", "", "GitHub", "", "Updates itself")

    private fun show(
        capable: Boolean = true,
        toggle: (suspend (MobilePluginToggle) -> String?)? = null,
        uninstall: (suspend (MobilePluginUninstall) -> String?)? = null,
        update: (suspend (MobilePluginUpdate) -> String?)? = null,
        install: (suspend (MobilePluginInstall) -> String?)? = null,
        skillState: (suspend (MobileSkillState) -> String?)? = null,
        skillCopy: (suspend (MobileSkillCopy) -> String?)? = null,
        skillCreate: (suspend (MobileSkillCreate) -> String?)? = null,
        skillFile: Boolean = false,
        refreshMarketplace: (suspend (MobileMarketplaceChange) -> String?)? = null,
        removeMarketplace: (suspend (MobileMarketplaceChange) -> String?)? = null,
        answer: suspend (String?) -> MobileSkillsList?,
    ) {
        val base = DeckFixtures.byName("settings")!!
        val state = if (capable) {
            base.copy(
                hello = base.hello!!.copy(
                    capabilities = base.hello!!.capabilities + MobileProtocol.Capability.SKILLS_LIST +
                        listOfNotNull(
                            MobileProtocol.Capability.PLUGIN_TOGGLE.takeIf { toggle != null },
                            MobileProtocol.Capability.PLUGIN_UNINSTALL.takeIf { uninstall != null },
                            MobileProtocol.Capability.PLUGIN_UPDATE.takeIf { update != null },
                            MobileProtocol.Capability.PLUGIN_INSTALL.takeIf { install != null },
                            MobileProtocol.Capability.SKILL_STATE.takeIf { skillState != null },
                            MobileProtocol.Capability.SKILL_COPY.takeIf { skillCopy != null },
                            MobileProtocol.Capability.SKILL_CREATE.takeIf { skillCreate != null },
                            MobileProtocol.Capability.SKILL_FILE.takeIf { skillFile },
                            MobileProtocol.Capability.MARKETPLACE_REFRESH.takeIf { refreshMarketplace != null },
                            MobileProtocol.Capability.MARKETPLACE_REMOVE.takeIf { removeMarketplace != null },
                        ),
                ),
            )
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
                    onTogglePlugin = { request -> toggled += request; toggle?.invoke(request) },
                    onUninstallPlugin = { request -> uninstalled += request; uninstall?.invoke(request) },
                    onUpdatePlugin = { request -> updated += request; update?.invoke(request) },
                    onInstallPlugin = { request -> installed += request; install?.invoke(request) },
                    onSkillState = { request -> stated += request; skillState?.invoke(request) },
                    onCopySkill = { request -> copied += request; skillCopy?.invoke(request) },
                    onCreateSkill = { request -> createdSkills += request; skillCreate?.invoke(request) },
                    onEditSkillFile = { project, row -> editedFiles += project to row },
                    hasSkillFileDraft = { _, row -> row.name in drafts },
                    onRefreshMarketplace = { request -> refreshed += request; refreshMarketplace?.invoke(request) },
                    onRemoveMarketplace = { request -> removed += request; removeMarketplace?.invoke(request) },
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
    fun `a machine or phone without plugin-toggle offers no Turn on or Turn off`() {
        show { MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme)) }
        open()

        assertEquals(0, compose.onAllNodesWithText("Turn off").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Turn on").fetchSemanticsNodes().size)
    }

    @Test
    fun `Turn off sends the opposite of the shown state for that plugin alone and reads the list again`() {
        var enabled = true
        show(toggle = { request -> enabled = request.enabled; null }) {
            MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy, acme.copy(enabled = enabled, status = if (enabled) "Enabled" else "Off")))
        }
        open()
        compose.onNodeWithTag("plugin-toggle-review@acme").performScrollTo()
        compose.onNodeWithText("Turn off").assertIsDisplayed()
        assertEquals("only a plugin row offers it", 1, compose.onAllNodesWithText("Turn off").fetchSemanticsNodes().size)
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p32/plugin-toggle.png", RECORD)

        compose.onNodeWithTag("plugin-toggle-review@acme").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobilePluginToggle("review@acme", false, "/work/alpha")), toggled)
        assertEquals("the list was read again", listOf<String?>(null, null), asked)
        compose.onNodeWithTag("plugin-toggle-review@acme").performScrollTo()
        compose.onNodeWithText("Turn on").assertIsDisplayed()
        compose.onNodeWithText("acme · user · Off").assertIsDisplayed()
    }

    @Test
    fun `a change the machine did not make is its sentence, with the list read again`() {
        show(toggle = { "The machine could not change that plugin. See Settings › Plugins in the IDE." }) {
            MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme))
        }
        open()

        compose.onNodeWithTag("plugin-toggle-review@acme").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("The machine could not change that plugin. See Settings › Plugins in the IDE.").assertIsDisplayed()
        compose.onNodeWithText("acme · user · Enabled").assertIsDisplayed()
    }

    @Test
    fun `a machine or phone without plugin-uninstall offers no Uninstall`() {
        show(toggle = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme)) }
        open()

        assertEquals(0, compose.onAllNodesWithText("Uninstall…").fetchSemanticsNodes().size)
    }

    @Test
    fun `Uninstall asks first, names what goes, and only Uninstall sends that plugin's id`() {
        var gone = false
        show(uninstall = { gone = true; null }) {
            MobileSkillsList("/work/alpha", listOf(alpha), if (gone) listOf(deploy) else listOf(deploy, acme))
        }
        open()
        compose.onNodeWithTag("plugin-uninstall-review@acme").performScrollTo()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p32/plugin-uninstall.png", RECORD)

        compose.onNodeWithTag("plugin-uninstall-review@acme").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Uninstall \"review\"?").assertIsDisplayed()
        compose.onNodeWithText(PLUGIN_UNINSTALL_WORDS).assertIsDisplayed()
        compose.onNodeWithText("Keep").performClick()
        compose.waitForIdle()
        assertEquals("Keep asks nothing of the machine", emptyList<MobilePluginUninstall>(), uninstalled)

        compose.onNodeWithTag("plugin-uninstall-review@acme").performClick()
        compose.onNodeWithTag("plugin-uninstall-confirm").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobilePluginUninstall("review@acme", "/work/alpha")), uninstalled)
        assertEquals("the list was read again", listOf<String?>(null, null), asked)
        assertEquals("the plugin is gone from what is shown", 0, compose.onAllNodesWithText("Plugins (1)").fetchSemanticsNodes().size)
    }

    @Test
    fun `a plugin the machine authored itself has no Uninstall, though it can be turned off`() {
        show(toggle = { null }, uninstall = { null }) {
            MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme.copy(source = "skills-dir · user", removable = false)))
        }
        open()

        compose.onNodeWithTag("plugin-toggle-review@acme").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Uninstall…").fetchSemanticsNodes().size)
    }

    @Test
    fun `an uninstall the machine did not make is its sentence, with the list read again`() {
        show(uninstall = { "The machine could not uninstall that plugin. See Settings › Plugins in the IDE." }) {
            MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme))
        }
        open()

        compose.onNodeWithTag("plugin-uninstall-review@acme").performClick()
        compose.onNodeWithTag("plugin-uninstall-confirm").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("The machine could not uninstall that plugin. See Settings › Plugins in the IDE.").assertIsDisplayed()
        compose.onNodeWithText("acme · user · Enabled").assertIsDisplayed()
    }

    @Test
    fun `a machine or phone without plugin-update offers no Update`() {
        show(toggle = { null }, uninstall = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme)) }
        open()

        assertEquals(0, compose.onAllNodesWithText("Update").fetchSemanticsNodes().size)
    }

    @Test
    fun `Update is one tap that sends that plugin's id and reads the list again`() {
        show(update = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme)) }
        open()
        compose.onNodeWithTag("plugin-update-review@acme").performScrollTo()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p32/plugin-update.png", RECORD)

        compose.onNodeWithTag("plugin-update-review@acme").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobilePluginUpdate("review@acme", "/work/alpha")), updated)
        assertEquals("the list was read again", listOf<String?>(null, null), asked)
    }

    private val lint = MobileAvailablePlugin("lint@acme", "lint", "acme", "Lint every changed file before a commit.", 1234)
    private val deployer = MobileAvailablePlugin("deployer@other", "deployer", "other", "Ship a build to staging.")

    private fun offer() = MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme, market), available = listOf(lint, deployer))

    @Test
    fun `a machine or phone without plugin-install offers no available plugins, though it sent them`() {
        show(update = { null }) { offer() }
        open()

        assertEquals(0, compose.onAllNodesWithText("Available plugins (2)").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("plugin-install-lint@acme").fetchSemanticsNodes().size)
    }

    @Test
    fun `available plugins sit between the plugins and the marketplaces, each with where from and what it does`() {
        show(install = { null }) { offer() }
        open()
        compose.onNodeWithTag("available-search").performScrollTo()

        compose.onNodeWithText("Available plugins (2)").assertIsDisplayed()
        compose.onNodeWithTag("plugin-install-lint@acme").performScrollTo()
        compose.onNodeWithText("acme · 1,234 installs").assertIsDisplayed()
        compose.onNodeWithText("Lint every changed file before a commit.").assertIsDisplayed()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p32/plugin-install.png", RECORD)
    }

    @Test
    fun `the search field narrows the available plugins and says when nothing matches`() {
        show(install = { null }) { offer() }
        open()
        compose.onNodeWithTag("available-search").performScrollTo().performTextInput("STAGING")
        compose.waitForIdle()

        compose.onNodeWithTag("skills-list").performScrollToNode(hasTestTag("plugin-install-deployer@other"))
        compose.onNodeWithTag("plugin-install-deployer@other").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("plugin-install-lint@acme").fetchSemanticsNodes().size)

        compose.onNodeWithTag("available-search").performTextInput(" zzz")
        compose.waitForIdle()

        compose.onNodeWithText("No available plugin matches \"STAGING zzz\".").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `Install is one tap that sends that plugin's id, says Installed and reads the list again`() {
        show(install = { null }) { offer() }
        open()

        compose.onNodeWithTag("plugin-install-lint@acme").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobilePluginInstall("lint@acme", "/work/alpha")), installed)
        compose.onNodeWithText("Installed lint.").assertIsDisplayed()
        assertEquals("the list was read again", listOf<String?>(null, null), asked)
    }

    @Test
    fun `an install the machine refused is its sentence, with the list read again`() {
        val refusal = "Refused to install lint: it is a zip at http://example.com/l.zip, and a plugin archive must be served over HTTPS."
        show(install = { refusal }) { offer() }
        open()

        compose.onNodeWithTag("plugin-install-lint@acme").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText(refusal).assertIsDisplayed()
        assertEquals(listOf<String?>(null, null), asked)
    }

    @Test
    fun `the search matches every word against name, marketplace and description`() {
        val all = listOf(lint, deployer)
        assertEquals(all, availableMatching(all, "  "))
        assertEquals(listOf(lint), availableMatching(all, "LINT acme"))
        assertEquals(listOf(deployer), availableMatching(all, "other ship"))
        assertEquals(emptyList<MobileAvailablePlugin>(), availableMatching(all, "lint other"))
        assertEquals("acme · 1,234 installs", availableSourceLine(lint))
        assertEquals("other", availableSourceLine(deployer))
        assertEquals("other · 1 install", availableSourceLine(deployer.copy(installs = 1)))
    }

    @Test
    fun `a plugin the machine authored itself has no Update`() {
        show(update = { null }) {
            MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme.copy(source = "skills-dir · user", removable = false)))
        }
        open()

        assertEquals(0, compose.onAllNodesWithText("Update").fetchSemanticsNodes().size)
    }

    @Test
    fun `an update the machine did not make is its sentence, with the list read again`() {
        show(update = { UNPINNED_SENTENCE }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme)) }
        open()

        compose.onNodeWithTag("plugin-update-review@acme").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(UNPINNED_SENTENCE).assertIsDisplayed()
        compose.onNodeWithText("acme · user · Enabled").assertIsDisplayed()
    }

    @Test
    fun `marketplaces are a section of names and kinds of source, with no address in it`() {
        show(refreshMarketplace = { null }, removeMarketplace = { null }) {
            MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme, market, market.copy(name = "local", source = "Local folder", detail = "")))
        }
        open()
        compose.onNodeWithText("Marketplaces (2)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("marketplace-refresh-acme").performScrollTo()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p32/marketplaces.png", RECORD)

        compose.onNodeWithText("GitHub").assertIsDisplayed()
        compose.onNodeWithText("Updates itself").assertIsDisplayed()
        compose.onNodeWithTag("marketplace-remove-local").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a machine or phone without marketplace-refresh and marketplace-remove offers neither, though it lists them`() {
        show(toggle = { null }, uninstall = { null }, update = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(acme, market)) }
        open()

        compose.onNodeWithText("Marketplaces (1)").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Refresh").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Remove…").fetchSemanticsNodes().size)
    }

    @Test
    fun `Refresh is one tap that sends that marketplace's name and reads the list again`() {
        show(refreshMarketplace = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(market)) }
        open()

        compose.onNodeWithTag("marketplace-refresh-acme").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileMarketplaceChange("acme", "/work/alpha")), refreshed)
        assertEquals("the list was read again", listOf<String?>(null, null), asked)
        assertEquals("Remove is not offered without its own capability", 0, compose.onAllNodesWithText("Remove…").fetchSemanticsNodes().size)
    }

    @Test
    fun `Remove asks first in the desk's words, and only Remove sends that marketplace's name`() {
        var gone = false
        show(removeMarketplace = { gone = true; null }) {
            MobileSkillsList("/work/alpha", listOf(alpha), if (gone) emptyList() else listOf(market))
        }
        open()

        compose.onNodeWithTag("marketplace-remove-acme").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove Marketplace").assertIsDisplayed()
        compose.onNodeWithText(marketplaceRemoveWords("acme")).assertIsDisplayed()
        compose.onNodeWithText("Keep").performClick()
        compose.waitForIdle()
        assertEquals("Keep asks nothing of the machine", emptyList<MobileMarketplaceChange>(), removed)

        compose.onNodeWithTag("marketplace-remove-acme").performClick()
        compose.onNodeWithTag("marketplace-remove-confirm").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileMarketplaceChange("acme", "/work/alpha")), removed)
        assertEquals("the list was read again", listOf<String?>(null, null), asked)
        assertEquals("the marketplace is gone from what is shown", 0, compose.onAllNodesWithText("Marketplaces (1)").fetchSemanticsNodes().size)
    }

    @Test
    fun `a marketplace change the machine did not make is its sentence, with the list read again`() {
        val sentence = "The machine could not refresh that marketplace. See Settings › Plugins in the IDE."
        show(refreshMarketplace = { sentence }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(market)) }
        open()

        compose.onNodeWithTag("marketplace-refresh-acme").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(sentence).assertIsDisplayed()
        compose.onNodeWithText("Marketplaces (1)").assertIsDisplayed()
    }

    @Test
    fun `a skill the machine would write shows the desk's four states with the current one selected`() {
        show(skillState = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(state = "name-only", stateEditable = true))) }
        open()
        compose.onNodeWithTag("skill-state-/deploy-on").performScrollTo()

        compose.onNodeWithText("User-invocable only").assertIsDisplayed()
        compose.onNodeWithTag("skill-state-/deploy-name-only").assertIsSelected()
        compose.onNodeWithTag("skill-state-/deploy-on").assertIsNotSelected()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p30/skill-state.png", RECORD)
    }

    @Test
    fun `a state chip sends the row's name and that state and reads the list again`() {
        show(skillState = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(state = "on", stateEditable = true))) }
        open()

        compose.onNodeWithTag("skill-state-/deploy-off").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileSkillState("deploy", "off", "/work/alpha")), stated)
        assertEquals("the list was read again", listOf<String?>(null, null), asked)
    }

    @Test
    fun `the chip of the state a skill is already in sends nothing`() {
        show(skillState = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(state = "off", stateEditable = true))) }
        open()

        compose.onNodeWithTag("skill-state-/deploy-off").performClick()
        compose.waitForIdle()

        assertEquals(emptyList<MobileSkillState>(), stated)
    }

    @Test
    fun `a row the machine would not write, or a phone without skill-state, has no state chips`() {
        show(skillState = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(state = "on", stateEditable = false))) }
        open()
        assertEquals(0, compose.onAllNodesWithTag("skill-state-/deploy-off").fetchSemanticsNodes().size)
    }

    @Test
    fun `a phone without skill-state offers no chips even for an editable row`() {
        show { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(state = "on", stateEditable = true))) }
        open()

        assertEquals(0, compose.onAllNodesWithTag("skill-state-/deploy-off").fetchSemanticsNodes().size)
    }

    @Test
    fun `a state the machine did not save is its sentence, with the list read again`() {
        val sentence = "The machine cannot change skill states: this project's .claude/settings.local.json is invalid, unreadable or read-only. Fix it in the IDE."
        show(skillState = { sentence }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(state = "on", stateEditable = true))) }
        open()

        compose.onNodeWithTag("skill-state-/deploy-off").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(sentence).assertIsDisplayed()
        assertEquals(listOf<String?>(null, null), asked)
    }

    @Test
    fun `a directory skill offers the desk's copy in the direction it goes, with the desk's words in the dialog`() {
        show(skillCopy = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(copyTo = "personal"), fmt)) }
        open()

        compose.onNodeWithTag("skill-copy-/deploy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy to personal").assertIsDisplayed()
        assertEquals("a command file has no copy", 0, compose.onAllNodesWithTag("skill-copy-/fmt").fetchSemanticsNodes().size)
        compose.onNodeWithTag("skill-copy-/deploy").performClick()

        compose.onNodeWithText("Copies /deploy into your personal skills. The original stays.").assertIsDisplayed()
        compose.onNodeWithTag("skill-copy-name").assertIsDisplayed()
        compose.onAllNodes(isRoot()).get(compose.onAllNodes(isRoot()).fetchSemanticsNodes().size - 1).captureRoboImage("build/outputs/p30/skill-copy.png", RECORD)
    }

    @Test
    fun `copying under the skill's own name sends none, so the machine names it after the directory`() {
        show(skillCopy = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(copyTo = "personal"))) }
        open()

        compose.onNodeWithTag("skill-copy-/deploy").performScrollTo().performClick()
        compose.onNodeWithTag("skill-copy-confirm").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileSkillCopy("deploy", toProject = false, newName = "", project = "/work/alpha")), copied)
        assertEquals("the list was read again and the dialog closed", listOf<String?>(null, null), asked)
        assertEquals(0, compose.onAllNodesWithTag("skill-copy-name").fetchSemanticsNodes().size)
    }

    @Test
    fun `a typed name is sent as it is, and one the desk's rule refuses cannot be sent`() {
        show(skillCopy = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(name = "/apps/web:deploy", copyTo = "project"))) }
        open()

        compose.onNodeWithTag("skill-copy-/apps/web:deploy").performScrollTo().performClick()
        compose.onNodeWithText("Copies /apps/web:deploy into this project's .claude/skills, where the repository's collaborators get it too. The original stays.").assertIsDisplayed()
        compose.onNodeWithTag("skill-copy-name").performTextReplacement("../evil")
        compose.onNodeWithTag("skill-copy-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("skill-copy-name").performTextReplacement("Deploy Two")
        compose.onNodeWithTag("skill-copy-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("skill-copy-name").performTextReplacement("deploy-two")
        compose.onNodeWithTag("skill-copy-confirm").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileSkillCopy("apps/web:deploy", toProject = true, newName = "deploy-two", project = "/work/alpha")), copied)
    }

    @Test
    fun `a name the machine says is taken keeps the dialog open with its sentence, and another name goes through`() {
        val taken = "A skill with that name already exists there. Copy it under another name."
        var answer: String? = taken
        show(skillCopy = { answer }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(copyTo = "personal"))) }
        open()

        compose.onNodeWithTag("skill-copy-/deploy").performScrollTo().performClick()
        compose.onNodeWithTag("skill-copy-confirm").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(taken).assertIsDisplayed()
        assertEquals("no re-read while it failed", listOf<String?>(null), asked)

        answer = null
        compose.onNodeWithTag("skill-copy-name").performTextReplacement("deploy-2")
        compose.onNodeWithTag("skill-copy-confirm").performClick()
        compose.waitForIdle()

        assertEquals(listOf("", "deploy-2"), copied.map { it.newName })
        assertEquals(0, compose.onAllNodesWithTag("skill-copy-name").fetchSemanticsNodes().size)
    }

    @Test
    fun `New skill opens the desk's dialog with its choices, and never offers a script, model, effort or access`() {
        show(skillCreate = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy)) }
        open()

        compose.onNodeWithTag("skill-new").assertIsDisplayed().performClick()

        compose.onNodeWithTag("skill-new-name").assertIsDisplayed()
        compose.onNodeWithText("Lowercase letters, numbers and hyphens. This becomes /name.").assertIsDisplayed()
        compose.onNodeWithTag("skill-new-personal").assertIsSelected()
        compose.onNodeWithTag("skill-new-invoke-any").assertIsSelected()
        compose.onNodeWithText("Only I can invoke it").assertIsDisplayed()
        compose.onNodeWithText("Only Claude can invoke it").assertIsDisplayed()
        compose.onNodeWithText("Model, effort, access and script stay in the IDE: a script runs in its terminal.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Script").fetchSemanticsNodes().size)
        compose.onNodeWithTag("skill-new-confirm").assertIsNotEnabled()
        compose.onAllNodes(isRoot()).get(compose.onAllNodes(isRoot()).fetchSemanticsNodes().size - 1).captureRoboImage("build/outputs/p30/skill-new.png", RECORD)
    }

    @Test
    fun `a name the desk would refuse cannot be sent, and a good one goes with its scope, description and flag`() {
        show(skillCreate = { null }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy)) }
        open()

        compose.onNodeWithTag("skill-new").performClick()
        for (bad in listOf("../evil", "Deploy", "my-claude-skill")) {
            compose.onNodeWithTag("skill-new-name").performTextReplacement(bad)
            compose.onNodeWithTag("skill-new-confirm").assertIsNotEnabled()
        }
        compose.onNodeWithText("Names cannot include \"anthropic\" or \"claude\".").assertIsDisplayed()
        compose.onNodeWithTag("skill-new-name").performTextReplacement("triage-2")
        compose.onNodeWithText("This becomes /triage-2.").assertIsDisplayed()
        compose.onNodeWithTag("skill-new-project").performClick()
        compose.onNodeWithTag("skill-new-invoke-model").performClick()
        compose.onNodeWithTag("skill-new-description").performTextReplacement("Sorts the  new issues")
        compose.onNodeWithTag("skill-new-confirm").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileSkillCreate("triage-2", personal = false, description = "Sorts the new issues", invocation = "model", project = "/work/alpha")), createdSkills)
        assertEquals("the list was read again and the dialog closed", listOf<String?>(null, null), asked)
        assertEquals(0, compose.onAllNodesWithTag("skill-new-name").fetchSemanticsNodes().size)
    }

    @Test
    fun `a new skill's name the machine says is taken keeps the dialog open with its sentence, and another name goes through`() {
        val taken = "A skill with that name already exists there. Pick another name."
        var answer: String? = taken
        show(skillCreate = { answer }) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy)) }
        open()

        compose.onNodeWithTag("skill-new").performClick()
        compose.onNodeWithTag("skill-new-name").performTextReplacement("deploy")
        compose.onNodeWithTag("skill-new-confirm").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(taken).assertIsDisplayed()
        assertEquals("no re-read while it failed", listOf<String?>(null), asked)

        answer = null
        compose.onNodeWithTag("skill-new-name").performTextReplacement("deploy-2")
        compose.onNodeWithTag("skill-new-confirm").performClick()
        compose.waitForIdle()

        assertEquals(listOf("deploy", "deploy-2"), createdSkills.map { it.name })
        assertEquals(0, compose.onAllNodesWithTag("skill-new-name").fetchSemanticsNodes().size)
    }

    @Test
    fun `a phone without skill-create has no New skill button`() {
        show { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy)) }
        open()

        assertEquals(0, compose.onAllNodesWithTag("skill-new").fetchSemanticsNodes().size)
    }

    @Test
    fun `Edit file is offered on a skill and a command the machine marks editable, and opens that row's file`() {
        val editable = deploy.copy(fileEditable = true)
        val command = fmt.copy(fileEditable = true)
        show(skillFile = true) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(editable, review, command, reviewer, acme)) }
        open()

        compose.onNodeWithTag("skill-file-skill-/deploy").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals("a plugin's skill, a subagent and a plugin have no file here", 2, compose.onAllNodesWithText("Edit file").fetchSemanticsNodes().size)
        compose.onNodeWithTag("skill-file-command-/fmt").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("skill-file-skill-/deploy").performClick()

        assertEquals(listOf("/work/alpha" to editable), editedFiles)
    }

    @Test
    fun `a subagent the machine marks editable has Edit file, and opens that row's file`() {
        val editable = reviewer.copy(fileEditable = true)
        show(skillFile = true) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy, editable)) }
        open()

        compose.onNodeWithTag("skill-file-agent-reviewer").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals("only the subagent, whose row the machine marked", 1, compose.onAllNodesWithText("Edit file").fetchSemanticsNodes().size)

        compose.onNodeWithTag("skill-file-agent-reviewer").performClick()

        assertEquals(listOf("/work/alpha" to editable), editedFiles)
    }

    @Test
    fun `a row with unsaved typed text says so on its button`() {
        drafts = setOf("/deploy")
        show(skillFile = true) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(fileEditable = true))) }
        open()

        compose.onNodeWithText("Edit file (unsaved changes)").assertIsDisplayed()
    }

    @Test
    fun `a phone without skill-file, or a row the machine did not mark editable, has no Edit file`() {
        show { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(fileEditable = true))) }
        open()
        assertEquals(0, compose.onAllNodesWithText("Edit file").fetchSemanticsNodes().size)
    }

    @Test
    fun `a machine that offers skill-file but marks no row editable shows no Edit file`() {
        show(skillFile = true) { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy, review)) }
        open()

        assertEquals(0, compose.onAllNodesWithText("Edit file").fetchSemanticsNodes().size)
    }

    private val fileCalls = mutableListOf<String>()

    private val openFile = OpenMemoryFile(
        id = "skill:/deploy", label = "/deploy", writable = true, deletable = false, existed = true,
        savedText = SKILL_TEXT, revision = "r1", text = SKILL_TEXT,
    )

    private fun fileSheet(open: OpenMemoryFile?, error: String? = null, busy: Boolean = false) =
        SkillFileSheet("/work/alpha", "/deploy", "skill", "Project", open, error, busy)

    private fun fileActions(onEdit: (String) -> Unit = { fileCalls += "edit" }) = SkillFileActions(
        onEdit = onEdit, onSave = { fileCalls += "save" }, onDiscard = { fileCalls += "discard" }, onKeepMine = { fileCalls += "keep" },
        onLoadTheirs = { fileCalls += "theirs" }, onDismiss = { fileCalls += "dismiss" },
    )

    private fun showFile(sheet: SkillFileSheet) = compose.setContent { AgentDeckTheme(dark = false, dynamic = false) { SkillFileDialog(sheet, fileActions()) } }

    @Test
    fun `the file editor shows the skill's text and where it lives, with Save waiting for an edit`() {
        showFile(fileSheet(openFile))

        compose.onNodeWithText("/deploy").assertIsDisplayed()
        compose.onNodeWithText("Project").assertIsDisplayed()
        compose.onNodeWithTag("skill-file-field").assertIsDisplayed()
        compose.onNodeWithText("name: deploy", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("skill-file-save").assertIsDisplayed().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("skill-file-discard").assertIsNotEnabled()
        compose.onNodeWithTag("skill-file-back").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Back").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Read only:", substring = true).fetchSemanticsNodes().size)
        assertEquals(listOf("dismiss"), fileCalls)
    }

    @Test
    fun `an edited file offers Save and says its text is kept on the phone until the machine takes it`() {
        showFile(fileSheet(openFile.copy(text = SKILL_TEXT + "Ask before pushing to production.\n")))

        compose.onNodeWithText("Unsaved changes are kept on this phone.").assertIsDisplayed()
        compose.onNodeWithTag("skill-file-save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("skill-file-discard").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Ask before pushing to production.", substring = true).assertIsDisplayed()
        compose.onAllNodes(isRoot()).get(compose.onAllNodes(isRoot()).fetchSemanticsNodes().size - 1).captureRoboImage("build/outputs/p30/skill-file.png", RECORD)
    }

    @Test
    fun `typing reports the whole text and then Save sends it`() {
        var sheet by mutableStateOf(fileSheet(openFile))
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                SkillFileDialog(sheet, fileActions(onEdit = { text -> fileCalls += "edit"; sheet = sheet.copy(open = sheet.open!!.copy(text = text)) }))
            }
        }

        compose.onNodeWithTag("skill-file-field").performTextInput("\nAlways tag the release.")
        compose.onNodeWithText("Unsaved changes are kept on this phone.").assertIsDisplayed()
        compose.onNodeWithTag("skill-file-save").assertIsEnabled().performClick()

        assertEquals(listOf("edit", "save"), fileCalls)
    }

    @Test
    fun `a conflict shows both ways out and holds Save until one is chosen`() {
        val theirs = MobileMemoryFile("/work/alpha", "skill:/deploy", "/deploy", "edited on the desk", true, "r2", true)
        showFile(fileSheet(openFile.copy(text = "my edit", conflict = theirs)))

        compose.onNodeWithText("This file changed on the machine after you opened it.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("skill-file-save").assertIsNotEnabled()
        compose.onNodeWithTag("skill-file-load-theirs").assertIsDisplayed().performClick()
        compose.onNodeWithTag("skill-file-keep-mine").assertIsDisplayed().performClick()
        assertEquals(listOf("theirs", "keep"), fileCalls)
        assertEquals("my edit", compose.onNodeWithTag("skill-file-field").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
    }

    @Test
    fun `a phone that may not save reads the file, is told where to turn saving on, and has no Save`() {
        showFile(fileSheet(openFile.copy(writable = false)))

        compose.onNodeWithText("Read only:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Settings › Connections › Mobile › Devices", substring = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("skill-file-save").fetchSemanticsNodes().size)
        compose.onNodeWithText("name: deploy", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a failed save keeps the typed text and shows the machine's sentence`() {
        showFile(fileSheet(openFile.copy(text = "my edit", error = "That file is too large to edit on a phone. Edit it in the IDE.")))

        compose.onNodeWithText("That file is too large to edit on a phone. Edit it in the IDE.").assertIsDisplayed()
        compose.onNodeWithTag("skill-file-save").assertIsEnabled()
        assertEquals("my edit", compose.onNodeWithTag("skill-file-field").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
    }

    @Test
    fun `a file still being read says so instead of showing an empty editor`() {
        showFile(fileSheet(null, busy = true))
        compose.onNodeWithText("Reading the file…").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("skill-file-field").fetchSemanticsNodes().size)
    }

    @Test
    fun `a file the machine would not hand over shows its sentence`() {
        showFile(fileSheet(null, error = "That skill's file is no longer listed on the machine, or a plugin owns it. Reopen the list."))

        compose.onNodeWithText("That skill's file is no longer listed on the machine, or a plugin owns it. Reopen the list.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("skill-file-field").fetchSemanticsNodes().size)
    }

    @Test
    fun `a phone without skill-copy, or a row with nothing to copy, has no copy button`() {
        show { MobileSkillsList("/work/alpha", listOf(alpha), listOf(deploy.copy(copyTo = "personal"))) }
        open()

        assertEquals(0, compose.onAllNodesWithTag("skill-copy-/deploy").fetchSemanticsNodes().size)
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
        const val SKILL_TEXT = "---\nname: deploy\ndescription: Build, tag and ship the current branch to staging.\nargument-hint: <env>\n---\n\nRun the test suite, tag the release and push it to \$ARGUMENTS.\n"
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
