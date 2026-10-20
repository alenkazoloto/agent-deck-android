package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.claudeagents.core.WaitingReason
import com.github.claudeagents.core.mobile.MobileAcpAnswerOption
import com.github.claudeagents.core.mobile.MobileAcpCall
import com.github.claudeagents.core.mobile.MobileAcpChat
import com.github.claudeagents.core.mobile.MobileAcpChoice
import com.github.claudeagents.core.mobile.MobileAcpConfig
import com.github.claudeagents.core.mobile.MobileAcpOutcome
import com.github.claudeagents.core.mobile.MobileAcpQuestion
import com.github.claudeagents.core.mobile.MobileAcpSession
import dev.agentdeck.companion.ui.AcpChatActions
import dev.agentdeck.companion.ui.AcpChatControls
import dev.agentdeck.companion.ui.AgentDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AcpChatControlsInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val answers = mutableListOf<Triple<String, MobileAcpCall, MobileAcpOutcome>>()
    private val single = MobileAcpQuestion("question-id", "Which path?", listOf(
        MobileAcpAnswerOption("exact-a", "First path"), MobileAcpAnswerOption("exact-b", "Second path")))
    private val question = MobileAcpCall("call-id", "token-one", "question", questions = listOf(single))
    private val actions = AcpChatActions(canDecide = true, onDecision = { epoch, call, answer ->
        answers += Triple(epoch, call, answer)
        null
    })

    private fun show(chat: MobileAcpChat?, offer: AcpChatActions = actions) {
        compose.setContent { AgentDeckTheme(dynamic = false) { AcpChatControls(chat, offer, "machine/row") } }
    }

    @Test fun `single selection submits only after Answer with exact wire identities`() {
        show(MobileAcpChat("epoch-one", pendingCalls = listOf(question)))
        compose.onNodeWithText("Answer").assertIsNotEnabled()
        compose.onNodeWithText("First path").performClick()
        compose.runOnIdle { assertEquals(0, answers.size) }
        compose.onNodeWithText("Second path").performClick()
        compose.onNodeWithText("Answer").performClick()
        compose.onNodeWithText("Answer").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("epoch-one", answers.single().first)
            assertEquals("token-one", answers.single().second.decisionToken)
            assertEquals(mapOf("question-id" to listOf("exact-b")), answers.single().third.answers)
        }
    }

    @Test fun `multi selection preserves option IDs and failed answers retain selections`() {
        val multi = question.copy(questions = listOf(single.copy(allowMultiple = true)))
        show(MobileAcpChat("epoch", pendingCalls = listOf(multi)), AcpChatActions(canDecide = true,
            onDecision = { epoch, call, answer -> answers += Triple(epoch, call, answer); "Refresh this chat" }))
        compose.onNodeWithText("First path").performClick()
        compose.onNodeWithText("Second path").performClick()
        compose.onNodeWithText("Answer").performClick()
        compose.onNodeWithText("Answer").assertIsEnabled()
        compose.onNodeWithText("Refresh this chat").assertExists()
        compose.runOnIdle { assertEquals(listOf("exact-a", "exact-b"), answers.single().third.answers["question-id"]) }
    }

    @Test fun `zero-choice question keeps explicit Skip without invented answer input`() {
        show(MobileAcpChat("epoch", pendingCalls = listOf(question.copy(questions = listOf(single.copy(options = emptyList()))))))
        compose.onNodeWithText("Answer").assertIsNotEnabled()
        compose.onNodeWithText("Skip").performClick()
        compose.runOnIdle { assertEquals("skipped", answers.single().third.outcome) }
    }

    @Test fun `reused call ID in a new root clears the old selection and token`() {
        val chat = mutableStateOf(MobileAcpChat("epoch-one", pendingCalls = listOf(question)))
        compose.setContent { AgentDeckTheme(dynamic = false) { AcpChatControls(chat.value, actions, "machine/row") } }
        compose.onNodeWithText("First path").performClick()
        compose.runOnIdle { chat.value = MobileAcpChat("epoch-two", pendingCalls = listOf(question.copy(decisionToken = "token-two"))) }
        compose.onNodeWithText("Answer").assertIsNotEnabled()
        compose.onNodeWithText("Second path").performClick()
        compose.onNodeWithText("Answer").performClick()
        compose.runOnIdle {
            assertEquals("epoch-two", answers.single().first)
            assertEquals("token-two", answers.single().second.decisionToken)
        }
    }

    @Test fun `plan rejection carries optional reason without a chat send`() {
        show(MobileAcpChat("epoch", pendingCalls = listOf(MobileAcpCall("plan-id", "plan-token", "plan", plan = "A short plan"))))
        compose.onNodeWithTag("acp-plan-reason").performTextInput("Keep the parser")
        compose.onNodeWithText("Reject").performClick()
        compose.runOnIdle {
            assertEquals("rejected", answers.single().third.outcome)
            assertEquals("Keep the parser", answers.single().third.reason)
        }
    }

    @Test fun `successful config stays usable when confirmed value has not changed`() {
        val config = MobileAcpConfig("agent-model", "Model", "model", "old", listOf(MobileAcpChoice("old", "Old model"), MobileAcpChoice("agent-value", "New model", group = "Agent models")))
        val choices = mutableListOf<List<String>>()
        show(MobileAcpChat("epoch-config", config = listOf(config)), AcpChatActions(canConfigure = true,
            onConfig = { epoch, id, value -> choices += listOf(epoch, id, value); null }))
        compose.onNodeWithText("Model: Old model").performClick()
        compose.onNodeWithText("Agent models").assertExists()
        compose.onNodeWithText("New model").performClick()
        compose.onNodeWithText("Model: Old model").assertIsEnabled()
        compose.onNodeWithText("Model: Old model").performClick()
        compose.onNodeWithText("New model").performClick()
        compose.runOnIdle { assertEquals(List(2) { listOf("epoch-config", "agent-model", "agent-value") }, choices) }
    }

    @Test fun `a single offered config value is informational`() {
        val config = MobileAcpConfig("only-model", "Model", "model", "only", listOf(MobileAcpChoice("only", "Only model")))
        show(MobileAcpChat("epoch", config = listOf(config)), AcpChatActions(canConfigure = true))
        compose.onNodeWithText("Model: Only model").assertExists()
        compose.onNodeWithTag("acp-config-only-model").assertHasNoClickAction()
    }

    @Test fun `saved failure can be retried and old hosts explain where to answer`() {
        var reopened = 0
        show(null, AcpChatActions(remembered = MobileAcpSession("acp:cursor/saved", "Cursor", "/work", "Saved", false, 0,
            remembered = true, canResume = true, reopenError = "Agent unavailable"), waitingReason = WaitingReason.QUESTION,
            onReopen = { reopened++; "Still unavailable" }))
        compose.onNodeWithText("Answer this question in the IDE.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Still unavailable").assertExists()
        compose.onNodeWithText("Retry").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, reopened) }
    }
}
