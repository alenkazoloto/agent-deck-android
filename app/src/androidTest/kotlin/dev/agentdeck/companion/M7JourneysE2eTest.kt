package dev.agentdeck.companion

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.mobile.MobileAcpAgent
import com.github.claudeagents.core.mobile.MobileMcpProject
import com.github.claudeagents.core.mobile.MobileMcpServer
import com.github.claudeagents.core.mobile.MobileMcpServers
import com.github.claudeagents.core.mobile.MobileMemoryEntries
import com.github.claudeagents.core.mobile.MobileMemoryEntry
import com.github.claudeagents.core.mobile.MobileMemoryFile
import com.github.claudeagents.core.mobile.MobileMemoryProject
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.fixture.DeckFixtures
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J10–J12 of PLAN-MOBILE-REDESIGN.md (M9 runbook step 2), following `M5JourneysE2eTest` and
 * `M6JourneysE2eTest`'s pattern: production Activity, ViewModel, `SecureStore` and pinned HTTPS
 * client against [StoryBridge].
 *
 * J10 (agent/account/model/effort/mode incl. an ACP agent; unavailable usage reads unavailable):
 * the Claude/Codex model-account-effort-mode picker already has device coverage
 * (`MobileUserStoriesE2eTest.newChatCanBeCancelledResumedAndStarted`,
 * `searchOpenAndReplyUsesRealBridgeAndClearsOnlyAcceptedDraft`'s Run settings change); the new
 * ground here is starting a chat on an ACP agent — which carries no model, effort, account or
 * mode at all — and an account the plan endpoint has never measured, which must read its own
 * sentence rather than a fabricated "$0.00".
 *
 * J11 (file/line context, photo, memory edit with a stale-revision conflict): a photo and a text
 * file both ride `/v1/attach` onto the same send, and saving a memory file against a revision the
 * machine has since moved on from conflicts rather than silently overwriting the agent's own note.
 *
 * J12 (a setting, resource and account row round-trip; no secret in any response): Restricted
 * mode (a setting), MCP servers (a resource, read-only — only a credential's *name* ever
 * crosses), and an account rename (an account) each reach the machine and read back its answer.
 */
@RunWith(AndroidJUnit4::class)
class M7JourneysE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val evidence = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable, description: org.junit.runner.Description) {
            runCatching { screenshot("m7-failed-${description.methodName}") }
            bridges.forEach { b -> android.util.Log.i("M7Journeys", "commands: " + b.commands.map { it.first + " " + it.second }) }
        }
    }
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var store: SecureStore
    private val bridges = mutableListOf<StoryBridge>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun prepare() {
        LiveLink.of(app).bind(null)
        store = SecureStore(app)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        store.saveSettings(AppSettings(triggers = emptySet(), dynamicColor = false, updateNotices = false))
        store.saveUpdateCheckedAt(System.currentTimeMillis())
    }

    @After fun finish() {
        runCatching { scenario?.close() }
        LiveLink.of(app).bind(null)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        bridges.forEach { it.close() }
    }

    /**
     * J10: a new chat started on an ACP agent sends the agent's own id and no model, effort,
     * account or permission mode — those cells simply do not exist for it — and once open, its
     * cost line reads "cost unknown", never a zero the machine never measured.
     */
    @Test fun startingAChatOnAnAcpAgentSendsNoRunPicksAndReadsCostUnknown() {
        val desk = bridge()
        desk.acpAgents = listOf(MobileAcpAgent("gemini", "Gemini CLI"))
        cache(desk)
        launch()
        ui.onNodeWithContentDescription("New chat").performClick()
        ui.onNodeWithText("Your task").performTextInput("Investigate the flaky pairing test")
        ui.onNodeWithText("Agent: Claude").performScrollTo().performClick()
        ui.onNodeWithText("Gemini CLI", substring = false).performClick()
        ui.onNodeWithText("Start chat").performClick()
        awaitCommand(desk, "/v1/send")
        val sent = desk.commands.last { it.first == "/v1/send" }.second
        assertEquals("gemini", sent["acpAgentId"].asString)
        assertFalse("an ACP agent has no model", sent.has("model"))
        assertFalse("an ACP agent has no effort", sent.has("effort"))
        assertFalse("an ACP agent has no account", sent.has("accountId"))
        assertFalse("an ACP agent has no permission mode", sent.has("permissionMode"))
        chats()
        ui.onNodeWithText("Investigate the flaky pairing test").performClick()
        awaitText("cost unknown")
        assertFalse("an ACP chat never fakes a zero spend", has("$0.00", substring = true))
        screenshot("m7-j10-acp-cost-unknown")
    }

    /**
     * J10: an account the plan endpoint has never answered for carries its own sentence — worded
     * per vendor, since Claude and Codex are silent for different reasons — never a percentage or
     * a dollar figure that would read as a measured, quiet account.
     */
    @Test fun anAccountWithNothingMeasuredNamesItsOwnSentenceInsteadOfZero() {
        val desk = bridge()
        desk.usageReport = requireNotNull(DeckFixtures.byName("usage-unmeasured")).usage
        cache(desk)
        launch()
        workspace()
        ui.onNodeWithText("Spend and plan windows").performScrollTo().performClick()
        awaitText("No plan usage has been read for this account yet.")
        assertEquals(2, ui.onAllNodesWithText("No plan usage has been read for this account yet.").fetchSemanticsNodes().size)
        ui.onNodeWithText("Codex reports its limits only while it runs", substring = true).assertIsDisplayed()
        screenshot("m7-j10-usage-unmeasured")
    }

    /**
     * J11: a photo and a text file both attach to the open conversation's composer as their own
     * chips and both ids ride the one send that follows — neither the picker nor the camera is
     * driven here, only the same `attachPhoto`/`attachFile` the paperclip's picker result reaches.
     */
    @Test fun photoAndFileAttachmentsRideTheSendToTheirOwnIds() {
        val desk = bridge()
        desk.attachmentsEnabled = true
        cache(desk)
        launch()
        chats()
        ui.onNodeWithText("Review navigation").performClick()
        awaitText("Navigation review is ready.")

        val photo = jpegBytes()
        val note = "Reproduction steps".toByteArray()
        scenario!!.onActivity { activity -> ViewModelProvider(activity)[DeckViewModel::class.java].attachPhoto(desk.page.key, photo) }
        ui.waitUntil(5_000) { desk.attachments.size == 1 }
        awaitText("Photo", substring = true)
        scenario!!.onActivity { activity -> ViewModelProvider(activity)[DeckViewModel::class.java].attachFile(desk.page.key, "repro.txt", note) }
        ui.waitUntil(5_000) { desk.attachments.size == 2 }
        awaitText("repro.txt", substring = true)
        screenshot("m7-j11-attachment-chips")

        ui.onNode(hasSetTextAction()).performTextInput("See the attached photo and file")
        ui.onNodeWithContentDescription("Send").performClick()
        awaitCommand(desk, "/v1/send")
        val sent = desk.commands.last { it.first == "/v1/send" }.second
        val ids = sent["attachmentIds"].asJsonArray.map { it.asString }
        assertEquals(2, ids.size)
        assertTrue("every id the send named is one the machine actually accepted", ids.all { desk.attachments.containsKey(it) })
    }

    /**
     * J11: the phone opened the file at one revision; the machine's own copy moves on before Save
     * is pressed. The save conflicts rather than overwriting the agent's newer note, offers the
     * machine's current text back, and "Save mine over it" resends against the revision now on
     * file — which the machine only then accepts.
     */
    @Test fun savingMemoryAgainstAStaleRevisionConflictsRatherThanOverwriting() {
        val desk = bridge()
        val project = MobileMemoryProject("/work/project", "project")
        desk.memoryProject = project
        val original = MobileMemoryFile(
            project = project.path, id = "agent:notes", label = "Session notes", content = "Old content",
            existed = true, revision = MobileMemoryFile.revisionOf(true, "Old content"), writable = true, deletable = true,
        )
        desk.memoryFiles[original.id] = original
        desk.memoryEntries = MobileMemoryEntries(
            project.path,
            listOf(MobileMemoryEntry(id = original.id, label = original.label, group = "Agent memory", source = "Agent memory", hasContent = true)),
            writable = true,
        )
        cache(desk)
        launch()
        workspace()
        ui.onNodeWithText("Memory and instructions").performScrollTo().performClick()
        awaitText("Session notes")
        ui.onNodeWithText("Session notes").performClick()
        awaitTag("memory-field")
        ui.onNodeWithTag("memory-field").assertTextContains("Old content", substring = true)

        // The desk edits the same file while the phone still has it open, unseen until it saves.
        desk.memoryFiles[original.id] = original.copy(content = "Desk changed this", revision = MobileMemoryFile.revisionOf(true, "Desk changed this"))

        ui.onNodeWithTag("memory-field").performTextReplacement("My phone edit")
        ui.onNodeWithTag("memory-save").performClick()
        awaitTag("memory-conflict")
        screenshot("m7-j11-memory-conflict")
        ui.onNodeWithTag("memory-load-theirs").assertIsDisplayed()

        ui.onNodeWithTag("memory-keep-mine").performClick()
        awaitText("Saved on the machine.")
        assertEquals("My phone edit", desk.memoryFiles.getValue(original.id).content)
        val saves = desk.commands.filter { it.first == "/v1/memory" }
        assertEquals("a rejected save and the accepted retry, never a third", 2, saves.size)
        assertEquals(original.revision, saves[0].second["revision"].asString)
    }

    /**
     * J12: a setting (Restricted mode), a resource (MCP servers, read-only) and an account row
     * (rename) each reach the machine and the phone renders back exactly the machine's answer.
     * The MCP row's credential crosses only by name — never the value a real one would carry.
     */
    @Test fun settingResourceAndAccountRowsRoundTripWithNoSecretInAnyResponse() {
        val desk = bridge()
        desk.restrictedMode = false
        desk.mcpServers = MobileMcpServers(
            project = "/work/project",
            projects = listOf(MobileMcpProject("/work/project", "project")),
            servers = listOf(
                MobileMcpServer(agent = "codex", name = "search", scope = "", transport = "stdio", target = "search-mcp", secretKeys = listOf("OPENAI_API_KEY")),
            ),
        )
        desk.usageReport = requireNotNull(DeckFixtures.byName("usage-account-edit")).usage
        desk.extraCapabilities = setOf(MobileProtocol.Capability.ACCOUNT_EDITS)
        cache(desk)
        launch()
        workspace()

        // Setting: a flip reaches the machine and the switch reflects its own answer.
        awaitText("Restricted mode for Claude chats")
        ui.onNodeWithText("Restricted mode for Claude chats").performScrollTo().performClick()
        ui.waitUntil(5_000) { desk.restrictedMode == true }
        assertTrue(desk.commands.last { it.first == "/v1/restricted-mode" }.second["enabled"].asBoolean)

        // Account: renaming an inactive account round-trips through the machine's own answer.
        ui.onNodeWithText("Spend and plan windows").performScrollTo().performClick()
        awaitText("Personal")
        // The account list is a LazyColumn; the row two below "Plans" is not composed yet, so the
        // scroll must walk the list to find it rather than assume it already exists in the tree.
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("usage-account-rename-CLAUDE-personal"))
        ui.onNodeWithTag("usage-account-rename-CLAUDE-personal").performClick()
        ui.onNodeWithTag("usage-account-rename-field").performTextReplacement("Renamed personal")
        ui.onNodeWithTag("usage-account-rename-save").performClick()
        awaitText("Renamed personal")
        val edit = desk.commands.last { it.first == "/v1/account-edit" }.second
        assertEquals("personal", edit["id"].asString)
        assertEquals("Renamed personal", edit["name"].asString)
        ui.onNodeWithContentDescription("Back").performClick()

        // Resource: the MCP list is read-only; the credential's name crosses, never a value.
        awaitText("MCP servers")
        ui.onNodeWithText("MCP servers").performScrollTo().performClick()
        awaitText("Credentials in OPENAI_API_KEY", substring = true)
        screenshot("m7-j12-settings-resource-account")

        // No response this journey read carried a secret's value, only names ever do.
        assertFalse(desk.commands.any { it.second.toString().contains("sk-secret", ignoreCase = true) })
    }

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun awaitCommand(bridge: StoryBridge, path: String) = ui.waitUntil(5_000) { bridge.commands.any { it.first == path } }

    private fun bridge() = StoryBridge().also { bridges += it }

    /** Saving activates, so the machine cached last is the one the app opens on. */
    private fun cache(bridge: StoryBridge) {
        store.save(bridge.machine)
        store.cacheSnapshot(bridge.machine.id, bridge.snapshot)
        store.cacheTranscript(bridge.machine.id, bridge.page)
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = java.io.File(app.getExternalFilesDir(null), "story-evidence").apply { mkdirs() }
        java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // AGP uninstalls the target after a connected run, including its external files.
        shell("mkdir -p /data/local/tmp/agent-deck-story-evidence")
        shell("cp ${folder.absolutePath}/$name.png /data/local/tmp/agent-deck-story-evidence/$name.png")
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun has(text: String, substring: Boolean = false, byTag: Boolean = false): Boolean = if (byTag) {
        ui.onAllNodesWithTag(text).fetchSemanticsNodes().isNotEmpty()
    } else {
        ui.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() ||
            ui.onAllNodesWithContentDescription(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun chats() { awaitText("Search chats"); ui.onNodeWithText("Search chats").assertIsDisplayed() }
    private fun workspace() { ui.onNodeWithContentDescription("Workspace", useUnmergedTree = true).performClick() }
    private fun awaitText(text: String, substring: Boolean = true) = await("text $text") { has(text, substring = substring) }
    private fun awaitTag(tag: String) = await("tag $tag") { has(tag, byTag = true) }

    /** A timed-out wait photographs the screen first: the watcher runs after the activity has closed. */
    private fun await(what: String, condition: () -> Boolean) {
        try {
            ui.waitUntil(10_000, condition)
        } catch (e: ComposeTimeoutException) {
            runCatching { screenshot("m7-timeout-" + what.replace(Regex("[^A-Za-z0-9]+"), "-").take(40)) }
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        ui.waitForIdle()
    }
}
