package dev.agentdeck.companion

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileSessionExport
import dev.agentdeck.companion.data.ConversationShare
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Share (M3): the row sheet offers it only on a machine serving `session-export`, and the file
 * that leaves the app is one plain name in the app's private export cache, granted to the
 * recipient by URI, and gone after a day.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ShareConversationTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun show(canShare: Boolean, shared: MutableList<String>) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = MobileFleetSnapshot(listOf(row("a", "Fix the parser")), 0, emptyList(), null, DeckFixtures.NOW),
                        filter = FleetFilter(),
                        sort = FleetSort.RECENT,
                        refreshing = false,
                        snoozed = emptyMap(),
                        openKey = null,
                        onFilter = {}, onSort = {}, onRefresh = {}, onOpen = {}, onSnooze = {}, onStop = {},
                        canShare = canShare,
                        onShare = { shared += it.key },
                    )
                }
            }
        }
    }

    @Test
    fun `the sheet shares the row it was opened on`() {
        val shared = mutableListOf<String>()
        show(canShare = true, shared)

        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Share the conversation").performClick()

        compose.runOnIdle { assertEquals(listOf("a"), shared) }
    }

    @Test
    fun `an older machine is not offered share`() {
        show(canShare = false, mutableListOf())

        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Copy the title").assertExists()
        compose.onNodeWithText("Share the conversation").assertDoesNotExist()
    }

    @Test
    fun `a name from the wire cannot reach outside its directory`() {
        assertEquals("2026-09-23-110000-fix-it.txt", ConversationShare.safeName("2026-09-23-110000-fix-it.txt"))
        assertEquals("passwd.txt", ConversationShare.safeName("../../etc/passwd"))
        assertEquals("evil.txt", ConversationShare.safeName("..\\..\\evil.txt"))
        assertEquals("conversation.txt", ConversationShare.safeName(".."))
        assertEquals("conversation.txt", ConversationShare.safeName(""))
        assertEquals("hidden.txt", ConversationShare.safeName(".hidden"))
        assertEquals("r-sum-.txt", ConversationShare.safeName("résumé"))
    }

    @Test
    fun `each share gets its own file and a day-old one is swept`() {
        val root = ConversationShare.dir(context).apply { deleteRecursively() }
        val export = MobileSessionExport("k", "chat.txt", "> hello\n", truncated = false)

        val first = ConversationShare.write(root, export, NOW)
        val second = ConversationShare.write(root, export, NOW)
        assertNotEquals("a second share never rewrites a file a recipient may be reading", first, second)
        assertEquals("> hello\n", second.readText())
        assertTrue(first.canonicalPath.startsWith(root.canonicalPath + File.separator))

        first.parentFile!!.setLastModified(NOW - ConversationShare.RETAIN_MS)
        second.parentFile!!.setLastModified(NOW - 1_000)
        ConversationShare.sweep(root, NOW)
        assertFalse(first.exists())
        assertTrue("an export from within the day stays for its recipient", second.exists())
    }

    @Test
    fun `the recipient is granted the one file by content URI`() {
        forgetFileProviderRoots()
        val file = ConversationShare.write(
            ConversationShare.dir(context),
            MobileSessionExport("k", "chat.txt", "text", truncated = false),
            NOW,
        )
        val chooser = ConversationShare.chooser(context, file, "Fix the parser")
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!

        assertEquals("content", uri.scheme)
        assertFalse("the path never leaves the app", uri.toString().contains(context.cacheDir.path))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals("Fix the parser", send.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    private fun row(key: String, title: String) = MobileFleetRow(
        key = key, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
    )

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
