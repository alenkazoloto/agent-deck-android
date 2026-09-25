package dev.agentdeck.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.github.claudeagents.core.mobile.MobilePreviewAction
import com.github.claudeagents.core.mobile.MobilePreviewChoice
import com.github.claudeagents.core.mobile.MobilePreviewFrame
import com.github.claudeagents.core.mobile.MobilePreviewProject
import com.github.claudeagents.core.mobile.MobilePreviewProjects
import com.github.claudeagents.core.mobile.MobilePreviewState
import com.github.claudeagents.core.mobile.MobilePreviewView
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.fixture.DeckFixtures
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.PreviewLink
import dev.agentdeck.companion.ui.PreviewSheet
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Resources › "Preview" is the desk's Preview tab as the machine draws it: a picture, the
 * desk's address, frame, scheme and zoom as controls, and one armed tap that adds an element to the chat.
 * The phone sends typed intents and shows the machine's sentences — it decides nothing about the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<MobilePreviewAction>()
    private var views = 0
    private var rev = 0L

    private val project = MobilePreviewProject("/work/shop", "shop")

    /** A stand-in for a page a dev server serves: a header, a heading, a line of copy and a button. */
    private fun jpeg(): String {
        val bitmap = Bitmap.createBitmap(390, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(24, 33, 47)
        canvas.drawRect(0f, 0f, 390f, 44f, paint)
        paint.color = Color.WHITE
        paint.textSize = 18f
        canvas.drawText("Corner Shop", 16f, 29f, paint)
        paint.color = Color.rgb(24, 33, 47)
        paint.textSize = 26f
        canvas.drawText("Spring sale", 16f, 100f, paint)
        paint.textSize = 15f
        paint.color = Color.rgb(99, 112, 132)
        canvas.drawText("Everything for the garden, 20% off this week.", 16f, 130f, paint)
        paint.color = Color.rgb(49, 92, 232)
        canvas.drawRoundRect(16f, 160f, 150f, 204f, 8f, 8f, paint)
        paint.color = Color.WHITE
        paint.textSize = 16f
        canvas.drawText("Buy now", 46f, 188f, paint)
        val out = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun state(open: Boolean = true, unavailable: String? = null) = MobilePreviewState(
        project = project.path, open = open, url = if (open) "http://localhost:3000/" else "", viewport = "PHONE", appearance = "SYSTEM",
        zoomPercent = 120, unavailable = unavailable,
        viewports = listOf(MobilePreviewChoice("FIT", "Fit"), MobilePreviewChoice("PHONE", "Phone — 390 × 844")),
        appearances = listOf(MobilePreviewChoice("SYSTEM", "Match the IDE"), MobilePreviewChoice("DARK", "Dark")),
    )

    private fun picture() = MobilePreviewView(state(), MobilePreviewFrame(++rev, 390, 300, jpeg()))

    private fun show(
        projects: Result<MobilePreviewProjects> = Result.success(MobilePreviewProjects(listOf(project))),
        view: () -> Result<MobilePreviewView> = { Result.success(picture()) },
        act: (MobilePreviewAction) -> Result<MobilePreviewView> = { Result.success(picture()) },
        waitForPicture: Boolean = true,
    ) {
        val link = PreviewLink(
            projects = { projects },
            view = { _, _ -> views++; view() },
            act = { action -> sent += action; act(action) },
        )
        compose.setContent { AgentDeckTheme(dark = false, dynamic = false) { PreviewSheet(link, onDismiss = {}, refreshMs = Long.MAX_VALUE) } }
        compose.waitForIdle()
        if (waitForPicture) awaitPicture()
    }

    /** The picture is decoded off the main thread, so idle is not enough: wait for it to be drawn. */
    private fun awaitPicture() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("preview-picture").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the machine's picture and the desk's address are shown`() {
        show()

        compose.onNodeWithTag("preview-picture").assertIsDisplayed()
        compose.onNodeWithText("http://localhost:3000/").assertIsDisplayed()
        compose.onNodeWithText("120%").assertIsDisplayed()
        compose.onNodeWithTag("preview-dialog").captureRoboImage("build/outputs/p34/preview.png", RECORD)
        assertEquals(1, views)
    }

    @Test
    fun `every control sends the intent it names and the desk's choices are all visible`() {
        show()

        compose.onNodeWithContentDescription("Reload the page").performClick()
        compose.onNodeWithContentDescription("Zoom out").performClick()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.onNodeWithContentDescription("Reset zoom to 100%, now 120%").performClick()
        compose.onNodeWithText("Fit").performClick()
        compose.onNodeWithText("Dark").performClick()

        assertEquals(
            listOf(
                MobilePreviewAction.RELOAD to null,
                MobilePreviewAction.ZOOM to "-1",
                MobilePreviewAction.ZOOM to "1",
                MobilePreviewAction.ZOOM_RESET to null,
                MobilePreviewAction.VIEWPORT to "FIT",
                MobilePreviewAction.APPEARANCE to "DARK",
            ),
            sent.map { it.action to it.value },
        )
        // The frame the desk is already on is not asked for again.
        compose.onNodeWithText("Phone").assertIsDisplayed()
    }

    @Test
    fun `a typed address is sent as typed and the machine decides whether it opens`() {
        show(act = { Result.success(picture().copy(notice = "Only pages on this machine, or on the page's own site, open from a phone. Open other addresses in the IDE.")) })

        compose.onNodeWithTag("preview-address").performTextClearance()
        compose.onNodeWithTag("preview-address").performTextInput("https://evil.example/")
        compose.onNodeWithTag("preview-address").performImeAction()
        compose.waitForIdle()

        assertEquals(MobilePreviewAction.OPEN, sent.single().action)
        assertEquals("https://evil.example/", sent.single().url)
        compose.onNodeWithText("Only pages on this machine, or on the page's own site, open from a phone. Open other addresses in the IDE.").assertIsDisplayed()
    }

    @Test
    fun `a tap on the picture does nothing until an element is being picked, then names its fraction and picture`() {
        show(act = { Result.success(picture().copy(notice = "Added #buy > button to the chat draft on this machine.")) })

        compose.onNodeWithTag("preview-picture").performTouchInput { click(Offset(width / 2f, height / 4f)) }
        assertEquals("a tap on an unarmed picture reached the machine", 0, sent.size)

        compose.onNodeWithContentDescription("Add an element to the chat").performClick()
        compose.onNodeWithText("Tap the element on the picture to add it to the chat.").assertIsDisplayed()
        compose.onNodeWithTag("preview-picture").performTouchInput { click(Offset(width / 2f, height / 4f)) }
        compose.waitForIdle()

        val tap = sent.single()
        assertEquals(MobilePreviewAction.ANNOTATE, tap.action)
        assertEquals(0.5, tap.x!!, 0.02)
        assertEquals(0.25, tap.y!!, 0.02)
        assertEquals("the tap must quote the picture it was made on", 1L, tap.rev)
        compose.onNodeWithText("Added #buy > button to the chat draft on this machine.").assertIsDisplayed()
        // One tap per arming: the next tap is not an element pick.
        compose.onNodeWithTag("preview-picture").performTouchInput { click(Offset(10f, 10f)) }
        assertEquals(1, sent.size)
    }

    @Test
    fun `a stale tap is answered with a fresh picture and the next tap quotes that one`() {
        var taps = 0
        show(act = { action ->
            if (action.action == MobilePreviewAction.ANNOTATE && taps++ == 0) Result.success(picture().copy(notice = MobilePreviewAction.STALE))
            else Result.success(picture().copy(notice = "Added #buy to the chat draft on this machine."))
        })

        compose.onNodeWithContentDescription("Add an element to the chat").performClick()
        compose.onNodeWithTag("preview-picture").performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.waitForIdle()
        compose.onNodeWithText(MobilePreviewAction.STALE).assertIsDisplayed()
        assertEquals(1L, sent.single().rev)

        // Disarmed by the tap: arm again, tap on the fresh picture.
        compose.onNodeWithContentDescription("Add an element to the chat").performClick()
        compose.onNodeWithTag("preview-picture").performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.waitForIdle()

        assertEquals("the second tap must quote the picture the stale answer brought", 2L, sent[1].rev)
        compose.onNodeWithText("Added #buy to the chat draft on this machine.").assertIsDisplayed()
    }

    @Test
    fun `a page the machine cannot draw says why and offers no controls`() {
        show(waitForPicture = false, view = { Result.success(MobilePreviewView(state(open = false, unavailable = "The Preview tab is not open on this machine. Open a page to start it."))) })

        compose.onNodeWithText("The Preview tab is not open on this machine. Open a page to start it.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescription("Reload the page").fetchSemanticsNodes().size)
        compose.onNodeWithTag("preview-address").assertIsDisplayed()
    }

    @Test
    fun `a machine that refuses the phone speaks in its own words`() {
        val refusal = "This machine does not let this phone see or steer the Preview tab. Turn it on for this phone in the IDE, under Settings › Connections › Mobile › Devices, or use the tab there."
        show(projects = Result.failure(IllegalStateException(refusal)), waitForPicture = false)

        compose.onNodeWithText(refusal).assertIsDisplayed()
    }

    @Test
    fun `several open projects ask whose preview before drawing anything`() {
        show(projects = Result.success(MobilePreviewProjects(listOf(project, MobilePreviewProject("/work/api", "api")))), waitForPicture = false)

        assertEquals("nothing is drawn before a project is chosen", 0, views)
        compose.onNodeWithText("Whose preview?").assertIsDisplayed()
        compose.onNodeWithText("api").performClick()
        compose.waitForIdle()

        awaitPicture()
        assertEquals(1, views)
        compose.onNodeWithTag("preview-picture").assertIsDisplayed()
    }

    @Test
    fun `no open project is a sentence`() {
        show(projects = Result.success(MobilePreviewProjects(emptyList())), waitForPicture = false)

        assertTrue(compose.onAllNodesWithText("No project is open in the IDE. Open one there to preview its page.").fetchSemanticsNodes().isNotEmpty())
    }

    private fun settings(capable: Boolean) {
        val base = DeckFixtures.byName("settings")!!
        val state = if (capable) base.copy(hello = base.hello!!.copy(capabilities = base.hello!!.capabilities + MobileProtocol.Capability.PREVIEW)) else base
        val link = PreviewLink(projects = { Result.success(MobilePreviewProjects(listOf(project))) }, view = { _, _ -> Result.success(picture()) }, act = { Result.success(picture()) })
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                SettingsScreen(
                    state = state, onSettings = {}, onSwitchMachine = {}, onAddMachine = {}, onUnpair = {},
                    onRefreshHello = {}, onRefreshPush = {}, onChoosePush = {}, onCheckUpdate = {},
                    onDownloadUpdate = {}, onInstallUpdate = {}, onReleasePage = {}, preview = link,
                )
            }
        }
    }

    @Test
    fun `a machine without the preview capability has no row`() {
        settings(capable = false)

        assertEquals(0, compose.onAllNodesWithText("Preview").fetchSemanticsNodes().size)
    }

    @Test
    fun `Settings Resources opens the preview from its row`() {
        settings(capable = true)

        compose.onNodeWithText("Preview").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("preview-dialog").assertIsDisplayed()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
