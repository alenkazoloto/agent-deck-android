package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileWorktreeCreateRequest
import com.github.claudeagents.core.mobile.MobileWorktreeCreateResult
import com.github.claudeagents.core.mobile.MobileWorktreeOptions
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.WorktreeStartFlow
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
 * New chat in a worktree (M4, P22): the pick opens on the desk's suggested name, a create that
 * outlasts one request is followed under its operation id until it lands, the chat starts only in
 * the created path, and a failed create keeps the typed name with the machine's reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorktreeStartFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<MobileWorktreeCreateRequest>()
    private val started = CopyOnWriteArrayList<Pair<String, String>>()
    /** A result, a [MobileRefusal] the machine answers with, or null — thrown, as a dropped connection is. */
    private val answers = ArrayDeque<Any?>()
    @Volatile private var options = OPTIONS

    private val flow = WorktreeStartFlow(scope, { bridge() }, { 0L }, { it.message ?: "error" }, pollMs = 1)

    @After fun tearDown() = scope.cancel()

    @Test fun `the pick opens on the desk's suggestion and starts the chat in the created worktree`() {
        flow.choose(PROJECT)
        val choice = eventually { flow.choice.value?.takeIf { it.loaded } }
        assertEquals("the desk dialog's next free name", "wt-3", choice.name)

        answers += MobileWorktreeCreateResult(MobileWorktreeCreateResult.CREATING, "wt-3")
        answers += MobileWorktreeCreateResult(MobileWorktreeCreateResult.CREATING, "wt-3")
        answers += MobileWorktreeCreateResult(MobileWorktreeCreateResult.CREATED, "wt-3", "$PROJECT/.claude/worktrees/wt-3")
        flow.setFromHead(true)
        flow.create { path, name -> started += path to name }
        eventually { started.firstOrNull() }

        assertEquals("$PROJECT/.claude/worktrees/wt-3" to "wt-3", started.single())
        assertEquals("a create is followed, never repeated", 1, sent.map { it.operationId }.distinct().size)
        assertEquals(3, sent.size)
        assertTrue(sent.first().fromHead)
        assertNull("a landed create puts the pick back to this checkout", flow.choice.value)
    }

    @Test fun `a failed create starts nothing and keeps the typed name with the reason`() {
        flow.choose(PROJECT)
        eventually { flow.choice.value?.takeIf { it.loaded } }
        flow.editName("my-fix")
        answers += MobileWorktreeCreateResult(MobileWorktreeCreateResult.FAILED, "my-fix", message = "fatal: invalid reference")
        flow.create { path, name -> started += path to name }

        val failed = eventually { flow.choice.value?.takeIf { it.error != null } }
        assertEquals("fatal: invalid reference", failed.error)
        assertEquals("my-fix", failed.name)
        assertFalse(failed.creating)
        assertTrue(started.isEmpty())

        flow.off()
        flow.choose(PROJECT)
        val again = eventually { flow.choice.value?.takeIf { it.loaded } }
        assertEquals("the name is a draft until a create lands", "my-fix", again.name)
    }

    @Test fun `a create whose answer never arrived is asked again under the same operation`() {
        flow.choose(PROJECT)
        eventually { flow.choice.value?.takeIf { it.loaded } }
        answers += null
        flow.create { path, name -> started += path to name }
        eventually { flow.choice.value?.error }
        answers += MobileWorktreeCreateResult(MobileWorktreeCreateResult.CREATED, "wt-3", "$PROJECT/.claude/worktrees/wt-3")
        flow.create { path, name -> started += path to name }
        eventually { started.firstOrNull() }

        assertEquals("a lost answer must not become a second worktree", sent[0].operationId, sent[1].operationId)
    }

    @Test fun `a refusal after the machine said creating keeps the operation that is still running`() {
        flow.choose(PROJECT)
        eventually { flow.choice.value?.takeIf { it.loaded } }
        answers += MobileWorktreeCreateResult(MobileWorktreeCreateResult.CREATING, "wt-3")
        answers += MobileRefusal.NO_OPEN_PROJECT
        flow.create { _, _ -> }
        eventually { flow.choice.value?.error }
        answers += MobileWorktreeCreateResult(MobileWorktreeCreateResult.CREATED, "wt-3", "$PROJECT/.claude/worktrees/wt-3")
        flow.create { path, name -> started += path to name }
        eventually { started.firstOrNull() }

        assertEquals("the create the machine started is found again, not raced", 1, sent.map { it.operationId }.distinct().size)
    }

    @Test fun `a refusal of the first request retires its operation`() {
        flow.choose(PROJECT)
        eventually { flow.choice.value?.takeIf { it.loaded } }
        answers += MobileRefusal.NO_OPEN_PROJECT
        flow.create { _, _ -> }
        eventually { flow.choice.value?.error }
        answers += MobileWorktreeCreateResult(MobileWorktreeCreateResult.FAILED, "wt-3", message = "no")
        flow.create { _, _ -> }
        eventually { sent.takeIf { it.size == 2 } }

        assertNotEquals(sent[0].operationId, sent[1].operationId)
    }

    @Test fun `a renamed retry is a new operation`() {
        flow.choose(PROJECT)
        eventually { flow.choice.value?.takeIf { it.loaded } }
        answers += null
        flow.create { _, _ -> }
        eventually { flow.choice.value?.error }
        flow.editName("other")
        answers += MobileWorktreeCreateResult(MobileWorktreeCreateResult.FAILED, "other", message = "no")
        flow.create { _, _ -> }
        eventually { sent.takeIf { it.size == 2 } }

        assertNotEquals(sent[0].operationId, sent[1].operationId)
    }

    @Test fun `a taken name or a project outside git cannot create`() {
        flow.choose(PROJECT)
        eventually { flow.choice.value?.takeIf { it.loaded } }
        flow.editName("wt-1")
        assertTrue(flow.choice.value!!.nameTaken)
        assertFalse(flow.choice.value!!.canCreate)

        options = MobileWorktreeOptions(PROJECT, refused = "This project is not in a git repository, so it has no worktrees.")
        flow.choose(PROJECT)
        val refused = eventually { flow.choice.value?.takeIf { it.loaded } }
        assertEquals("This project is not in a git repository, so it has no worktrees.", refused.refused)
        assertFalse(refused.canCreate)
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
                if (url.path != MobileWorktreeCreateRequest.ROUTE) return 404
                if (body.size() == 0) {
                    answer = options.toJson().toString()
                    return 200
                }
                sent += MobileWorktreeCreateRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                return when (val next = answers.removeFirst()) {
                    null -> throw IOException("connection reset")
                    is MobileRefusal -> { answer = next.toJson().toString(); next.status }
                    else -> { answer = (next as MobileWorktreeCreateResult).toJson().toString(); 200 }
                }
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val PROJECT = "/work/repo"
        val OPTIONS = MobileWorktreeOptions(PROJECT, "wt-3", "origin/main", listOf("wt-1", "wt-2"))
    }
}
