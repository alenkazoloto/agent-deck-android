package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.mobile.MobileWorkingDiff
import com.github.claudeagents.core.mobile.MobileWorkingDiffFile
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.WorkingDiffFlow
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.WorkingDiffDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import javax.net.ssl.HttpsURLConnection

/**
 * A Codex chat's `/diff` on the phone (M4): the sheet lists the machine's uncommitted files, a
 * tap shows a file's patch, and a file whose patch did not fit the answer is read alone by path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class WorkingDiffFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val asked = CopyOnWriteArrayList<String?>()
    /** Holds a `?path=` answer back until released, for the late-answer case. */
    @Volatile private var gate: java.util.concurrent.CountDownLatch? = null
    @Volatile private var machine = MobileWorkingDiff(
        key = KEY,
        root = "/repo",
        summary = "2 files changed, +3 −1",
        boundary = "Everything not yet committed.",
        files = listOf(
            MobileWorkingDiffFile("tracked.txt", 1, 1, untracked = false, patch = "diff --git a/tracked.txt b/tracked.txt\n-two\n+CHANGED\n"),
            MobileWorkingDiffFile("src/brand-new.txt", 2, 0, untracked = true, patch = null),
        ),
    )

    private val flow = WorkingDiffFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `the sheet lists every uncommitted file and a tap opens its patch`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.diff }
        compose.setContent {
            AgentDeckTheme {
                val sheet by flow.sheet.collectAsState()
                sheet?.let { WorkingDiffDialog(it, flow::dismiss, flow::openFile, flow::closeFile, flow::refresh) }
            }
        }
        compose.onNodeWithText("2 files changed, +3 −1").assertIsDisplayed()
        compose.onNodeWithText("+2 −0 · new file").assertIsDisplayed()

        compose.onNodeWithText("tracked.txt").performClick()

        compose.onNodeWithText("+CHANGED").assertIsDisplayed()
        assertEquals("the patch came with the list", listOf<String?>(null), asked.toList())
        compose.onNodeWithTag("working-diff-open-path").assertIsDisplayed()
        flow.closeFile()
        compose.onNodeWithText("src/brand-new.txt").assertIsDisplayed()
    }

    @Test fun `a file past the answer's budget is read alone by its path`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.diff }
        machine = machine.copy(files = listOf(machine.files[1].copy(patch = "diff --git a/src/brand-new.txt b/src/brand-new.txt\n+hello\n+world\n")))

        flow.openFile("src/brand-new.txt")

        val file = eventually { flow.sheet.value?.openFile }
        assertEquals("+hello", file.patch!!.lines()[1])
        assertEquals(listOf(null, "src/brand-new.txt"), asked.toList())
    }

    @Test fun `a file committed since the list says so instead of showing nothing`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.diff }
        machine = machine.copy(files = emptyList())

        flow.openFile("src/brand-new.txt")

        val sheet = eventually { flow.sheet.value?.takeIf { !it.loadingFile && it.error != null } }
        assertEquals(WorkingDiffFlow.GONE, sheet.error)
        assertNull(sheet.openFile)
    }

    @Test fun `a slow single-file read is not reported as a clean file, and Try again reads it`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.diff }
        val listed = machine
        machine = MobileWorkingDiff(KEY, MobileWorkingDiff.SLOW, "git is still reading.")

        flow.openFile("src/brand-new.txt")

        val slow = eventually { flow.sheet.value?.takeIf { !it.loadingFile && it.error != null } }
        assertEquals("git is still reading.", slow.error)
        machine = listed.copy(files = listOf(listed.files[1].copy(patch = "+hello\n")))
        flow.refresh()
        val file = eventually { flow.sheet.value?.openFile }
        assertEquals("+hello\n", file.patch)
        assertEquals(listOf(null, "src/brand-new.txt", "src/brand-new.txt"), asked.toList())
    }

    @Test fun `a file's answer arriving after Back lands nowhere`() {
        flow.open(KEY)
        eventually { flow.sheet.value?.diff }
        gate = java.util.concurrent.CountDownLatch(1)

        flow.openFile("src/brand-new.txt")
        flow.closeFile()
        gate!!.countDown()
        Thread.sleep(200)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val sheet = flow.sheet.value!!
        assertNull(sheet.openPath)
        assertNull(sheet.openFile)
        assertNull(sheet.error)
    }

    @Test fun `a clean tree shows the desk's sentence`() {
        machine = MobileWorkingDiff(KEY, MobileWorkingDiff.NONE, "No changes detected.")
        flow.open(KEY)
        eventually { flow.sheet.value?.diff }
        compose.setContent {
            AgentDeckTheme {
                val sheet by flow.sheet.collectAsState()
                sheet?.let { WorkingDiffDialog(it, flow::dismiss, flow::openFile, flow::closeFile, flow::refresh) }
            }
        }
        compose.onNodeWithText("No changes detected.").assertIsDisplayed()
    }

    private fun <T : Any> eventually(value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            value()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

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
                if (!url.path.endsWith("/" + MobileWorkingDiff.SUFFIX)) return 404
                val path = url.query?.removePrefix("path=")?.let { URLDecoder.decode(it, "UTF-8") }
                asked += path
                if (path != null) gate?.await()
                val diff = machine
                answer = (if (path == null) diff else diff.copy(files = diff.files.filter { it.path == path })).toJson().toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "codex:/repo:thread"
    }
}
