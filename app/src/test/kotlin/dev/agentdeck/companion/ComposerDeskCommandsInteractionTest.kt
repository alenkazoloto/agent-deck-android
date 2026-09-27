package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileBackgroundTask
import com.github.claudeagents.core.mobile.MobileCommand
import com.github.claudeagents.core.mobile.MobileDeskCommands
import com.github.claudeagents.core.mobile.MobilePreset
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.data.ComposerPicks
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P15 re-audit: the desk composer's own commands (`/model`, `/stop`, `/copy`…) typed on the phone
 * run the phone's control instead of reaching the agent as prose, against the real
 * `ConversationScreen`. Before, `/v1/commands` left them out and a sent `/stop` was a message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerDeskCommandsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private val sent = mutableListOf<String>()
    private var stops = 0
    private val notices = mutableListOf<String>()
    private val sides = mutableListOf<String>()
    private val autoCompacts = mutableListOf<String>()
    private val navigations = mutableListOf<MobileDeskCommands.Action>()
    private val picks = mutableListOf<Pair<ComposerPicks.Field, String?>>()
    private var catalogue: List<MobileCommand>? = listOf(MobileCommand("/deploy", "Ship it"))
    private var pendingCatalogue: kotlinx.coroutines.CompletableDeferred<List<MobileCommand>?>? = null

    @Test
    fun `the popup offers a desk command and picking it opens the phone's control`() {
        show(running = false)

        compose.onNode(hasSetTextAction()).performTextReplacement("/mo")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Choose the model in Run settings").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Choose the model in Run settings").performClick()

        compose.onNodeWithTag("run-settings-sheet").assertExists()
        compose.runOnIdle {
            assertEquals("", draft.value)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `a sent stop stops the running turn instead of messaging the agent`() {
        show(running = true)

        type("/stop")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(1, stops)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `stop with nothing running says so and keeps the line`() {
        show(running = false)

        type("/stop")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(0, stops)
            assertTrue(sent.isEmpty())
            assertEquals("/stop", draft.value)
            assertEquals(listOf("No running turn to stop."), notices)
        }
    }

    @Test
    fun `copy puts the last response on the clipboard`() {
        show(running = false)

        type("/copy")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            val clipboard = ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("All 42 tests pass.", clipboard.primaryClip?.getItemAt(0)?.text.toString())
            assertEquals(listOf("Copied the last response."), notices)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `new chat leaves through the view model`() {
        show(running = false)

        type("/clear")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle { assertEquals(listOf(MobileDeskCommands.Action.NEW_CHAT), navigations) }
    }

    @Test
    fun `words after the command, a user's own command and Codex's diff are still messages`() {
        catalogue = listOf(MobileCommand("/stop", "Stop the dev server"))
        show(running = true)
        type("/stop")
        compose.onNodeWithContentDescription("Send").performClick()
        type("/model opus")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(0, stops)
            assertEquals(listOf("/stop", "/model opus"), sent)
        }
    }

    @Test
    fun `a send before the catalogue arrives goes to the agent`() {
        // The user's own `/stop` may be in the list still on its way; the phone cannot know.
        val gate = kotlinx.coroutines.CompletableDeferred<List<MobileCommand>?>()
        pendingCatalogue = gate
        show(running = true)
        compose.onNode(hasSetTextAction()).performTextReplacement("/stop")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(0, stops)
            assertEquals(listOf("/stop"), sent)
        }
        gate.complete(null)
    }

    @Test
    fun `a Codex chat sends diff to Codex`() {
        show(running = false, vendor = AgentVendor.CODEX)

        type("/diff")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle { assertEquals(listOf("/diff"), sent) }
    }

    @Test
    fun `a bare personality in a Codex chat opens Codex settings instead of spending a turn`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.SESSION_CODEX)

        type("/personality")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.CODEX_SETTINGS), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `bare add-dir, mcp-servers and spend open their chat sheets instead of spending a turn`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/add-dir")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.ADD_DIR), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `bare memory opens the memory sheet on a Codex chat too`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.MEMORY_FILES)
        type("/memory")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.MEMORY), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `memory is a message on a machine that does not serve memory files`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/memory")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/memory"), sent)
        }
    }

    @Test
    fun `bare mcp opens its sheet on a Codex chat too`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.MCP_SERVERS)
        type("/mcp")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.MCP), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `bare insights opens its sheet on a Codex chat too`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.SESSION_INSIGHTS)
        type("/insights")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.INSIGHTS), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `bare skills opens its sheet on a Codex chat too`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.SKILLS_LIST)
        type("/skills")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.SKILLS), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `bare config opens Workspace instead of spending a turn`() {
        show(running = false)
        type("/config")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.SETTINGS), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `bare plugins opens the skills sheet on a Codex chat`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.SKILLS_LIST)
        type("/plugins")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.SKILLS), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `plugins on a Claude chat is the CLI's own command and is sent`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SKILLS_LIST)
        type("/plugins")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/plugins"), sent)
        }
    }

    @Test
    fun `bare tasks brings the background note into view and clears the line`() {
        show(running = false, tasks = listOf(MobileBackgroundTask("shell", "npm run dev", "b1")))
        type("/tasks")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertTrue(notices.isEmpty())
            assertEquals("", draft.value)
        }
        compose.onNodeWithText("1 background task").assertExists()
    }

    @Test
    fun `tasks with nothing running says so instead of messaging the agent`() {
        show(running = false)
        type("/tasks")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Nothing is running in the background."), notices)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `tasks is a message on a Codex chat, whose list is ps`() {
        show(running = false, vendor = AgentVendor.CODEX)
        type("/tasks")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle { assertEquals(listOf("/tasks"), sent) }
    }

    @Test
    fun `skills is a message on a machine that does not list skills`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/skills")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/skills"), sent)
        }
    }

    @Test
    fun `bare recap asks for the chat's line on a Claude chat`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.RECAP)
        type("/recap")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.RECAP), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `recap is a message in a Codex chat and on a machine without the route`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.RECAP)
        type("/recap")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/recap"), sent)
        }
    }

    @Test
    fun `recap is a message on a machine that does not serve it`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/recap")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/recap"), sent)
        }
    }

    @Test
    fun `bare goal reads the thread's goal on a Codex chat and clears the line`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.CODEX_GOAL)
        type("/goal")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.CODEX_GOAL), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `goal is a message on a Claude chat, whose goal is the CLI's own`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.CODEX_GOAL)
        type("/goal")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/goal"), sent)
        }
    }

    @Test
    fun `goal is a message on a machine that does not serve it`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/goal")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/goal"), sent)
        }
    }

    @Test
    fun `bare version reads the build a Claude chat runs and clears the line`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.CLI_VERSION)
        type("/version")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.CLI_VERSION), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `version is a message on a Codex chat, which has no Claude Code version`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.CLI_VERSION)
        type("/version")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/version"), sent)
        }
    }

    @Test
    fun `version is a message on a machine that does not serve it`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/version")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/version"), sent)
        }
    }

    @Test
    fun `bare reload-plugins rescans for a Claude chat and clears the line`() = bareReload("/reload-plugins", MobileDeskCommands.Action.RELOAD_PLUGINS)

    @Test
    fun `bare reload-skills rescans for a Claude chat and clears the line`() = bareReload("/reload-skills", MobileDeskCommands.Action.RELOAD_SKILLS)

    private fun bareReload(name: String, action: MobileDeskCommands.Action) {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.EXTENSION_RELOAD)
        type(name)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(action), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `autocompact with a window hands it to the machine and clears the line`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.AUTO_COMPACT_WINDOW)

        // Typed through the bare name, as a keyboard does: that keystroke is what reads the catalogue.
        type("/autocompact")
        type("/autocompact 500k")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(listOf("500k"), autoCompacts)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `a bare autocompact asks the machine for the window in force`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.AUTO_COMPACT_WINDOW)
        type("/autocompact")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.AUTO_COMPACT), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `autocompact is a message on a Codex chat and on a machine that does not serve it`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.AUTO_COMPACT_WINDOW)
        type("/autocompact 500k")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(autoCompacts.isEmpty() && navigations.isEmpty())
            assertEquals(listOf("/autocompact 500k"), sent)
        }
    }

    @Test
    fun `a reload is a message on a Codex chat, which has neither plugins nor Claude Code's skills`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.EXTENSION_RELOAD)
        type("/reload-plugins")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/reload-plugins"), sent)
        }
    }

    @Test
    fun `a reload is a message on a machine that does not serve it`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/reload-skills")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/reload-skills"), sent)
        }
    }

    @Test
    fun `mcp is a message on a machine that does not list MCP servers`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/mcp")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/mcp"), sent)
        }
    }

    @Test
    fun `add-dir with a path goes to the agent as before`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/add-dir ../lib")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/add-dir ../lib"), sent)
        }
    }

    @Test
    fun `side with a question in a Codex chat opens the copy that asks it, and clears the line`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.CODEX_SIDE)

        // Typed through the bare name, as a keyboard does: that keystroke is what reads the catalogue.
        type("/side")
        type("/side Why is the build red?")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(listOf("Why is the build red?"), sides)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `a bare side has no question to start the copy with, so it says so and keeps the line`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.CODEX_SIDE)

        type("/side")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertTrue(sides.isEmpty())
            assertTrue(sent.isEmpty())
            assertEquals("/side", draft.value)
            assertTrue(notices.single().startsWith("Ask the question with it: /side <question>"))
        }
    }

    @Test
    fun `side while the Codex chat runs waits, in the desk's words, and keeps the line`() {
        show(running = true, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.CODEX_SIDE)

        // Typed through the bare name, as a keyboard does: that keystroke is what reads the catalogue.
        type("/side")
        type("/side Why?")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertTrue(sides.isEmpty())
            assertEquals("/side Why?", draft.value)
            assertEquals(listOf("Wait for this turn to finish before opening a side conversation."), notices)
        }
    }

    @Test
    fun `side is a message on a Claude chat, whose side question is btw`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.CODEX_SIDE)
        // Typed through the bare name, as a keyboard does: that keystroke is what reads the catalogue.
        type("/side")
        type("/side Why?")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(sides.isEmpty())
            assertEquals(listOf("/side Why?"), sent)
        }
    }

    @Test
    fun `side without the machine's codex-side route goes to Codex as before`() {
        show(running = false, vendor = AgentVendor.CODEX)
        // Typed through the bare name, as a keyboard does: that keystroke is what reads the catalogue.
        type("/side")
        type("/side Why?")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(sides.isEmpty())
            assertEquals(listOf("/side Why?"), sent)
        }
    }

    @Test
    fun `personality is still a message on a Claude chat`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_CODEX)
        type("/personality")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle { assertEquals(listOf("/personality"), sent) }
    }

    @Test
    fun `personality without the machine's Codex settings goes to Codex as before`() {
        show(running = false, vendor = AgentVendor.CODEX)
        type("/personality")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/personality"), sent)
        }
    }

    @Test
    fun `a dollar at the start of a Claude message lists the skills and writes the one name the CLI expands`() {
        catalogue = listOf(MobileCommand("/deploy", "Ship it"), MobileCommand("\$triage", "Sort the inbox", skill = true, written = "/triage"))
        show(running = false)

        compose.onNode(hasSetTextAction()).performTextReplacement("\$tri")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("\$triage").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("the / rows stay out of a dollar popup", 0, compose.onAllNodesWithText("/deploy").fetchSemanticsNodes().size)
        compose.onNodeWithText("\$triage").performClick()

        compose.runOnIdle { assertEquals("/triage ", draft.value) }
    }

    @Test
    fun `a dollar mid-sentence in a Claude message opens nothing, as a slash does not`() {
        catalogue = listOf(MobileCommand("\$triage", "Sort the inbox", skill = true, written = "/triage"))
        show(running = false)

        type("run \$tri")

        assertEquals(0, compose.onAllNodesWithText("\$triage").fetchSemanticsNodes().size)
    }

    /** Not a golden: the popup with a desk row, written under `build/outputs/p15/`. */
    @Test
    fun `render the desk command rows`() {
        show(running = true)
        compose.onNode(hasSetTextAction()).performTextReplacement("/s")
        compose.waitForIdle()
        compose.onAllNodes(isRoot())[0].captureRoboImage("build/outputs/p15/desk-command-rows.png", RECORD)
    }

    /** Types and waits for the one catalogue read, so Send can ask it whether the name is the user's. */
    private fun type(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun show(
        running: Boolean,
        vendor: AgentVendor = AgentVendor.CLAUDE,
        extra: String? = null,
        tasks: List<MobileBackgroundTask> = emptyList(),
        presets: List<MobilePreset> = emptyList(),
    ) {
        val pills = DeckFixtures.byName("convo-composer-pills")!!
        val hello = pills.hello!!.let {
            it.copy(capabilities = it.capabilities + MobileProtocol.Capability.COMMANDS + listOfNotNull(extra), presets = presets)
        }
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests",
            turns = listOf(
                MobileTurn("t1", "user", "Run the tests", DeckFixtures.NOW - 60_000),
                MobileTurn("t2", "assistant", "All 42 tests pass.", DeckFixtures.NOW - 30_000),
            ),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = running, generatedAtMs = DeckFixtures.NOW,
            backgroundTasks = tasks,
        )
        compose.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, vendor, "/project"),
                    page = page,
                    loading = false,
                    cached = false,
                    draft = draft.value,
                    notice = null,
                    onDraft = { draft.value = it },
                    onSend = { text, _ -> sent += text },
                    onStop = { stops++ },
                    onDismissNotice = {},
                    hello = hello,
                    onLoadCommands = { pendingCatalogue?.await() ?: catalogue },
                    onDeskNavigation = { navigations += it },
                    onCommandNotice = { notices += it },
                    onCodexSide = { sides += it },
                    onAutoCompact = { autoCompacts += it },
                    onPick = { field, value -> picks += field to value },
                )
            }
        }
    }

    @Test
    fun `bare release notes opens its sheet on a Codex chat too`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.RELEASE_NOTES)
        type("/release-notes")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.RELEASE_NOTES), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `release notes is a message on a machine that does not serve it`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/release-notes")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/release-notes"), sent)
        }
    }

    @Test
    fun `bare status opens its sheet on a Codex chat too`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.STATUS)
        type("/status")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.STATUS), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `status is a message on a machine that does not serve it`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/status")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/status"), sent)
        }
    }

    private val review = MobilePreset("Review", AgentVendor.CLAUDE, model = "opus", effort = "high", permissionMode = "plan", extras = listOf("Extra instructions"))
    private val quick = MobilePreset("Quick fix", AgentVendor.CLAUDE, model = "haiku")
    private val codexOnly = MobilePreset("Codex deep", AgentVendor.CODEX, model = "gpt-5")

    @Test
    fun `bare p lists this agent's presets and a tap puts its cells on the next message`() {
        show(running = false, extra = MobileProtocol.Capability.AGENT_PRESETS, presets = listOf(review, quick, codexOnly))
        type("/p")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.onNodeWithTag("presets-sheet").assertExists()
        compose.onNodeWithTag("preset-Codex deep").assertDoesNotExist()
        compose.onNodeWithTag("preset-Review").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(ComposerPicks.Field.MODEL to "opus", ComposerPicks.Field.EFFORT to "high", ComposerPicks.Field.MODE to "plan"),
                picks,
            )
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
            assertTrue(notices.single().contains("Extra instructions apply only to a new chat"))
        }
    }

    @Test
    fun `p with a name applies the matching preset at once and a preset naming no effort leaves Default`() {
        show(running = false, extra = MobileProtocol.Capability.AGENT_PRESETS, presets = listOf(review, quick))
        // Typed through the bare name, as a keyboard does: that keystroke is what reads the catalogue.
        type("/p")
        type("/p quick")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(ComposerPicks.Field.MODEL to "haiku", ComposerPicks.Field.EFFORT to null, ComposerPicks.Field.MODE to null),
                picks,
            )
            assertTrue(sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `p with a name nothing matches keeps the line and says so`() {
        show(running = false, extra = MobileProtocol.Capability.AGENT_PRESETS, presets = listOf(review))
        // Typed through the bare name, as a keyboard does: that keystroke is what reads the catalogue.
        type("/p")
        type("/p nothing")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(picks.isEmpty())
            assertTrue(sent.isEmpty())
            assertEquals("/p nothing", draft.value)
            assertEquals(listOf("No preset matches “nothing”."), notices)
        }
    }

    @Test
    fun `p is a message when the machine lists no preset for this agent`() {
        show(running = false, extra = MobileProtocol.Capability.AGENT_PRESETS, presets = listOf(codexOnly))
        type("/p")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(picks.isEmpty())
            assertEquals(listOf("/p"), sent)
        }
    }

    @Test
    fun `bare permissions opens its sheet on a Codex chat`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.CODEX_PERMISSIONS)
        type("/permissions")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.CODEX_PERMISSIONS), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `permissions is a message on a Claude chat`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.CODEX_PERMISSIONS)
        type("/permissions")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/permissions"), sent)
        }
    }

    @Test
    fun `permissions is a message on a Codex chat whose machine does not serve it`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/permissions")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/permissions"), sent)
        }
    }

    @Test
    fun `bare debug-config opens its sheet on a Codex chat`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.CODEX_DEBUG_CONFIG)
        type("/debug-config")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.CODEX_DEBUG_CONFIG), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `debug-config is a message on a Claude chat`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.CODEX_DEBUG_CONFIG)
        type("/debug-config")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/debug-config"), sent)
        }
    }

    @Test
    fun `debug-config is a message on a Codex chat whose machine does not serve it`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/debug-config")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/debug-config"), sent)
        }
    }

    @Test
    fun `bare hooks opens its sheet on a Claude chat`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.HOOKS)
        type("/hooks")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.HOOKS), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `hooks is a message on a Codex chat`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.HOOKS)
        type("/hooks")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/hooks"), sent)
        }
    }

    @Test
    fun `hooks is a message on a Claude chat whose machine does not serve it`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/hooks")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/hooks"), sent)
        }
    }

    @Test
    fun `bare apps opens its sheet on a Codex chat`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.CODEX_APPS)
        type("/apps")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.CODEX_APPS), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `apps is a message on a Claude chat`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.CODEX_APPS)
        type("/apps")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/apps"), sent)
        }
    }

    @Test
    fun `apps is a message on a Codex chat whose machine does not serve it`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/apps")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/apps"), sent)
        }
    }

    @Test
    fun `bare experimental opens its sheet on a Codex chat`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.CODEX_FEATURES)
        type("/experimental")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.CODEX_FEATURES), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `experimental is a message on a Claude chat`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.CODEX_FEATURES)
        type("/experimental")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/experimental"), sent)
        }
    }

    @Test
    fun `experimental is a message on a Codex chat whose machine does not serve it`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.SESSION_DIRS)
        type("/experimental")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/experimental"), sent)
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
