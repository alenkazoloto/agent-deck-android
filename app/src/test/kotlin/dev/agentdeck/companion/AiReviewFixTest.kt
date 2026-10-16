package dev.agentdeck.companion

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobileAiReviewFinding
import com.github.claudeagents.core.mobile.MobileAiReviewReport
import com.github.claudeagents.core.mobile.MobileAiReviewState
import dev.agentdeck.companion.data.AiReviewSheet
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.AiReviewDialog
import dev.agentdeck.companion.ui.FIX_LABEL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Codex review findings on the phone (M4, P20) carry the desk marker's "Ask the agent to fix this
 * finding": the wrench adds the machine's request under the chat's draft — kept, not replaced, and
 * saved like any typing — and sends nothing. A finding from a machine without the text shows no wrench.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiReviewFixTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var app: Application
    private lateinit var store: SecureStore

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
    }

    @After fun tearDown() {
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `the fix request goes under the unsent draft, closes the sheet and survives a restart`() {
        val model = DeckViewModel(app)
        model.editDraft(KEY, "Also rename stamp().  \n")
        model.editDraft(OTHER, "another chat")
        model.aiReview.open(KEY)

        model.askToFix(FIXABLE)

        assertEquals("Also rename stamp().\n\n${FIXABLE.fixPrompt}", model.draft(KEY))
        assertEquals("another chat", model.draft(OTHER))
        assertNull("the composer holding it is in view", model.aiReview.sheet.value)
        assertEquals("Also rename stamp().\n\n${FIXABLE.fixPrompt}", DeckViewModel(app).state.value.drafts[KEY])
    }

    @Test fun `an empty draft becomes the request alone, and a finding without one changes nothing`() {
        val model = DeckViewModel(app)
        model.aiReview.open(KEY)
        model.askToFix(OLD_HOST)
        assertEquals("", model.draft(KEY))

        model.askToFix(FIXABLE)
        assertEquals(FIXABLE.fixPrompt, model.draft(KEY))
    }

    @Test fun `only a finding with a fix request shows the wrench, and it hands that finding over`() {
        val asked = mutableListOf<MobileAiReviewFinding>()
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                AiReviewDialog(
                    sheet = AiReviewSheet(KEY, state = MobileAiReviewState(KEY, report = MobileAiReviewReport(listOf(FIXABLE, OLD_HOST), "2 findings."))),
                    onDismiss = {}, onEdit = { _, _, _ -> }, onStart = {}, onStop = {},
                    onFix = { asked += it },
                )
            }
        }

        compose.onAllNodesWithContentDescription(FIX_LABEL).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(FIX_LABEL)[0].performClick()

        assertEquals(listOf(FIXABLE), asked)
    }

    private companion object {
        const val KEY = "codex:/repo:thread"
        const val OTHER = "codex:/repo:other"
        val FIXABLE = MobileAiReviewFinding(
            "Midnight rollover formats the previous day", "Truncate after the conversion.", 1, 82, "src/Stamps.kt", 41, 47,
            fixPrompt = "Fix this Codex review finding in /repo/src/Stamps.kt lines 41–47:\nP1 — Midnight rollover formats the previous day",
        )
        val OLD_HOST = MobileAiReviewFinding("Clock is read twice", "", 2, 64, "src/Stamps.kt", 58, 58)
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
