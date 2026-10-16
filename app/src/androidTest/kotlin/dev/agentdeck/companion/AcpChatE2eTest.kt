package dev.agentdeck.companion

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.fixture.AcpChatFixtures
import dev.agentdeck.companion.fixture.DeckFixtures
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production Activity → ViewModel → pinned HTTPS callbacks, using only fictional ACP roots. */
@RunWith(AndroidJUnit4::class)
class AcpChatE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var store: SecureStore
    private lateinit var desk: StoryBridge
    private var activity: ActivityScenario<MainActivity>? = null

    @Before fun prepare() {
        LiveLink.of(app).bind(null)
        store = SecureStore(app)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        store.saveSettings(AppSettings(triggers = emptySet(), dynamicColor = false, updateNotices = false))
        store.saveUpdateCheckedAt(System.currentTimeMillis())
        desk = StoryBridge()
    }

    @After fun finish() {
        activity?.close()
        LiveLink.of(app).bind(null)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        desk.close()
    }

    @Test fun questionCollectsEveryAnswerAndSettlesOnlyItsOwnRoot() {
        open("convo-acp-question")
        awaitTag("acp-question-fixture-question")
        assertAcpControlsUnobstructed()
        click("Bounded retries")
        assertTrue("a selection is not a submission", mutations().isEmpty())
        click("Unit tests")
        click("Integration tests")
        click("Answer")
        await("one answer") { mutations().size == 1 }
        val sent = mutations().single().second
        assertEquals(desk.page.key, sent["key"].asString)
        assertEquals(AcpChatFixtures.EPOCH, sent["epoch"].asString)
        assertEquals(AcpChatFixtures.question.decisionToken, sent["decisionToken"].asString)
        val outcome = sent["outcome"].asJsonObject
        assertEquals("answered", outcome["outcome"].asString)
        val answers = outcome["answers"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("policy", "checks"), answers.map { it["questionId"].asString })
        assertEquals(listOf("bounded"), answers[0]["selectedOptionIds"].asJsonArray.map { it.asString })
        assertEquals(setOf("unit", "integration"), answers[1]["selectedOptionIds"].asJsonArray.map { it.asString }.toSet())
        await("answered form removed") { !tag("acp-question-fixture-question") }
        assertEquals("draft survives a decision", requireNotNull(DeckFixtures.byName("convo-acp-question")).drafts[desk.page.key], store.drafts(desk.machine.id)[desk.page.key])
        screenshot("acp-question-answered")
    }

    @Test fun lostDecisionAcknowledgementRefreshesStateWithoutReplayingTheAnswer() {
        desk.loseAcpDecisionAck = true
        open("convo-acp-question")
        awaitTag("acp-question-fixture-question")
        click("Bounded retries")
        click("Unit tests")
        click("Answer")
        await("decision reached the root") { mutations().isNotEmpty() }
        await("state refresh removes settled form") { !tag("acp-question-fixture-question") }
        assertEquals("an uncertain mutation is never replayed", 1, mutations().size)
        assertTrue(desk.page.acp!!.pendingCalls.isEmpty())
        screenshot("acp-decision-lost-ack")
    }

    @Test fun desktopAnswerRemovesThePhoneFormWithoutSendingAnotherAnswer() {
        open("convo-acp-question")
        awaitTag("acp-question-fixture-question")
        click("Bounded retries")
        desk.answerAcpOnDesk()
        await("desk answer observed") { !tag("acp-question-fixture-question") }
        assertTrue(mutations().isEmpty())
        screenshot("acp-question-answered-on-desk")
    }

    @Test fun rejectedPlanSendsItsReasonAndDisappears() {
        open("convo-acp-plan")
        awaitTag("acp-plan-fixture-plan")
        assertAcpControlsUnobstructed()
        ui.onNodeWithTag("acp-plan-reason").performScrollTo().performTextInput("Keep retries manual for this endpoint.")
        click("Reject")
        await("plan answer") { mutations().size == 1 }
        val outcome = mutations().single().second["outcome"].asJsonObject
        assertEquals("rejected", outcome["outcome"].asString)
        assertEquals("Keep retries manual for this endpoint.", outcome["reason"].asString)
        await("plan removed") { !tag("acp-plan-fixture-plan") }
        screenshot("acp-plan-rejected")
    }

    @Test fun modelChangeUsesTheAdvertisedIdAndConfirmedState() {
        open("convo-acp-config")
        awaitTag("acp-config-model")
        assertAcpControlsUnobstructed()
        ui.onNodeWithTag("acp-config-model").performScrollTo().performClick()
        screenshot("acp-config-options")
        click("Precise")
        await("configuration request") { desk.commands.any { it.first == "/v1/acp/config" } }
        val sent = desk.commands.single { it.first == "/v1/acp/config" }.second
        assertEquals("model", sent["configId"].asString)
        assertEquals("precise", sent["value"].asString)
        await("confirmed configuration") { desk.page.acp!!.config.first { it.id == "model" }.currentValue == "precise" }
        await("confirmed model on screen") { text("Model: Precise") }
        ui.onNodeWithTag("acp-config-model").assertTextContains("Precise", substring = true)
        screenshot("acp-config-confirmed")
    }

    @Test fun savedReopenFailureRetainsDraftAndExplicitRetryLoadsHistory() {
        desk.refuseAcpReopen = true
        open("convo-acp-saved")
        awaitTag("acp-saved")
        assertAcpControlsUnobstructed()
        click("Reopen")
        await("load failure") { text("The agent could not load this saved chat.", substring = true) }
        assertTrue("reopen never sends a message", desk.commands.none { it.first == "/v1/send" })
        assertFalse(store.drafts(desk.machine.id)[desk.page.key].isNullOrBlank())
        ui.onNodeWithText("Nothing in this conversation yet").assertDoesNotExist()
        screenshot("acp-reopen-failed")
        desk.refuseAcpReopen = false
        click("Retry")
        await("restored agent history") { text("Saved retry discussion restored.") }
        await("saved controls removed") { !tag("acp-saved") }
        assertEquals(2, desk.commands.count { it.first == "/v1/acp/reopen" })
        screenshot("acp-reopened")
    }

    @Test fun olderHostKeepsTextAndStopAndExplainsWhereTheQuestionCanBeAnswered() {
        open("convo-acp-older", running = true)
        await("IDE decision hint") { text("Answer this question in the IDE.") }
        ui.onNodeWithTag("acp-config-model").assertDoesNotExist()
        ui.onNodeWithTag("acp-question-fixture-question").assertDoesNotExist()
        ui.onNodeWithContentDescription("Stop", substring = false).performScrollTo().performClick()
        await("legacy stop") { desk.commands.any { it.first == "/v1/stop" } }
        ui.onNode(hasSetTextAction()).performTextReplacement("Continue with bounded retries.")
        ui.onNodeWithContentDescription("Send", substring = false).performClick()
        await("legacy send") { desk.commands.any { it.first == "/v1/send" } }
        assertEquals(desk.page.key, desk.commands.last { it.first == "/v1/send" }.second["key"].asString)
        assertTrue("no unsupported mutation attempted", desk.commands.none { it.first.startsWith("/v1/acp/") })
        screenshot("acp-older-host")
    }

    private fun open(name: String, running: Boolean = false, transcript: com.github.claudeagents.core.mobile.MobileTranscriptPage? = null) {
        val fixture = requireNotNull(DeckFixtures.byName(name))
        desk.page = (transcript ?: requireNotNull(fixture.transcript)).copy(running = running)
        desk.rows = emptyList()
        desk.acpSessions = requireNotNull(fixture.snapshot).acpSessions
        desk.rememberedAcpSessions = requireNotNull(fixture.snapshot).rememberedAcpSessions
        desk.extraCapabilities = requireNotNull(fixture.hello).capabilities.toSet()
            .intersect(setOf("acp-remembered", "acp-config", "acp-vendor-decisions"))
        store.save(desk.machine)
        store.cacheSnapshot(desk.machine.id, desk.snapshot)
        store.cacheTranscript(desk.machine.id, desk.page)
        store.saveDrafts(desk.machine.id, fixture.drafts)
        store.saveScreen(Navigation.toJson(fixture.screen))
        activity = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        ui.waitForIdle()
    }

    @Test fun progressKeepsItsTaskDescriptionsClearOfFloatingJumps() {
        open("convo-acp-progress")
        awaitTag("acp-progress")
        ui.onNodeWithTag("acp-progress").performScrollTo()
        assertAcpControlsUnobstructed()
        ui.onNodeWithText("Checking retry callers in the client").assertIsDisplayed()
    }

    @Test fun latestRemainsAvailableWhenPersistentConfigIsOutsideTheViewport() {
        val page = requireNotNull(DeckFixtures.byName("convo-acp-config")?.transcript).copy(
            turns = (0..29).map { index ->
                com.github.claudeagents.core.mobile.MobileTurn("history-$index", "assistant",
                    "Historical ACP passage $index\n\n" + "Synthetic reading text. ".repeat(15), DeckFixtures.NOW)
            },
        )
        open("convo-acp-config", transcript = page)
        awaitTag("acp-config-model")
        await("initial tail position restored") {
            store.readingPositions(desk.machine.id).conversations[desk.page.key]?.followingLatest == true
        }
        val transcript = ui.onNodeWithTag("conversation-transcript")
        transcript.performScrollToIndex(2)
        transcript.performTouchInput { swipeDown() }
        await("older reading position persisted") {
            store.readingPositions(desk.machine.id).conversations[desk.page.key]?.followingLatest == false
        }
        await("Latest available away from config") {
            ui.onAllNodesWithContentDescription("Latest message", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val reads = desk.transcriptReads.size
        desk.page = desk.page.copy(turns = desk.page.turns + com.github.claudeagents.core.mobile.MobileTurn(
            "fresh-tail", "assistant", "Newest ACP passage after refresh.", DeckFixtures.NOW + 1,
        ))
        await("pending latest transcript fetched") { desk.transcriptReads.size >= reads + 2 }
        assertFalse("refresh must preserve older reading", requireNotNull(store.readingPositions(desk.machine.id).conversations[desk.page.key]).followingLatest)
        ui.onNodeWithContentDescription("Latest message", substring = true).assertIsDisplayed().performClick()
        await("Latest accepts the refreshed passage") { text("Newest ACP passage after refresh.") }
        ui.onNodeWithText("Newest ACP passage after refresh.").assertIsDisplayed()
        awaitTag("acp-config-model")
        ui.onNodeWithTag("acp-config-model").assertIsDisplayed()
        assertAcpControlsUnobstructed()
    }

    private fun assertAcpControlsUnobstructed() {
        ui.onNodeWithContentDescription("Oldest loaded message").assertDoesNotExist()
        ui.onNodeWithContentDescription("Latest message", substring = true).assertDoesNotExist()
        ui.onNodeWithTag("composer-run-pills").assertDoesNotExist()
    }

    private fun mutations() = desk.commands.filter { it.first == "/v1/acp/vendor-decision" }
    private fun click(label: String) = ui.onNodeWithText(label, substring = false).performScrollTo().performClick()
    private fun tag(value: String) = ui.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty()
    private fun text(value: String, substring: Boolean = false) = ui.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun awaitTag(value: String) = await(value) { tag(value) }
    private fun await(description: String, condition: () -> Boolean) {
        try { ui.waitUntil(20_000, condition) }
        catch (failure: ComposeTimeoutException) {
            screenshot("acp-timeout-" + description.replace(Regex("[^A-Za-z0-9]+"), "-").take(35))
            throw AssertionError(description, failure)
        }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val target = java.io.File(app.getExternalFilesDir(null), "$name.png")
        target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        shell("mkdir -p /data/local/tmp/agent-deck-story-evidence")
        shell("cp ${target.absolutePath} /data/local/tmp/agent-deck-story-evidence/$name.png")
    }

    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
