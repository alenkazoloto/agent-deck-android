package dev.agentdeck.companion

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobileChangeRequestReview
import dev.agentdeck.companion.data.ReviewSheet
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ChangeRequestReviewView
import dev.agentdeck.companion.ui.ReviewActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk review dialog's heading verbs on the phone (M4, P22): Copy checkout command puts the
 * machine's command on the clipboard and is absent where the service has none (Bitbucket), and
 * Re-read asks the machine again, waiting while a post or a reading is still running.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ChangeRequestHeaderInteractionTest {
    @get:Rule val compose = createComposeRule()

    private var reloads = 0
    private val sheet = mutableStateOf(ReviewSheet("/work/app", "/work/app-wt", "wt-2", review("gh pr checkout 42")))

    private fun review(command: String?) = MobileChangeRequestReview(
        "/work/app", "/work/app-wt", "wt-2", requestId = "42", title = "Make the clock injectable",
        forge = "GitHub", checkoutCommand = command,
    )

    private fun show() {
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                ChangeRequestReviewView(
                    sheet.value,
                    ReviewActions(
                        onReplyText = { _, _ -> }, onReply = {}, onResolve = { _, _ -> }, onVerdictText = {},
                        onSubmit = {}, onReload = { reloads++ }, onBack = {}, onDismiss = {},
                    ),
                )
            }
        }
    }

    @Test fun `copy checkout command puts the machine's command on the clipboard`() {
        show()
        compose.onNodeWithTag("review-copy-checkout", useUnmergedTree = true).performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals("gh pr checkout 42", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test fun `a service without a checkout command shows no copy button`() {
        sheet.value = sheet.value.copy(review = review(null))
        show()
        compose.onNodeWithTag("review-copy-checkout", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `re-read asks the machine again, and waits while a post is running`() {
        show()
        compose.onNodeWithTag("review-reload", useUnmergedTree = true).assertIsEnabled().performClick()
        assertEquals(1, reloads)
        sheet.value = sheet.value.copy(working = "reply")
        compose.onNodeWithTag("review-reload", useUnmergedTree = true).assertIsNotEnabled()
        sheet.value = sheet.value.copy(working = null, reloading = true)
        compose.onNodeWithTag("review-reload", useUnmergedTree = true).assertIsNotEnabled()
    }
}
