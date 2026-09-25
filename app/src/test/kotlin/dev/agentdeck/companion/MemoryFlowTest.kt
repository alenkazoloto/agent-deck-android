package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileMemoryEntries
import com.github.claudeagents.core.mobile.MobileMemoryEntry
import com.github.claudeagents.core.mobile.MobileMemoryFile
import com.github.claudeagents.core.mobile.MobileMemoryProject
import com.github.claudeagents.core.mobile.MobileMemoryProjects
import com.github.claudeagents.core.mobile.MobileMemoryQuery
import com.github.claudeagents.core.mobile.MobileMemorySave
import com.github.claudeagents.core.mobile.MobileMemorySaved
import com.github.claudeagents.core.mobile.MobileRefusal
import com.google.gson.JsonParser
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.MemoryFlow
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
 * The Memory sheet on the phone: the machine's projects, a project's files, a file's text and a save
 * against the revision that was read. What the reader typed is theirs — kept through Back, closing, a
 * refused or failed save — and dropped only when the machine takes it or they choose the machine's
 * text. A file that moved on underneath is a conflict shown beside the typed text, never an overwrite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<String>()
    private val files = HashMap<String, String>().apply { put(ID, ORIGINAL) }
    @Volatile private var projects = listOf(PROJECT)
    @Volatile private var writable = true
    @Volatile private var refuseSaveWith: String? = null
    @Volatile private var refusePost: MobileRefusal? = null
    @Volatile private var generation = 0L

    private val flow = MemoryFlow(scope, { bridge() }, { generation }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `one open project goes straight to its files`() {
        flow.open()

        val sheet = eventually { flow.sheet.value?.takeIf { it.entries != null } }
        assertEquals(PROJECT, sheet.project)
        assertEquals(listOf(ID), sheet.entries?.entries?.map { it.id })
        assertEquals(listOf("GET projects", "GET entries"), sent)
    }

    @Test fun `several projects wait for a choice and Back returns to them from the files`() {
        projects = listOf(PROJECT, MobileMemoryProject("/work/other", "other"))
        flow.open()
        val listed = eventually { flow.sheet.value?.takeIf { it.projects != null } }
        assertNull("no project is guessed", listed.project)

        flow.chooseProject(PROJECT)
        eventually { flow.sheet.value?.takeIf { it.entries != null } }
        flow.back()

        assertNull(flow.sheet.value?.project)
        assertEquals(2, flow.sheet.value?.projects?.size)
        flow.back()
        assertNull("Back from the projects closes the sheet", flow.sheet.value)
    }

    @Test fun `a saved edit is sent against the revision that was read and is then the machine's text`() {
        val file = openFile()
        val read = file.revision
        flow.edit("$ORIGINAL- new rule\n")
        assertTrue(flow.sheet.value!!.open!!.dirty)

        flow.save()

        val saved = eventually { flow.sheet.value?.open?.takeIf { it.saved } }
        assertEquals("POST $ID rev=$read", sent.last())
        assertEquals("$ORIGINAL- new rule\n", files[ID])
        assertFalse(saved.dirty)
        assertEquals(MobileMemoryFile.revisionOf(true, "$ORIGINAL- new rule\n"), saved.revision)
        assertFalse("a taken edit is no longer a draft", flow.hasDraft(ID))
    }

    @Test fun `typed text survives Back, closing the sheet and reopening the file`() {
        openFile()
        flow.edit("half a thought")
        flow.back()
        assertTrue("the list marks the file", flow.hasDraft(ID))

        flow.dismiss()
        flow.open()
        eventually { flow.sheet.value?.takeIf { it.entries != null } }
        flow.openFile(ID)

        val reopened = eventually { flow.sheet.value?.open }
        assertEquals("half a thought", reopened.text)
        assertNull("the machine's file did not move, so there is nothing to resolve", reopened.conflict)
        assertTrue(reopened.dirty)
    }

    @Test fun `a file that changed under a save is a conflict, the typed text stays, and nothing was overwritten`() {
        openFile()
        flow.edit("my edit")
        files[ID] = "the agent wrote this"

        flow.save()

        val conflicted = eventually { flow.sheet.value?.open?.takeIf { it.conflict != null } }
        assertEquals("my edit", conflicted.text)
        assertEquals("the agent wrote this", conflicted.conflict?.content)
        assertEquals("the machine kept its own text", "the agent wrote this", files[ID])
        flow.save()
        assertEquals("a conflict blocks another blind save", 1, sent.count { it.startsWith("POST") })
    }

    @Test fun `keeping mine saves over the version the reader has now seen`() {
        openFile()
        flow.edit("my edit")
        files[ID] = "the agent wrote this"
        flow.save()
        eventually { flow.sheet.value?.open?.takeIf { it.conflict != null } }

        flow.keepMine()

        val saved = eventually { flow.sheet.value?.open?.takeIf { it.saved } }
        assertEquals("my edit", files[ID])
        assertNull(saved.conflict)
        assertEquals("the second save quotes the revision the conflict named", "POST $ID rev=${MobileMemoryFile.revisionOf(true, "the agent wrote this")}", sent.last())
    }

    @Test fun `loading the machine's text drops the reader's and its draft`() {
        openFile()
        flow.edit("my edit")
        files[ID] = "the agent wrote this"
        flow.save()
        eventually { flow.sheet.value?.open?.takeIf { it.conflict != null } }

        flow.loadTheirs()

        val open = flow.sheet.value!!.open!!
        assertEquals("the agent wrote this", open.text)
        assertFalse(open.dirty)
        assertNull(open.conflict)
        assertFalse(flow.hasDraft(ID))
    }

    @Test fun `a draft opened after the machine's file moved on shows the conflict before any save`() {
        openFile()
        flow.edit("my edit")
        flow.dismiss()
        files[ID] = "changed while the phone was away"

        flow.open()
        eventually { flow.sheet.value?.takeIf { it.entries != null } }
        flow.openFile(ID)

        val open = eventually { flow.sheet.value?.open }
        assertEquals("my edit", open.text)
        assertEquals("changed while the phone was away", open.conflict?.content)
        assertEquals("no save was needed to find out", 0, sent.count { it.startsWith("POST") })
    }

    @Test fun `a phone that may not save sends nothing`() {
        writable = false
        val file = openFile()
        assertFalse(file.writable)
        flow.edit("attempt")

        flow.save()

        assertEquals(0, sent.count { it.startsWith("POST") })
        assertEquals(ORIGINAL, files[ID])
    }

    @Test fun `a save the machine refuses keeps the text and shows its sentence`() {
        refuseSaveWith = "That file is too large to edit on a phone. Edit it in the IDE."
        openFile()
        flow.edit("big")

        flow.save()

        val open = eventually { flow.sheet.value?.open?.takeIf { it.error != null } }
        assertEquals("That file is too large to edit on a phone. Edit it in the IDE.", open.error)
        assertEquals("big", open.text)
        assertTrue(flow.hasDraft(ID))
        assertFalse(flow.sheet.value!!.busy)
    }

    @Test fun `the grant taken away mid-edit reads the machine's refusal and keeps the text`() {
        openFile()
        flow.edit("still mine")
        refusePost = MobileRefusal.PERMISSIONS_DISABLED

        flow.save()

        val open = eventually { flow.sheet.value?.open?.takeIf { it.error != null } }
        assertEquals(MobileRefusal.PERMISSIONS_DISABLED.message, open.error)
        assertEquals("still mine", open.text)
    }

    @Test fun `discard shows the machine's text again`() {
        openFile()
        flow.edit("oops")

        flow.discard()

        assertEquals(ORIGINAL, flow.sheet.value!!.open!!.text)
        assertFalse(flow.hasDraft(ID))
    }

    @Test fun `a different machine drops the drafts`() {
        openFile()
        flow.edit("mine")

        flow.forget()

        assertNull(flow.sheet.value)
        flow.open()
        eventually { flow.sheet.value?.takeIf { it.entries != null } }
        assertFalse(flow.hasDraft(ID))
    }

    private fun openFile(): dev.agentdeck.companion.data.OpenMemoryFile {
        flow.open()
        eventually { flow.sheet.value?.takeIf { it.entries != null } }
        flow.openFile(ID)
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
                if (url.path != MobileMemoryQuery.ROUTE) return 404
                if (requestMethod == "POST") return post(MobileMemorySave.fromJson(JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject))
                val query = url.query.orEmpty().split("&").filter { it.isNotEmpty() }
                    .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
                answer = when {
                    "project" !in query -> { sent += "GET projects"; MobileMemoryProjects(projects).toJson().toString() }
                    "id" !in query -> { sent += "GET entries"; MobileMemoryEntries(PROJECT.path, listOf(entry()), writable).toJson().toString() }
                    else -> { sent += "GET file"; file(query.getValue("id")).toJson().toString() }
                }
                return 200
            }

            private fun post(request: MobileMemorySave): Int {
                sent += "POST ${request.id} rev=${request.revision}"
                refusePost?.let { refused ->
                    answer = """{"v":1,"error":"${refused.code}","message":"${refused.message}"}"""
                    return refused.status
                }
                refuseSaveWith?.let {
                    answer = MobileMemorySaved(MobileMemorySaved.REFUSED, message = it).toJson().toString()
                    return 200
                }
                val current = file(request.id)
                answer = if (current.revision != request.revision) {
                    MobileMemorySaved(MobileMemorySaved.CONFLICT, current.revision, current = current)
                } else {
                    files[request.id] = request.content
                    MobileMemorySaved(MobileMemorySaved.SAVED, MobileMemoryFile.revisionOf(true, request.content))
                }.toJson().toString()
                return 200
            }

            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private fun entry() = MobileMemoryEntry(ID, "Project (CLAUDE.md)", "Instructions", "Instructions · Project", hasContent = true)

    private fun file(id: String): MobileMemoryFile {
        val text = files.getValue(id)
        return MobileMemoryFile(PROJECT.path, id, "Project (CLAUDE.md)", text, true, MobileMemoryFile.revisionOf(true, text), writable)
    }

    private companion object {
        const val ID = "Instructions:Project (CLAUDE.md)"
        const val ORIGINAL = "# Rules\n"
        val PROJECT = MobileMemoryProject("/work/agents-deck", "agents-deck")
    }
}
