package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileMemoryDelete
import com.github.claudeagents.core.mobile.MobileMemoryEntries
import com.github.claudeagents.core.mobile.MobileMemoryEntry
import com.github.claudeagents.core.mobile.MobileMemoryCap
import com.github.claudeagents.core.mobile.MobileMemoryFile
import com.github.claudeagents.core.mobile.MobileMemoryLoadOrder
import com.github.claudeagents.core.mobile.MobileMemoryLoadStep
import com.github.claudeagents.core.mobile.MobileMemoryProject
import com.github.claudeagents.core.mobile.MobileMemoryProjects
import com.github.claudeagents.core.mobile.MobileMemoryQuery
import com.github.claudeagents.core.mobile.MobileMemoryRulePaths
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
    private val files = HashMap<String, String>().apply { put(ID, ORIGINAL); put(NOTE, NOTE_TEXT) }
    @Volatile private var projects = listOf(PROJECT)
    @Volatile private var writable = true
    @Volatile private var refuseSaveWith: String? = null
    @Volatile private var refusePost: MobileRefusal? = null
    @Volatile private var refuseDeleteWith: String? = null
    @Volatile private var generation = 0L
    @Volatile private var orderRefusal: MobileRefusal? = null

    private val flow = MemoryFlow(scope, { bridge() }, { generation }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `one open project goes straight to its files`() {
        flow.open()

        val sheet = eventually { flow.sheet.value?.takeIf { it.entries != null } }
        assertEquals(PROJECT, sheet.project)
        assertEquals(listOf(ID, NOTE), sheet.entries?.entries?.map { it.id })
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

    @Test fun `deleting a note quotes the revision read, drops it from the list and its draft with it`() {
        val note = openFile(NOTE)
        assertTrue(note.deletable)
        flow.edit("half a thought about the note")
        assertTrue(flow.hasDraft(NOTE))

        flow.delete()

        val sheet = eventually { flow.sheet.value?.takeIf { it.open == null && !it.busy } }
        assertEquals("DELETE $NOTE rev=${MobileMemoryFile.revisionOf(true, NOTE_TEXT)}", sent.last())
        assertFalse("the machine removed it", files.containsKey(NOTE))
        assertEquals(listOf(ID), sheet.entries?.entries?.map { it.id })
        assertFalse("the reader's text was for a note that is gone", flow.hasDraft(NOTE))
        assertNull(sheet.error)
    }

    @Test fun `instructions are never offered for delete and delete sends nothing for them`() {
        val file = openFile()
        assertFalse(file.deletable)

        flow.delete()

        assertEquals(0, sent.count { it.startsWith("DELETE") })
        assertTrue(files.containsKey(ID))
    }

    @Test fun `a phone that may not save sends no delete`() {
        writable = false
        openFile(NOTE)

        flow.delete()

        assertEquals(0, sent.count { it.startsWith("DELETE") })
        assertTrue(files.containsKey(NOTE))
    }

    @Test fun `a note rewritten before the delete arrives is a conflict, kept with the typed text`() {
        openFile(NOTE)
        flow.edit("my edit")
        files[NOTE] = "the agent added a line"

        flow.delete()

        val open = eventually { flow.sheet.value?.open?.takeIf { it.conflict != null } }
        assertTrue("the note is still on the machine", files.containsKey(NOTE))
        assertEquals("the agent added a line", open.conflict?.content)
        assertEquals("my edit", open.text)
        flow.delete()
        assertEquals("an unresolved conflict blocks another blind delete", 1, sent.count { it.startsWith("DELETE") })
    }

    @Test fun `a delete the machine refuses keeps the note and shows its sentence`() {
        refuseDeleteWith = "The machine could not delete that note: read only"
        openFile(NOTE)

        flow.delete()

        val open = eventually { flow.sheet.value?.open?.takeIf { it.error != null } }
        assertEquals("The machine could not delete that note: read only", open.error)
        assertTrue(files.containsKey(NOTE))
        assertFalse(flow.sheet.value!!.busy)
    }

    @Test fun `the grant taken away mid-session reads the machine's refusal on delete`() {
        openFile(NOTE)
        refusePost = MobileRefusal.PERMISSIONS_DISABLED

        flow.delete()

        val open = eventually { flow.sheet.value?.open?.takeIf { it.error != null } }
        assertEquals(MobileRefusal.PERMISSIONS_DISABLED.message, open.error)
        assertTrue(files.containsKey(NOTE))
    }

    @Test fun `the load order opens over the files, is read from the machine and Back returns to the files`() {
        flow.open()
        eventually { flow.sheet.value?.takeIf { it.entries != null } }

        flow.openLoadOrder()

        val sheet = eventually { flow.sheet.value?.takeIf { it.loadOrder != null } }
        assertTrue(sheet.loadOrderOpen)
        assertFalse(sheet.busy)
        assertEquals(listOf("Project · CLAUDE.md", "@style.md"), sheet.loadOrder!!.steps.map { it.text })
        assertEquals(1, sheet.loadOrder!!.steps[1].depth)
        assertEquals("GET loadOrder", sent.last())

        flow.back()

        val back = flow.sheet.value!!
        assertFalse(back.loadOrderOpen)
        assertNull(back.loadOrder)
        assertEquals("the file list is still there", listOf(ID, NOTE), back.entries?.entries?.map { it.id })
        flow.back()
        assertNull("Back from the list closes the sheet as before", flow.sheet.value)
    }

    @Test fun `a load order the machine cannot give is its sentence, not an empty list, and Back still works`() {
        flow.open()
        eventually { flow.sheet.value?.takeIf { it.entries != null } }
        orderRefusal = MobileRefusal.NOT_FOUND

        flow.openLoadOrder()

        val sheet = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertNull(sheet.loadOrder)
        assertFalse(sheet.busy)
        assertTrue(sheet.loadOrderOpen)
        flow.back()
        assertFalse(flow.sheet.value!!.loadOrderOpen)
        assertNull(flow.sheet.value!!.error)
    }

    @Test fun `a project the machine says is not open shows its sentence in the load order`() {
        flow.open()
        eventually { flow.sheet.value?.takeIf { it.entries != null } }
        orderUnavailable = "That project is not open in the IDE. Open it there, or pick another."

        flow.openLoadOrder()

        val sheet = eventually { flow.sheet.value?.takeIf { it.loadOrder != null } }
        assertEquals("That project is not open in the IDE. Open it there, or pick another.", sheet.loadOrder?.unavailable)
    }

    @Test fun `the cap meter is the machine's for the text it holds, and a save moves it to the text just written`() {
        val opened = openFile()
        assertEquals(MobileMemoryCap.OK, opened.cap?.level)
        assertEquals("small", opened.cap?.text)
        flow.edit("$ORIGINAL- a much longer rule than the fake machine allows\n")
        assertEquals("typing does not re-measure", "small", flow.sheet.value!!.open!!.cap?.text)

        flow.save()

        val saved = eventually { flow.sheet.value?.open?.takeIf { it.saved } }
        assertEquals(MobileMemoryCap.OVER, saved.cap?.level)
        assertEquals("big", saved.cap?.text)
    }

    @Test fun `resolving a conflict takes the meter of the machine's text`() {
        openFile()
        flow.edit("my edit")
        files[ID] = "the agent wrote this, and it is long"
        flow.save()
        eventually { flow.sheet.value?.open?.takeIf { it.conflict != null } }
        assertEquals("small", flow.sheet.value!!.open!!.cap?.text)

        flow.loadTheirs()

        assertEquals("big", flow.sheet.value!!.open!!.cap?.text)
    }

    @Test fun `a rules file carries its paths line and a save carries the new one`() {
        files[RULE] = "Use 4 spaces.\n"
        val opened = openFile(RULE)
        assertEquals("Always loaded", opened.rulePaths?.text)
        assertNull("a rules file has no meter", opened.cap)

        flow.edit("---\npaths:\n  - \"src/**\"\n---\nUse 4 spaces.\n")
        flow.save()

        val saved = eventually { flow.sheet.value?.open?.takeIf { it.saved } }
        assertEquals(listOf("src/**"), saved.rulePaths?.globs)
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

    private fun openFile(id: String = ID): dev.agentdeck.companion.data.OpenMemoryFile {
        flow.open()
        eventually { flow.sheet.value?.takeIf { it.entries != null } }
        flow.openFile(id)
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
                if (url.path == MobileMemoryQuery.DELETE_ROUTE) return delete(MobileMemoryDelete.fromJson(JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject))
                if (url.path != MobileMemoryQuery.ROUTE) return 404
                if (requestMethod == "POST") return post(MobileMemorySave.fromJson(JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject))
                val query = url.query.orEmpty().split("&").filter { it.isNotEmpty() }
                    .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
                answer = when {
                    "loadOrder" in query -> {
                        sent += "GET loadOrder"
                        orderRefusal?.let { refused ->
                            answer = """{"v":1,"error":"${refused.code}","message":"${refused.message}"}"""
                            return refused.status
                        }
                        loadOrder().toJson().toString()
                    }
                    "project" !in query -> { sent += "GET projects"; MobileMemoryProjects(projects).toJson().toString() }
                    "id" !in query -> { sent += "GET entries"; MobileMemoryEntries(PROJECT.path, listOfNotNull(entry(), noteEntry().takeIf { NOTE in files }), writable).toJson().toString() }
                    else -> { sent += "GET file"; file(query.getValue("id")).toJson().toString() }
                }
                return 200
            }

            private fun delete(request: MobileMemoryDelete): Int {
                sent += "DELETE ${request.id} rev=${request.revision}"
                refusePost?.let { refused ->
                    answer = """{"v":1,"error":"${refused.code}","message":"${refused.message}"}"""
                    return refused.status
                }
                refuseDeleteWith?.let {
                    answer = MobileMemorySaved(MobileMemorySaved.REFUSED, message = it).toJson().toString()
                    return 200
                }
                val current = file(request.id)
                answer = if (current.revision != request.revision) {
                    MobileMemorySaved(MobileMemorySaved.CONFLICT, current.revision, current = current)
                } else {
                    files.remove(request.id)
                    MobileMemorySaved(MobileMemorySaved.DELETED)
                }.toJson().toString()
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
                    MobileMemorySaved(
                        MobileMemorySaved.SAVED, MobileMemoryFile.revisionOf(true, request.content),
                        cap = cap(request.content), rulePaths = rulePaths(request.id, request.content),
                    )
                }.toJson().toString()
                return 200
            }

            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private fun entry() = MobileMemoryEntry(ID, "Project (CLAUDE.md)", "Instructions", "Instructions · Project", hasContent = true)

    private fun noteEntry() = MobileMemoryEntry(NOTE, "user_role.md", "Agent memory", "Agent memory", hasContent = true)

    private fun file(id: String): MobileMemoryFile {
        val text = files.getValue(id)
        return MobileMemoryFile(
            PROJECT.path, id, if (id == NOTE) "user_role.md" else "Project (CLAUDE.md)", text, true,
            MobileMemoryFile.revisionOf(true, text), writable, deletable = id == NOTE,
            cap = cap(text).takeIf { id != RULE }, rulePaths = rulePaths(id, text),
        )
    }

    /** A fake machine's meter: it only has to differ with the text it is given. */
    private fun cap(text: String) =
        if (text.length > 30) MobileMemoryCap("big", "Cut it.", MobileMemoryCap.OVER) else MobileMemoryCap("small", "1 line of 200.", MobileMemoryCap.OK)

    private fun rulePaths(id: String, text: String): MobileMemoryRulePaths? {
        if (id != RULE) return null
        val globs = Regex("\"([^\"]+/\\*\\*)\"").findAll(text).map { it.groupValues[1] }.toList()
        return if (globs.isEmpty()) MobileMemoryRulePaths("Always loaded", "Every file.") else MobileMemoryRulePaths("Loads only for files matching:", "This rule loads only for files matching:", globs)
    }

    @Volatile private var orderUnavailable: String? = null

    private fun loadOrder() = MobileMemoryLoadOrder(
        PROJECT.path,
        listOf(MobileMemoryLoadStep("Project · CLAUDE.md", "1.5 kB", "This repository's shared instructions."), MobileMemoryLoadStep("@style.md", "2 B", depth = 1)),
        "2 of 2 load for this project, in this order. 1 @import.",
        orderUnavailable,
    )

    private companion object {
        const val ID = "Instructions:Project (CLAUDE.md)"
        const val ORIGINAL = "# Rules\n"
        const val NOTE = "Agent memory:user_role.md"
        const val RULE = "Rules:style.md"
        const val NOTE_TEXT = "The reader maintains the plugin.\n"
        val PROJECT = MobileMemoryProject("/work/agents-deck", "agents-deck")
    }
}
