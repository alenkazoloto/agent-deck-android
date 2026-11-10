package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileRepositoryHost
import com.github.claudeagents.core.mobile.MobileRepositorySetupOptions
import com.github.claudeagents.core.mobile.MobileRepositorySetupRequest
import com.github.claudeagents.core.mobile.MobileRepositorySetupResult
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.RepositorySetupFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection

/**
 * Clone and Publish repository (M4, P22): the sheet opens on the desk's draft and the machine's
 * first directory, a clone outlasting one request is followed under its operation id, a lost answer
 * is asked again under the same id, a failure keeps what was typed, a landed clone may be opened on
 * the machine, and a landed publish takes the project off New chat's Publish row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositorySetupFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<MobileRepositorySetupRequest>()
    /** A result, a [MobileRefusal] the machine answers with, or null — thrown, as a dropped connection is. */
    private val answers = ArrayDeque<Any?>()
    @Volatile private var options = OPTIONS
    /** Holds each write answer back, so the test acts while the machine is still "working". */
    @Volatile private var slow = false

    private val flow = RepositorySetupFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" }, pollMs = 1)

    @After fun tearDown() = scope.cancel()

    @Test fun `a clone opens on the desk's draft, is followed until it lands, and may be opened on the machine`() {
        flow.open(PROJECT, publish = false)
        val sheet = eventually { flow.sheet.value?.takeIf { it.loaded } }
        assertEquals("the desk's unsent paste", "https://github.com/o/r", sheet.input)
        assertEquals("the open project's own directory", "/work", sheet.parentDir)
        assertEquals("github", sheet.host)
        assertTrue(sheet.inputIsUrl)

        answers += MobileRepositorySetupResult(MobileRepositorySetupResult.WORKING)
        answers += MobileRepositorySetupResult(MobileRepositorySetupResult.DONE, "Cloned r into /work/r.", path = "/work/r")
        flow.submit()
        val done = eventually { flow.sheet.value?.done }
        assertEquals("/work/r", done.path)
        assertEquals("a clone is followed, never repeated", 1, sent.map { it.operationId }.distinct().size)
        assertEquals(MobileRepositorySetupRequest.CLONE, sent.first().action)
        assertEquals("/work", sent.first().parentDir)

        answers += MobileRepositorySetupResult(MobileRepositorySetupResult.DONE, "Opening r on the machine.", path = "/work/r")
        flow.openCloned()
        assertEquals("Opening r on the machine.", eventually { flow.sheet.value?.done?.opened })
        assertEquals(MobileRepositorySetupRequest.OPEN, sent.last().action)
        assertEquals("/work/r", sent.last().path)

        // The machine cleared the consumed paste; a later one typed on the desk is what reopening shows.
        options = OPTIONS.copy(cloneDraft = "https://github.com/o/next")
        flow.close()
        flow.open(PROJECT, publish = false)
        assertEquals("https://github.com/o/next", eventually { flow.sheet.value?.takeIf { it.loaded } }.input)
    }

    @Test fun `a running write keeps its sheet, so its outcome cannot land on another and nothing runs twice`() {
        flow.open(PROJECT, publish = false)
        eventually { flow.sheet.value?.takeIf { it.loaded } }
        repeat(3) { answers += MobileRepositorySetupResult(MobileRepositorySetupResult.WORKING) }
        answers += MobileRepositorySetupResult(MobileRepositorySetupResult.DONE, "Cloned r into /work/r.", path = "/work/r")
        slow = true
        flow.submit()
        eventually { flow.sheet.value?.takeIf { it.working } }

        flow.close()
        flow.open("/work/other", publish = true)
        flow.submit()
        assertEquals("the running clone's sheet stays", PROJECT, flow.sheet.value?.projectPath)
        assertFalse(flow.sheet.value!!.publish)
        slow = false
        assertEquals("/work/r", eventually { flow.sheet.value?.done }.path)
        assertEquals("one write, one operation", 1, sent.map { it.operationId }.distinct().size)
        assertTrue(sent.all { it.action == MobileRepositorySetupRequest.CLONE })
    }

    @Test fun `a failed clone keeps what was typed with the machine's reason`() {
        flow.open(PROJECT, publish = false)
        eventually { flow.sheet.value?.takeIf { it.loaded } }
        flow.editInput("o/other")
        answers += MobileRepositorySetupResult(MobileRepositorySetupResult.FAILED, "/work/other already exists and is not empty.")
        flow.submit()
        val failed = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals("/work/other already exists and is not empty.", failed.error)
        assertNull(failed.done)

        flow.close()
        flow.open(PROJECT, publish = false)
        assertEquals("typed text is a draft until a clone lands", "o/other", eventually { flow.sheet.value?.takeIf { it.loaded } }.input)
    }

    @Test fun `a write whose answer never arrived is asked again under the same operation`() {
        flow.open(PROJECT, publish = false)
        eventually { flow.sheet.value?.takeIf { it.loaded } }
        answers += null
        flow.submit()
        eventually { flow.sheet.value?.error }
        answers += MobileRepositorySetupResult(MobileRepositorySetupResult.DONE, "Cloned.", path = "/work/r")
        flow.submit()
        eventually { flow.sheet.value?.done }

        assertEquals("a lost answer must not become a second clone", sent[0].operationId, sent[1].operationId)
    }

    @Test fun `a refusal of the first request retires its operation`() {
        flow.open(PROJECT, publish = false)
        eventually { flow.sheet.value?.takeIf { it.loaded } }
        answers += MobileRefusal.NO_OPEN_PROJECT
        flow.submit()
        eventually { flow.sheet.value?.error }
        answers += MobileRepositorySetupResult(MobileRepositorySetupResult.FAILED, "no")
        flow.submit()
        eventually { sent.takeIf { it.size == 2 } }

        assertNotEquals(sent[0].operationId, sent[1].operationId)
    }

    @Test fun `publish is offered only where the machine says so and a landed one takes it away`() {
        flow.check(PROJECT)
        eventually { flow.publishable.value.takeIf { PROJECT in it } }

        flow.open(PROJECT, publish = true)
        val sheet = eventually { flow.sheet.value?.takeIf { it.loaded } }
        assertEquals("the directory's own name", "repo", sheet.input)
        flow.editInput("me/repo")
        flow.setVisibility(MobileRepositorySetupRequest.PUBLIC)
        answers += MobileRepositorySetupResult(MobileRepositorySetupResult.DONE, "Published main to https://github.com/me/repo.git.", url = "https://github.com/me/repo")
        flow.submit()
        assertEquals("https://github.com/me/repo", eventually { flow.sheet.value?.done }.url)
        assertEquals(MobileRepositorySetupRequest.PUBLISH, sent.single().action)
        assertEquals("me/repo", sent.single().input)
        assertEquals(MobileRepositorySetupRequest.PUBLIC, sent.single().visibility)
        assertFalse("the row is gone once the checkout has a remote", PROJECT in flow.publishable.value)

        options = OPTIONS.copy(publishable = false)
        flow.open(PROJECT, publish = true)
        assertFalse(eventually { flow.sheet.value?.takeIf { it.loaded } }.canSubmit)
    }

    private fun <T : Any> eventually(value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            value()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

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
                if (url.path != MobileRepositorySetupRequest.ROUTE) return 404
                if (body.size() == 0) {
                    answer = options.toJson().toString()
                    return 200
                }
                while (slow && answers.size > 1) Thread.sleep(5)
                sent += MobileRepositorySetupRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                return when (val next = answers.removeFirst()) {
                    null -> throw IOException("connection reset")
                    is MobileRefusal -> { answer = next.toJson().toString(); next.status }
                    else -> { answer = (next as MobileRepositorySetupResult).toJson().toString(); 200 }
                }
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val PROJECT = "/work/repo"
        val OPTIONS = MobileRepositorySetupOptions(
            projectPath = PROJECT,
            hosts = listOf(
                MobileRepositoryHost("github", "GitHub", "owner/repository"),
                MobileRepositoryHost("azure", "Azure DevOps", "organization/project/repository", false, "An Azure DevOps repository is as visible as its project."),
            ),
            cloneParents = listOf("/work", "/elsewhere"),
            cloneDraft = "https://github.com/o/r",
            publishable = true,
            suggestedRepository = "repo",
        )
    }
}
