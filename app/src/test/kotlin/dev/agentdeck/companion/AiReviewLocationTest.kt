package dev.agentdeck.companion

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.mobile.MobileAiReviewExcerpt
import com.github.claudeagents.core.mobile.MobileAiReviewFinding
import com.github.claudeagents.core.mobile.MobileAiReviewReport
import com.github.claudeagents.core.mobile.MobileAiReviewRequest
import com.github.claudeagents.core.mobile.MobileAiReviewState
import dev.agentdeck.companion.data.AiReviewFlow
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.AiReviewDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.net.URLDecoder
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * A Codex review finding's place opens its lines on the phone (M4, P20), the desk dialog's Enter on
 * it: the machine is asked for that finding of its report — index, path and line — and the file's
 * lines around it show in place of the findings, the named ones marked; Back returns to the list.
 * A machine that cannot answer leaves the place as plain text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AiReviewLocationTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val asked = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile private var findings = listOf(FIRST, SECOND)
    /** Holds the machine's excerpt answer until released, as a slow link would. */
    @Volatile private var hold: CountDownLatch? = null
    private val answeredCount = AtomicInteger()
    private val answered: Int get() = answeredCount.get()
    @Volatile private var excerpt = MobileAiReviewExcerpt(
        path = SECOND.path,
        startLine = 30,
        endLine = 31,
        firstLine = 10,
        lines = (10..51).map { "line $it" },
    )

    private val flow = AiReviewFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `a finding's place opens its file's lines, the named ones marked, and Back returns to the findings`() {
        show(canOpen = true)

        compose.onAllNodesWithTag("ai-review-location")[1].performClick()

        eventually { flow.sheet.value?.location?.excerpt }
        assertEquals(
            "the second finding, named by the place the phone shows",
            listOf(mapOf(MobileAiReviewExcerpt.FINDING to "1", MobileAiReviewExcerpt.PATH to "src/Main.kt", MobileAiReviewExcerpt.LINE to "30")),
            asked.toList(),
        )
        compose.onNodeWithTag("ai-review-location-view").assertExists()
        compose.onNode(hasText("Leaks the reader") and hasAnyAncestor(hasTestTag("ai-review-location-view"))).assertExists()
        compose.onNodeWithText("line 30").assertExists()
        compose.onAllNodesWithTag("ai-review-location-named").assertCountEquals(2)

        compose.onNodeWithTag("ai-review-location-back").performClick()

        compose.onNodeWithTag("ai-review-dialog").assertExists()
        assertNull(flow.sheet.value?.location)
    }

    @Test fun `Back returns to the findings where they were scrolled, not to the top`() {
        findings = (1..30).map { MobileAiReviewFinding("Finding $it", "", 2, 50, "src/F$it.kt", it, it) }
        show(canOpen = true)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("src/F20.kt:20"))

        compose.onNodeWithText("src/F20.kt:20").performClick()
        eventually { flow.sheet.value?.location?.excerpt }
        compose.onNodeWithTag("ai-review-location-back").performClick()

        compose.onNodeWithText("Finding 20").assertIsDisplayed()
    }

    @Test fun `an answer that lands after Back does not reopen the lines`() {
        val gate = CountDownLatch(1)
        hold = gate
        show(canOpen = true)

        compose.onAllNodesWithTag("ai-review-location")[1].performClick()
        eventually { asked.firstOrNull() }
        compose.onNodeWithTag("ai-review-location-back").performClick()
        gate.countDown()
        eventually { answered.takeIf { it > 0 } }
        repeat(20) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); compose.waitForIdle(); Thread.sleep(5) }

        assertNull(flow.sheet.value?.location)
        compose.onAllNodesWithTag("ai-review-location-view").assertCountEquals(0)
    }

    @Test fun `a place the machine will not read says why in its words`() {
        excerpt = MobileAiReviewExcerpt(SECOND.path, 30, 31, message = OUTSIDE)
        show(canOpen = true)

        compose.onAllNodesWithTag("ai-review-location")[1].performClick()

        eventually { flow.sheet.value?.location?.excerpt }
        compose.onNodeWithTag("ai-review-location-message").assertExists()
        compose.onNodeWithText(OUTSIDE).assertExists()
    }

    @Test fun `a machine that cannot open a finding leaves its place as text`() {
        show(canOpen = false)

        compose.onAllNodesWithTag("ai-review-location").assertCountEquals(0)
        compose.onNodeWithText(SECOND.locationLabel).assertExists()
    }

    private fun show(canOpen: Boolean) {
        flow.open(KEY)
        eventually { flow.sheet.value?.state }
        compose.setContent {
            val sheet by flow.sheet.collectAsState()
            AgentDeckTheme(dynamic = false) {
                sheet?.let {
                    AiReviewDialog(
                        sheet = it,
                        onDismiss = flow::dismiss, onEdit = { _, _, _ -> }, onStart = flow::start, onStop = flow::stop,
                        onOpenLocation = flow::openLocation.takeIf { canOpen },
                        onCloseLocation = flow::closeLocation,
                    )
                }
            }
        }
    }

    private fun <T : Any> eventually(value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            compose.waitForIdle()
            value()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

    /** The machine: the review's state, or with `?finding=` the excerpt `MobileAiReviewing` answers. */
    private fun bridge() = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private var answer = "{}"
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getResponseCode(): Int {
                if (!url.path.endsWith("/" + MobileAiReviewRequest.SUFFIX)) return 404
                val query = url.query?.split('&')?.associate { part ->
                    part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8")
                }
                answer = if (query == null) {
                    MobileAiReviewState(KEY, report = MobileAiReviewReport(findings, "${findings.size} findings.")).toJson().toString()
                } else {
                    asked += query
                    hold?.await(5, TimeUnit.SECONDS)
                    excerpt.toJson().toString().also { answeredCount.incrementAndGet() }
                }
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "codex:/repo:thread"
        const val OUTSIDE = "This file is outside the project, so only the IDE opens it."
        val FIRST = MobileAiReviewFinding("Divides by zero", "values may be empty", 0, 80, "src/Calc.kt", 2, 3)
        val SECOND = MobileAiReviewFinding("Leaks the reader", "never closed", 1, 70, "src/Main.kt", 30, 31)
    }
}
