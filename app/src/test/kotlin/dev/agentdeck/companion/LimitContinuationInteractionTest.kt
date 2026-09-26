package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import com.github.claudeagents.core.mobile.MobileUsageAccount
import com.github.claudeagents.core.mobile.MobileUsageReport
import com.github.claudeagents.core.mobile.MobileUsageWindow
import dev.agentdeck.companion.data.AfterReset
import dev.agentdeck.companion.data.LimitContinuation
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.LimitContinuationOffer
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduleDuePick
import dev.agentdeck.companion.ui.ScheduleDueSelector
import dev.agentdeck.companion.ui.ScheduleTargetPickers
import dev.agentdeck.companion.ui.openingTarget
import dev.agentdeck.companion.ui.resetFor
import dev.agentdeck.companion.data.ScheduleWhen
import androidx.compose.foundation.layout.Column
import dev.agentdeck.companion.ui.Times
import dev.agentdeck.companion.ui.UsageScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * mobile-todo Scheduling #1: the desk's "Continue after reset" reached from the phone's own
 * limit surfaces — a drained plan in Usage opens Schedule on that account with the reset picked,
 * and a chat whose account is at its limit offers "Continue at …" into that chat.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LimitContinuationInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val now = DeckFixtures.NOW
    private val resetAt = now + 90 * 60_000L
    private val base = DeckFixtures.byName("scheduled")!!.hello!!
    private val hello = base.copy(
        capabilities = base.capabilities + MobileProtocol.Capability.ACCOUNTS + MobileProtocol.Capability.SCHEDULE_CREATE,
        accounts = mapOf(
            AgentVendor.CLAUDE to listOf(
                MobileScheduleAccountOption("default", "Personal"),
                MobileScheduleAccountOption("work", "Work", resetAtMs = resetAt),
            ),
        ),
        activeAccounts = mapOf(AgentVendor.CLAUDE to "default"),
    )
    private val due = Times.clock(resetAt + AfterReset.MARGIN_MS, now)

    @Test fun `a drained plan in Usage schedules against its own account's reset`() {
        val picked = mutableListOf<Pair<AgentVendor, String>>()
        val report = MobileUsageReport(
            accounts = listOf(
                MobileUsageAccount("default", AgentVendor.CLAUDE, "Personal", listOf(MobileUsageWindow("5-hour", 40))),
                MobileUsageAccount("work", AgentVendor.CLAUDE, "Work",
                    listOf(MobileUsageWindow("5-hour", 100, resetAtMs = resetAt, reached = true))),
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { now }) {
                AgentDeckTheme {
                    UsageScreen(report, loading = false, error = null, onLoad = {}, hello = hello,
                        onScheduleAfterReset = { vendor, id -> picked += vendor to id })
                }
            }
        }
        // One offer: the healthy account has no reset to wait for.
        compose.onNodeWithTag("usage-schedule-after-reset").performScrollTo()
            .assertTextContains("Schedule a prompt for $due…").performClick()
        compose.runOnIdle { assertEquals(listOf(AgentVendor.CLAUDE to "work"), picked) }
    }

    @Test fun `no offer where the machine cannot queue a prompt`() {
        val old = hello.copy(capabilities = hello.capabilities.filterNot { it == MobileProtocol.Capability.SCHEDULE_CREATE })
        assertNull(LimitContinuation.resetOf(old, AgentVendor.CLAUDE, "work", now))
        // The exact account only: an unknown id does not borrow the active account's state.
        assertNull(LimitContinuation.resetOf(hello, AgentVendor.CLAUDE, "gone", now))
        assertEquals(resetAt, LimitContinuation.resetOf(hello, AgentVendor.CLAUDE, "work", now)?.resetAtMs)
    }

    /**
     * Driven through the production pills without the dialog window, which Robolectric never
     * lets settle while it holds a text field (as [ScheduleAfterResetInteractionTest]).
     */
    @Test fun `Schedule opens on the preset account with the reset picked`() {
        val preset = NewChatTarget("", AgentVendor.CLAUDE, accountId = "work")
        val target = openingTarget(listOf("/Users/dev/Plugin"), listOf(AgentVendor.CODEX, AgentVendor.CLAUDE), preset)
        assertEquals(NewChatTarget("/Users/dev/Plugin", AgentVendor.CLAUDE, accountId = "work"), target)
        assertEquals(AgentVendor.CLAUDE, openingTarget(listOf("/p"), listOf(AgentVendor.CODEX, AgentVendor.CLAUDE), null).vendor)
        val pick = ScheduleDuePick.opening(preset)
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { now }) {
                AgentDeckTheme {
                    Column {
                        ScheduleTargetPickers(target, listOf("/Users/dev/Plugin"), listOf(AgentVendor.CLAUDE), hello, {}) {
                            ScheduleDueSelector(pick, hello, target)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("At $due, after the limit resets").assertIsDisplayed()
        assertEquals(resetAt + AfterReset.MARGIN_MS, pick.picked(resetFor(hello, target, now)).dueAtMs(now))
        // Without a preset the dialog opens as it always did.
        assertEquals(ScheduleWhen.IN_AN_HOUR, ScheduleDuePick.opening(null).picked(resetFor(hello, target, now)))
    }

    @Test fun `a chat whose account is at its limit offers to continue there, with the desk's sentence editable`() {
        val row = MobileFleetRow(
            key = "claude:/repo:session", vendor = AgentVendor.CLAUDE, accountId = "work", accountLabel = "Work",
            projectPath = "/repo", projectName = "repo", gitBranch = null, title = "Migrate",
            attention = null, waitingReason = null, lastActivityMs = now, costUsd = 0.0, costKnown = false,
            contextPct = null, messageCount = 1,
        )
        assertNull("a live turn has not been cut off", LimitContinuation.forChat(hello, row, running = true, now))
        val reset = LimitContinuation.forChat(hello, row, running = false, now)!!
        val prompt = mutableStateOf(LimitContinuation.PROMPT)
        val scheduled = mutableListOf<Long>()
        val page = MobileTranscriptPage(
            key = row.key, title = row.title,
            turns = listOf(MobileTurn("t1", "user", "Migrate the schema", now - 60_000)),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = now,
        )
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { now }) {
                AgentDeckTheme {
                    ConversationScreen(
                        target = Screen.Conversation(row.key, row.title, row.vendor, row.projectPath),
                        page = page, loading = false, cached = false, draft = "", notice = null,
                        onDraft = {}, onSend = { _, _ -> }, onStop = {}, onDismissNotice = {},
                        hello = hello,
                        limitContinuation = LimitContinuationOffer(reset, row.accountLabel, prompt.value,
                            onPrompt = { prompt.value = it }, onSchedule = { scheduled += it }),
                    )
                }
            }
        }
        compose.onNodeWithText("Work reached its usage limit", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Continue at $due…").performClick()
        compose.onNodeWithTag("limit-continuation-prompt").assertTextContains(LimitContinuation.PROMPT)
            .performTextReplacement("Finish the migration")
        compose.onNodeWithText("Schedule").performClick()
        compose.runOnIdle {
            assertEquals("Finish the migration", prompt.value)
            assertEquals(listOf(resetAt + AfterReset.MARGIN_MS), scheduled)
        }
    }
}
