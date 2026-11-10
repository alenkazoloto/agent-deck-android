package dev.agentdeck.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import dev.agentdeck.companion.data.AfterReset
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.ScheduleWhen
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduleDuePick
import dev.agentdeck.companion.ui.ScheduleDueSelector
import dev.agentdeck.companion.ui.ScheduleTargetPickers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * mobile-todo Scheduling #1: the create dialog offers "After the usage limit resets" for the
 * account the prompt will run on, and posts the desk's due time (reset + 2 min) and window repeat.
 * Driven through the production pills without the dialog window, as [ScheduledActionsInteractionTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduleAfterResetInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = DeckFixtures.NOW
    private val resetAt = now + 90 * 60_000L
    private val fixture = DeckFixtures.byName("scheduled")!!
    private val hello = fixture.hello!!.copy(
        capabilities = fixture.hello!!.capabilities + MobileProtocol.Capability.ACCOUNTS,
        accounts = mapOf(
            AgentVendor.CLAUDE to listOf(MobileScheduleAccountOption("default", "Personal")),
            AgentVendor.CODEX to listOf(
                MobileScheduleAccountOption("codex-default", "ChatGPT"),
                MobileScheduleAccountOption("codex-work", "Work ChatGPT", resetAtMs = resetAt),
            ),
        ),
        activeAccounts = mapOf(AgentVendor.CLAUDE to "default", AgentVendor.CODEX to "codex-default"),
    )

    @Test
    fun `the reset choice follows the picked account and posts the desk's due time and window repeat`() {
        val target = mutableStateOf(NewChatTarget("/Users/dev/Plugin", AgentVendor.CODEX))
        val pick = ScheduleDuePick()
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { now }) {
                AgentDeckTheme {
                    Column {
                        ScheduleTargetPickers(
                            target = target.value,
                            projects = listOf("/Users/dev/Plugin"),
                            vendors = listOf(AgentVendor.CLAUDE, AgentVendor.CODEX),
                            hello = hello,
                            onTarget = { target.value = it },
                        ) { ScheduleDueSelector(pick, hello, target.value) }
                    }
                }
            }
        }
        // The active account has no known reset, so the choice is not offered at all.
        compose.onNodeWithText("In an hour").performClick()
        compose.onNodeWithText("after the limit resets", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Tomorrow morning").performClick()

        compose.onNodeWithText("Account ChatGPT").performClick()
        compose.onNodeWithText("Work ChatGPT", substring = true).performClick()
        compose.onNodeWithText("Tomorrow morning").performClick()
        compose.onNodeWithText("after the limit resets", substring = true).performClick()
        compose.onNodeWithText("after the limit resets", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            val picked = pick.picked(AfterReset.of(hello.accounts[AgentVendor.CODEX]!![1], now))
            assertEquals(resetAt + 2 * 60_000L, picked.dueAtMs(now))
            assertEquals(5 * 60 * 60_000L, picked.repeat().everyMs)
            assertEquals(null, picked.repeat().atTime)
        }

        // Back on an account with no reset: the relative pick it replaced, not a stale reset time.
        compose.onNodeWithText("Account Work ChatGPT", substring = true).performClick()
        compose.onNodeWithText("ChatGPT").performClick()
        compose.onNodeWithText("Tomorrow morning").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ScheduleWhen.TOMORROW_MORNING, pick.picked(null)) }
    }

    @Test
    fun `a drained week repeats weekly and a reset already past is not offered`() {
        val week = AfterReset.of(MobileScheduleAccountOption("a", "A", resetAtMs = resetAt, weeklyLimit = true), now)!!
        assertEquals(7 * 24 * 60 * 60_000L, week.repeat().everyMs)
        assertEquals("Repeat each week", week.repeatLabel())
        assertEquals(null, AfterReset.of(MobileScheduleAccountOption("a", "A", resetAtMs = now - 1), now))
        assertEquals(null, AfterReset.of(MobileScheduleAccountOption("a", "A"), now))
    }
}
