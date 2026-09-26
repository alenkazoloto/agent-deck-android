package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileMemoryFile
import com.github.claudeagents.core.mobile.MobileMemorySaved
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSkillFileSave
import com.github.claudeagents.core.mobile.MobileSkillRow
import com.github.claudeagents.core.mobile.MobileSkillsQuery
import com.google.gson.JsonParser
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.OpenMemoryFile
import dev.agentdeck.companion.data.SkillFileFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * A skill's file on the phone: read by the row that names it, edited as text and saved against the revision that was read. What the
 * reader typed is theirs — kept through closing, a refused or failed save — and dropped only when the machine takes it or they choose
 * the machine's text. A file that moved on underneath is a conflict shown beside the typed text, never an overwrite. The draft rules are
 * the memory editor's ([MemoryFlowTest]); this pins that the skill editor keeps them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillFileFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<String>()
    @Volatile private var text = ORIGINAL
    @Volatile private var writable = true
    @Volatile private var unavailable: String? = null
    @Volatile private var refuseSaveWith: String? = null
    @Volatile private var refusePost: MobileRefusal? = null
    @Volatile private var generation = 0L

    private val flow = SkillFileFlow(scope, { bridge() }, { generation }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `opening a row reads its file by name, kind and source, never a path`() {
        val open = openFile()

        assertEquals("GET /deploy skill Project", sent.single())
        assertEquals(ORIGINAL, open.text)
        assertEquals(ORIGINAL, open.savedText)
        assertEquals(MobileMemoryFile.revisionOf(true, ORIGINAL), open.revision)
        assertTrue(open.writable)
        assertFalse(open.dirty)
        assertEquals(PROJECT, flow.sheet.value!!.project)
    }

    @Test fun `a file the machine will not hand over shows its sentence and opens no editor`() {
        unavailable = "That skill's file is no longer listed on the machine, or a plugin owns it. Reopen the list."

        flow.open(PROJECT, ROW)

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy } }
        assertNull(sheet.open)
        assertEquals(unavailable, sheet.error)
    }

    @Test fun `a saved edit is sent against the revision that was read and is then the machine's text`() {
        val read = openFile().revision
        flow.edit("$ORIGINAL- new step\n")
        assertTrue(flow.sheet.value!!.open!!.dirty)

        flow.save()

        val saved = eventually { flow.sheet.value?.open?.takeIf { it.saved } }
        assertEquals("POST /deploy skill Project rev=$read", sent.last())
        assertEquals("$ORIGINAL- new step\n", text)
        assertFalse(saved.dirty)
        assertEquals(MobileMemoryFile.revisionOf(true, "$ORIGINAL- new step\n"), saved.revision)
        assertFalse("a taken edit is no longer a draft", flow.hasDraft(PROJECT, ROW))
    }

    @Test fun `typed text survives closing the dialog and reopening the file`() {
        openFile()
        flow.edit("half a thought")
        flow.dismiss()
        assertNull(flow.sheet.value)
        assertTrue("the row marks the file", flow.hasDraft(PROJECT, ROW))

        flow.open(PROJECT, ROW)

        val reopened = eventually { flow.sheet.value?.open }
        assertEquals("half a thought", reopened.text)
        assertNull("the machine's file did not move, so there is nothing to resolve", reopened.conflict)
        assertTrue(reopened.dirty)
    }

    @Test fun `two rows of one name keep separate drafts`() {
        openFile()
        flow.edit("project draft")
        flow.dismiss()

        assertTrue(flow.hasDraft(PROJECT, ROW))
        assertFalse(flow.hasDraft(PROJECT, ROW.copy(source = "Personal")))
        assertFalse(flow.hasDraft("/work/beta", ROW))
    }

    @Test fun `a file that changed under a save is a conflict, the typed text stays, and nothing was overwritten`() {
        openFile()
        flow.edit("my edit")
        text = "edited on the desk"

        flow.save()

        val conflicted = eventually { flow.sheet.value?.open?.takeIf { it.conflict != null } }
        assertEquals("my edit", conflicted.text)
        assertEquals("edited on the desk", conflicted.conflict?.content)
        assertEquals("the machine kept its own text", "edited on the desk", text)
        flow.save()
        assertEquals("a conflict blocks another blind save", 1, sent.count { it.startsWith("POST") })
    }

    @Test fun `keeping mine saves over the version the reader has now seen`() {
        openFile()
        flow.edit("my edit")
        text = "edited on the desk"
        flow.save()
        eventually { flow.sheet.value?.open?.takeIf { it.conflict != null } }

        flow.keepMine()

        eventually { flow.sheet.value?.open?.takeIf { it.saved } }
        assertEquals("my edit", text)
        assertNull(flow.sheet.value!!.open!!.conflict)
    }

    @Test fun `loading the machine's text drops the reader's and its draft`() {
        openFile()
        flow.edit("my edit")
        text = "edited on the desk"
        flow.save()
        eventually { flow.sheet.value?.open?.takeIf { it.conflict != null } }

        flow.loadTheirs()

        val open = flow.sheet.value!!.open!!
        assertEquals("edited on the desk", open.text)
        assertFalse(open.dirty)
        assertFalse(flow.hasDraft(PROJECT, ROW))
    }

    @Test fun `a draft opened after the machine's file moved on shows the conflict before any save`() {
        openFile()
        flow.edit("my edit")
        flow.dismiss()
        text = "edited on the desk"

        flow.open(PROJECT, ROW)

        val open = eventually { flow.sheet.value?.open }
        assertEquals("my edit", open.text)
        assertEquals("edited on the desk", open.conflict?.content)
        flow.save()
        assertTrue("Save waits for the reader's choice", sent.none { it.startsWith("POST") })
    }

    @Test fun `a phone without the grant reads the file and sends nothing`() {
        writable = false
        val open = openFile()

        assertFalse(open.writable)
        flow.edit("typed anyway")
        flow.save()

        assertTrue(sent.none { it.startsWith("POST") })
        assertEquals("the typed text is still the reader's", "typed anyway", flow.sheet.value!!.open!!.text)
    }

    @Test fun `a save the machine refuses keeps the text and shows its sentence`() {
        openFile()
        flow.edit("my edit")
        refuseSaveWith = "That file is too large to edit on a phone. Edit it in the IDE."

        flow.save()

        val open = eventually { flow.sheet.value?.open?.takeIf { it.error != null } }
        assertEquals(refuseSaveWith, open.error)
        assertEquals("my edit", open.text)
        assertTrue(flow.hasDraft(PROJECT, ROW))
        assertEquals(ORIGINAL, text)
    }

    @Test fun `the grant taken away mid-edit reads the machine's refusal and keeps the text`() {
        openFile()
        flow.edit("my edit")
        refusePost = MobileRefusal.PERMISSIONS_DISABLED

        flow.save()

        val open = eventually { flow.sheet.value?.open?.takeIf { it.error != null } }
        assertEquals(MobileRefusal.PERMISSIONS_DISABLED.message, open.error)
        assertEquals("my edit", open.text)
        assertEquals(ORIGINAL, text)
    }

    @Test fun `discard shows the machine's text again`() {
        openFile()
        flow.edit("my edit")

        flow.discard()

        val open = flow.sheet.value!!.open!!
        assertEquals(ORIGINAL, open.text)
        assertFalse(flow.hasDraft(PROJECT, ROW))
    }

    @Test fun `a different machine drops the drafts`() {
        openFile()
        flow.edit("mine")

        flow.forget()

        assertNull(flow.sheet.value)
        assertFalse(flow.hasDraft(PROJECT, ROW))
    }

    private fun openFile(): OpenMemoryFile {
        flow.open(PROJECT, ROW)
        return eventually { flow.sheet.value?.open }
    }

    private fun <T : Any> eventually(read: () -> T?): T {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) read()?.let { return it }.also { Thread.sleep(10) }
        throw AssertionError("nothing arrived in 5 s")
    }

    private fun bridge() = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private var answer = "{}"
            private val body = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode(): Int {
                if (url.path != MobileSkillsQuery.FILE_ROUTE) return 404
                if (requestMethod == "POST") return post(MobileSkillFileSave.fromJson(JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject)!!)
                val query = url.query.orEmpty().split("&").filter { it.isNotEmpty() }
                    .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
                sent += "GET ${query["name"]} ${query["kind"]} ${query["source"]}"
                answer = file().toJson().toString()
                return 200
            }

            private fun post(request: MobileSkillFileSave): Int {
                sent += "POST ${request.name} ${request.kind} ${request.source} rev=${request.revision}"
                refusePost?.let { refused ->
                    answer = """{"v":1,"error":"${refused.code}","message":"${refused.message}"}"""
                    return refused.status
                }
                refuseSaveWith?.let {
                    answer = MobileMemorySaved(MobileMemorySaved.REFUSED, message = it).toJson().toString()
                    return 200
                }
                val current = file()
                answer = if (current.revision != request.revision) {
                    MobileMemorySaved(MobileMemorySaved.CONFLICT, current.revision, current = current)
                } else {
                    text = request.content
                    MobileMemorySaved(MobileMemorySaved.SAVED, MobileMemoryFile.revisionOf(true, request.content))
                }.toJson().toString()
                return 200
            }

            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private fun file() = MobileMemoryFile(
        PROJECT, "skill:/deploy", "/deploy", if (unavailable == null) text else "", unavailable == null,
        if (unavailable == null) MobileMemoryFile.revisionOf(true, text) else "", writable, unavailable,
    )

    private companion object {
        const val PROJECT = "/work/alpha"
        const val ORIGINAL = "---\nname: deploy\n---\nShip it.\n"
        val ROW = MobileSkillRow("skill", "/deploy", "Ship it", "Project", fileEditable = true)
    }
}
