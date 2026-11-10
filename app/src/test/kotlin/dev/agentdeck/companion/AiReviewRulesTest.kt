package dev.agentdeck.companion

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.mobile.MobileAiReviewFinding
import com.github.claudeagents.core.mobile.MobileAiReviewReport
import com.github.claudeagents.core.mobile.MobileAiReviewRequest
import com.github.claudeagents.core.mobile.MobileAiReviewState
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.AiReviewFlow
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.AiReviewDialog
import dev.agentdeck.companion.ui.NEVER_REPORT_LABEL
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection

/**
 * The Codex review dialog's rule buttons on the phone (M4, P20): an opened finding's "Never report
 * this" and "Review rules (N)…" write the project's rules on the machine and start no review; a
 * save over a list the desk changed since is refused with the typed text kept. A machine that sends
 * no rules gets no rule controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AiReviewRulesTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val posts = CopyOnWriteArrayList<MobileAiReviewRequest>()
    @Volatile private var machine = MobileAiReviewState(
        KEY,
        report = MobileAiReviewReport(listOf(FINDING), "1 finding."),
        rules = listOf("Flag any new console.log"),
    )
    /** A desk edit landing between the phone's read and its save. */
    @Volatile private var deskEdit: List<String>? = null

    private val flow = AiReviewFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `Never report this on an opened finding writes the rule, starts nothing and says so`() {
        show()
        compose.onAllNodesWithTag("ai-review-never-report").assertCountEquals(0)

        compose.onNodeWithTag("ai-review-finding").performClick()
        compose.onNodeWithText(NEVER_REPORT_LABEL).performClick()

        eventually { flow.sheet.value?.notice }
        val post = posts.single()
        assertEquals(FINDING, post.neverReport)
        assertNull("not a start", post.operationId)
        compose.onNodeWithTag("ai-review-notice").assertExists()
        compose.onNodeWithText("Review rules (2)…").assertExists()
    }

    @Test fun `Review rules edits the list in place of the form and saves it one rule per line`() {
        show()
        compose.onNodeWithText("Review rules (1)…").performClick()
        compose.onNodeWithTag("ai-review-rules-text").performTextReplacement("Flag any new console.log\nNo TODO without an issue")
        compose.onNodeWithTag("ai-review-rules-save").performClick()

        eventually { flow.sheet.value?.takeIf { it.rulesText == null && !it.sending } }
        val post = posts.single()
        assertEquals(listOf("Flag any new console.log", "No TODO without an issue"), post.rules)
        assertEquals(listOf("Flag any new console.log"), post.expectedRules)
        compose.onNodeWithTag("ai-review-dialog").assertExists()
        compose.onNodeWithText("Review rules (2)…").assertExists()
    }

    @Test fun `a save over the desk's newer list is refused, keeps what was typed, and a second Save replaces it`() {
        show()
        compose.onNodeWithText("Review rules (1)…").performClick()
        deskEdit = listOf("Written on the desk")
        compose.onNodeWithTag("ai-review-rules-text").performTextReplacement("Phone's rule")
        compose.onNodeWithTag("ai-review-rules-save").performClick()

        val sheet = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals("Phone's rule", sheet.rulesText)
        compose.onNodeWithTag("ai-review-rules-editor").assertExists()
        compose.onNodeWithText(REFUSED).assertExists()

        deskEdit = null
        compose.onNodeWithTag("ai-review-rules-save").performClick()

        eventually { flow.sheet.value?.takeIf { it.rulesText == null && !it.sending } }
        assertEquals("the second save names the list it now knows it replaces", listOf("Written on the desk"), posts.last().expectedRules)
        assertEquals(listOf("Phone's rule"), machine.rules)
    }

    @Test fun `a machine that keeps no rules for the chat shows no rule controls`() {
        machine = machine.copy(rules = null)
        show()
        compose.onNodeWithTag("ai-review-finding").performClick()

        compose.onAllNodesWithTag("ai-review-rules").assertCountEquals(0)
        compose.onAllNodesWithTag("ai-review-never-report").assertCountEquals(0)
    }

    private fun show() {
        flow.open(KEY)
        eventually { flow.sheet.value?.state }
        compose.setContent {
            val sheet by flow.sheet.collectAsState()
            AgentDeckTheme(dynamic = false) {
                sheet?.let {
                    AiReviewDialog(
                        sheet = it,
                        onDismiss = flow::dismiss, onEdit = { _, _, _ -> }, onStart = flow::start, onStop = flow::stop,
                        onNeverReport = flow::neverReport,
                        onOpenRules = flow::openRules,
                        onEditRules = flow::editRules,
                        onSaveRules = flow::saveRules,
                        onCloseRules = flow::closeRules,
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

    /** The machine's rule writes, as `MobileAiReviewing.writeRules` answers them. */
    private fun bridge() = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            private var answer = "{}"
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode(): Int {
                if (!url.path.endsWith("/" + MobileAiReviewRequest.SUFFIX)) return 404
                if (body.size() > 0) {
                    val request = MobileAiReviewRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                    posts += request
                    deskEdit?.let { machine = machine.copy(rules = it) }
                    val rules = machine.rules.orEmpty()
                    var refused: String? = null
                    request.neverReport?.let { machine = machine.copy(rules = rules + "Do not report findings like \"${it.title}\"") }
                    request.rules?.let {
                        if (request.expectedRules != rules) refused = REFUSED else machine = machine.copy(rules = it.filter(String::isNotBlank))
                    }
                    answer = machine.copy(refused = refused).toJson().toString()
                } else {
                    answer = machine.toJson().toString()
                }
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "codex:/repo:thread"
        const val REFUSED = "The rules changed on the machine since you opened them."
        val FINDING = MobileAiReviewFinding("Divides by zero", "values may be empty", 0, 80, "src/Calc.kt", 2, 3)
    }
}
