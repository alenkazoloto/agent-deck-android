package dev.agentdeck.companion

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobileDecisionRequest
import com.github.claudeagents.core.mobile.MobilePendingHandoff
import com.github.claudeagents.core.mobile.MobilePush
import com.google.gson.JsonParser
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.NotifyTrigger
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.notify.NotifyReceiver
import dev.agentdeck.companion.push.PushRegistration
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.LocalNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PLAN-SPEND-HANDOFF-APPROVAL M2: the desk's "Ask before handing off" answered from the phone —
 * the transcript card, and the push's two shade buttons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp")
class HandoffAskTest {
    @get:Rule
    val compose = createComposeRule()

    private val decided = mutableListOf<Pair<String, String>>()
    private val ask = MobilePendingHandoff("handoff-1", "Soft spend limit reached (\$5.12 of \$5.00). Ask the agent to hand off?")

    private fun show(canDecide: Boolean = true) {
        val state = DeckFixtures.byName("convo-handoff")!!
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ConversationScreen(
                        target = state.screen as Screen.Conversation,
                        page = state.transcript!!,
                        loading = false,
                        cached = false,
                        draft = "",
                        notice = null,
                        onDraft = {},
                        onSend = { _, _ -> },
                        onStop = {},
                        onDismissNotice = {},
                        canDecide = canDecide,
                        onDecide = { id, decision -> decided += id to decision },
                        decideFailures = 0,
                    )
                }
            }
        }
    }

    @Test
    fun `the card asks the desk's question and each button names the ask`() {
        show()
        compose.onNodeWithText(ask.question).performScrollTo().assertExists()
        compose.onNodeWithText("Hand off now").performScrollTo().performClick()
        assertEquals(listOf("handoff-1" to MobileDecisionRequest.HAND_OFF), decided)
        compose.onNodeWithText("Keep going").assertIsNotEnabled()
    }

    @Test
    fun `keep going sends its own verb`() {
        show()
        compose.onNodeWithText("Keep going").performScrollTo().performClick()
        assertEquals(listOf("handoff-1" to MobileDecisionRequest.KEEP_GOING), decided)
    }

    @Test
    fun `no card is drawn for a phone the owner has not let decide`() {
        show(canDecide = false)
        compose.onNodeWithTag("handoff-card").assertDoesNotExist()
    }

    @Test
    fun `the push posts the question with the two answers and no reply`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)

        PushRegistration.show(app, MobilePush.Payload(MobilePush.Trigger.HANDOFF_ASK, listOf("k"), 1, handoff = ask))

        val notification = shadowOf(manager).allNotifications.single { it.channelId == NotifyTrigger.HANDOFF_ASK.id }
        assertEquals(ask.question, notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(listOf("Hand off now", "Keep going"), notification.actions.map { it.title.toString() })
        val intent = shadowOf(notification.actions.first().actionIntent).savedIntent
        assertEquals(NotifyReceiver.ACTION_HAND_OFF, intent.action)
        assertEquals("handoff-1", intent.getStringExtra(NotifyReceiver.EXTRA_REQUEST_ID))
        assertEquals(MobileDecisionRequest.KEEP_GOING, NotifyReceiver.handoffDecision(NotifyReceiver.ACTION_KEEP_GOING))
    }

    @Test
    fun `it is on by default, including for a phone that saved its choices before the row existed`() {
        assertTrue(NotifyTrigger.HANDOFF_ASK in AppSettings().triggers)
        val upgraded = JsonParser.parseString("""{"v":1,"triggers":["needs-you"],"retryLoopOffered":true}""").asJsonObject
        assertEquals(setOf(NotifyTrigger.NEEDS_YOU, NotifyTrigger.HANDOFF_ASK), AppSettings.fromJson(upgraded).triggers)
        val off = AppSettings(triggers = setOf(NotifyTrigger.NEEDS_YOU))
        assertFalse(NotifyTrigger.HANDOFF_ASK in AppSettings.fromJson(off.toJson()).triggers)
        val allOff = JsonParser.parseString("""{"v":1,"triggers":[],"retryLoopOffered":true}""").asJsonObject
        assertEquals("every trigger switched off must stay off", emptySet<NotifyTrigger>(), AppSettings.fromJson(allOff).triggers)
    }
}
