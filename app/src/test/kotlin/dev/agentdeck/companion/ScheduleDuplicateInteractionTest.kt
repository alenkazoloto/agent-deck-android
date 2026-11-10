package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileScheduleEditDetail
import com.github.claudeagents.core.mobile.MobileScheduledRow
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.ScheduleDuplicate
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduledScreen
import dev.agentdeck.companion.ui.openingTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's "Duplicate…" on a scheduled prompt: a button on a row that starts a chat and the
 * create dialog's opening target. What the copy sends is [ScheduleDuplicateFlowTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduleDuplicateInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixture = DeckFixtures.byName("scheduled")!!
    private val newChatRow = MobileScheduledRow(
        "s-new", "Nightly dependency audit", "/Users/dev/Plugin", null, DeckFixtures.NOW + 3_600_000L,
        MobileScheduledRow.QUEUED, true, cadence = "Daily at 09:00",
    )
    private val chatRow = MobileScheduledRow(
        "s-chat", "Continue the migration", "/Users/dev/Plugin", "abc", DeckFixtures.NOW + 3_600_000L,
        MobileScheduledRow.QUEUED, false,
    )
    private val runningRow = newChatRow.copy(id = "s-run", state = MobileScheduledRow.RUNNING)

    private val detail = MobileScheduleEditDetail(
        "s-new", "Nightly dependency audit", "/Users/dev/Plugin", null, DeckFixtures.NOW + 3_600_000L,
        0, "09:00", "sonnet", AgentVendor.CLAUDE.name, "work", null, "Europe/Amsterdam", true, true,
        effort = "high", permissionMode = "plan",
    )

    private val asked = mutableListOf<String>()

    private fun show(
        rows: List<MobileScheduledRow> = listOf(newChatRow, chatRow, runningRow),
        canCreate: Boolean = true,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ScheduledScreen(
                        rows = rows,
                        loading = false,
                        canCreate = canCreate,
                        projects = listOf("/Users/dev/Other", "/Users/dev/Plugin"),
                        hello = fixture.hello,
                        draft = "",
                        onDraft = {},
                        onRefresh = {},
                        onCreate = { _, _, _, _ -> },
                        onCommand = { _, _, _ -> },
                        onDuplicate = { asked.add(it) },
                        vendors = listOf(AgentVendor.CLAUDE, AgentVendor.CODEX),
                    )
                }
            }
        }
    }

    @Test
    fun `only a queued row that starts a chat offers Duplicate, and it names the row it copies`() {
        show()
        compose.onAllNodesWithContentDescription("Duplicate").assertCountEquals(1)
        compose.onNodeWithContentDescription("Duplicate").performClick()
        compose.runOnIdle { assertEquals(listOf("s-new"), asked) }
    }

    @Test
    fun `a machine that cannot take a scheduled prompt offers no Duplicate`() {
        show(canCreate = false)
        compose.onAllNodesWithContentDescription("Duplicate").assertCountEquals(0)
    }

    /** The dialog itself cannot be idled under Robolectric (its focused text field), so its opening target is read here. */
    @Test
    fun `the create dialog opens on the source's agent, account and run choices in a project that is open`() {
        val projects = listOf("/Users/dev/Other", "/Users/dev/Plugin")
        val vendors = listOf(AgentVendor.CLAUDE, AgentVendor.CODEX)
        assertEquals(
            NewChatTarget(
                "/Users/dev/Plugin", AgentVendor.CLAUDE, model = "sonnet", accountId = "work",
                effort = "high", permissionMode = "plan",
            ),
            openingTarget(projects, vendors, null, ScheduleDuplicate.target(detail)),
        )
        // A source whose project the IDE has since closed falls back to the first open one.
        assertEquals(
            "/Users/dev/Other",
            openingTarget(projects, vendors, null, ScheduleDuplicate.target(detail.copy(projectPath = "/gone"))).projectPath,
        )
        // Its agent is no longer offered: the dialog opens on the one that is, not on a stale pick.
        assertEquals(
            AgentVendor.CODEX,
            openingTarget(projects, listOf(AgentVendor.CODEX), null, ScheduleDuplicate.target(detail)).vendor,
        )
    }

    @Test
    fun `a source that continues a chat or runs on an unknown agent has no copy`() {
        assertNull(ScheduleDuplicate.target(detail.copy(sessionId = "abc")))
        assertNull(ScheduleDuplicate.target(detail.copy(vendor = "ACP")))
        assertEquals(false, ScheduleDuplicate.repeats(detail.copy(repeatAtTime = null)))
        assertEquals(true, ScheduleDuplicate.repeats(detail.copy(repeatEveryMs = 900_000, repeatAtTime = null)))
    }
}
