package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSessionSpend
import com.github.claudeagents.core.mobile.MobileSessionSpendQuery
import com.github.claudeagents.core.mobile.MobileSessionSpendRequest
import com.google.gson.JsonParser
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.SessionSpendFlow
import dev.agentdeck.companion.data.SpendForm
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
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * A chat's own spend limits from the phone: the sheet reads what the machine holds, the whole form is
 * saved in one request, and what the reader typed survives everything but the machine taking it. A
 * refusal is the machine's sentence with the typed form and the saved limits both unchanged; a phone
 * without the grant is told so in the machine's words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionSpendFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<String>()
    @Volatile private var held = MobileSessionSpend(KEY, useDefaults = true, prompt = "Stop and hand off.")
    @Volatile private var heldDefaults = MobileSessionSpend("", useDefaults = false, hard = "8", prompt = "Stop and hand off.", revision = 4)
    @Volatile private var refuseWith: MobileRefusal? = null
    @Volatile private var refuseForm: String? = null
    @Volatile private var generation = 0L

    private val flow = SessionSpendFlow(scope, { bridge() }, { generation }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `the sheet opens on the chat and reads what the machine holds`() {
        flow.open(KEY, "Fix the parser")

        assertEquals("Fix the parser", flow.sheet.value?.title)
        val sheet = eventually { flow.sheet.value?.takeIf { it.form != null } }
        assertTrue("a chat with no limits of its own says it uses the defaults", sheet.form!!.useDefaults)
        assertFalse("nothing typed yet", sheet.dirty)
        assertEquals(listOf("GET $KEY"), sent)
    }

    @Test fun `the whole form is saved in one request and the sheet then holds what the machine normalised`() {
        readySheet()

        flow.edit(SpendForm(useDefaults = false, soft = "2.50", hard = "10", session = "90", prompt = "Write it down.", startNewChat = true))
        assertTrue(flow.sheet.value!!.dirty)
        flow.save()

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && !it.dirty && it.saved?.useDefaults == false } }
        assertEquals("2.5", sheet.form!!.soft)
        assertEquals("10", sheet.form!!.hard)
        assertNull(sheet.refused)
        assertEquals("POST soft=2.50 hard=10 session=90 weekly= startNewChat=true", sent.last())
    }

    @Test fun `using the global defaults again drops the chat's own limits`() {
        held = MobileSessionSpend(KEY, useDefaults = false, hard = "5", prompt = "Stop.")
        readySheet()

        flow.edit(flow.sheet.value!!.form!!.copy(useDefaults = true))
        flow.save()

        eventually { flow.sheet.value?.takeIf { !it.busy && it.saved?.useDefaults == true } }
        assertEquals("POST useDefaults", sent.last())
    }

    @Test fun `a form the desk refuses keeps the typed text and the saved limits and shows the desk's sentence`() {
        refuseForm = "6"
        readySheet()
        flow.edit(SpendForm(useDefaults = false, soft = "6", hard = "5", prompt = "Stop."))

        flow.save()

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && it.refused != null } }
        assertEquals("Soft limit must be lower than hard limit.", sheet.refused)
        assertEquals("6", sheet.form!!.soft)
        assertTrue("the saved limits are the machine's, unchanged", sheet.saved!!.useDefaults)
        assertTrue("the form is still unsaved", sheet.dirty)
        flow.edit(sheet.form!!.copy(soft = "1"))
        assertNull("the next edit takes the sentence away", flow.sheet.value?.refused)
    }

    @Test fun `saving sends nothing while the form is what the machine holds`() {
        readySheet()

        flow.save()

        assertEquals(listOf("GET $KEY"), sent)
    }

    @Test fun `a phone without the grant reads the machine's refusal and no form`() {
        refuseWith = MobileRefusal.PERMISSIONS_DISABLED

        flow.open(KEY, "Fix the parser")

        val sheet = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals(MobileRefusal.PERMISSIONS_DISABLED.message, sheet.error)
        assertNull(sheet.form)
    }

    @Test fun `a typed form survives closing and reopening the sheet, and a different machine drops it`() {
        readySheet()
        val typed = SpendForm(useDefaults = false, hard = "3", prompt = "Stop.")
        flow.edit(typed)
        flow.dismiss()
        assertNull(flow.sheet.value)

        flow.open(KEY, "Fix the parser")
        val reopened = eventually { flow.sheet.value?.takeIf { it.form != null } }
        assertEquals(typed, reopened.form)
        assertTrue(reopened.dirty)

        flow.forget()
        assertNull(flow.sheet.value)
        flow.open(KEY, "Fix the parser")
        assertFalse(eventually { flow.sheet.value?.takeIf { it.form != null } }.dirty)
    }

    @Test fun `a typed form is dropped when the machine's limits changed while the sheet was closed`() {
        readySheet()
        flow.edit(SpendForm(useDefaults = false, hard = "3", prompt = "Stop."))
        flow.dismiss()
        // The owner changes the chat's limits at the desk meanwhile: Save must not write the old base back.
        held = MobileSessionSpend(KEY, useDefaults = false, hard = "20", prompt = "Stop and hand off.")

        flow.open(KEY, "Fix the parser")

        val reopened = eventually { flow.sheet.value?.takeIf { it.form != null } }
        assertEquals("20", reopened.form!!.hard)
        assertFalse(reopened.dirty)
    }

    @Test fun `an answer after the sheet moved to another chat paints nothing on it`() {
        flow.open(KEY, "First")
        eventually { flow.sheet.value?.takeIf { it.form != null } }
        flow.open("claude:/repo:other", "Second")
        eventually { flow.sheet.value?.takeIf { it.form != null } }

        assertEquals("Second", flow.sheet.value?.title)
        assertEquals("claude:/repo:other", flow.sheet.value?.key)
    }

    @Test fun `the defaults sheet reads the machine-wide form and has no chat`() {
        flow.openDefaults()

        val sheet = eventually { flow.sheet.value?.takeIf { it.form != null } }
        assertTrue(sheet.defaults)
        assertEquals("", sheet.key)
        assertEquals("8", sheet.form!!.hard)
        assertFalse("the defaults are nobody's override", sheet.form!!.useDefaults)
        assertEquals(4L, sheet.revision)
        assertEquals(listOf("GET defaults"), sent)
    }

    @Test fun `a defaults save names the generation it read and takes the one the machine answers`() {
        flow.openDefaults()
        eventually { flow.sheet.value?.takeIf { it.form != null } }

        flow.edit(flow.sheet.value!!.form!!.copy(hard = "12"))
        flow.save()

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && it.revision == 5L } }
        assertEquals("POST defaults revision=4 hard=12", sent.last())
        assertEquals("12", sheet.form!!.hard)
        assertFalse(sheet.dirty)
        assertNull(sheet.refused)
    }

    @Test fun `a defaults save over a newer generation keeps the typed text beside the machine's sentence and its new generation`() {
        flow.openDefaults()
        eventually { flow.sheet.value?.takeIf { it.form != null } }
        // The desk saves between this read and this save.
        heldDefaults = heldDefaults.copy(hard = "20", revision = 9)

        flow.edit(flow.sheet.value!!.form!!.copy(hard = "12"))
        flow.save()

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && it.refused != null } }
        assertEquals("The defaults were changed on the computer since you opened them. Their current values are shown.", sheet.refused)
        assertEquals("what was typed stays", "12", sheet.form!!.hard)
        assertEquals("the saved side is what the desk holds now", "20", sheet.saved!!.hard)
        assertEquals(9L, sheet.revision)
        assertTrue(sheet.dirty)

        // Saving again is a knowing choice, over the generation just shown.
        flow.save()
        eventually { flow.sheet.value?.takeIf { !it.busy && it.refused == null && it.revision == 10L } }
        assertEquals("POST defaults revision=9 hard=12", sent.last())
    }

    @Test fun `defaults and a chat keep separate drafts`() {
        readySheet()
        flow.edit(SpendForm(useDefaults = false, hard = "3", prompt = "Stop."))
        flow.openDefaults()
        eventually { flow.sheet.value?.takeIf { it.defaults && it.form != null } }
        flow.edit(flow.sheet.value!!.form!!.copy(hard = "30"))
        flow.dismiss()

        flow.open(KEY, "Fix the parser")
        val chat = eventually { flow.sheet.value?.takeIf { it.form != null } }
        assertEquals("3", chat.form!!.hard)
        flow.openDefaults()
        val defaults = eventually { flow.sheet.value?.takeIf { it.defaults && it.form != null } }
        assertEquals("30", defaults.form!!.hard)
    }

    private fun readySheet() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.form != null } }
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
                if (url.path != MobileSessionSpendQuery.ROUTE) return 404
                val refused = refuseWith
                if (requestMethod == "POST") {
                    val request = MobileSessionSpendRequest.fromJson(JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject)
                    if (request.defaults) {
                        sent += "POST defaults revision=${request.revision} hard=${request.hard}"
                        if (request.revision != heldDefaults.revision) {
                            answer = heldDefaults.copy(refused = "The defaults were changed on the computer since you opened them. Their current values are shown.").toJson().toString()
                            return 200
                        }
                        heldDefaults = heldDefaults.copy(hard = request.hard, prompt = request.prompt, revision = heldDefaults.revision + 1)
                        answer = heldDefaults.toJson().toString()
                        return 200
                    }
                    sent += "POST " + if (request.useDefaults) "useDefaults" else
                        "soft=${request.soft} hard=${request.hard} session=${request.session} weekly=${request.weekly} startNewChat=${request.startNewChat}"
                    if (refused == null) {
                        if (request.soft == refuseForm) {
                            answer = held.copy(refused = "Soft limit must be lower than hard limit.").toJson().toString()
                            return 200
                        }
                        held = if (request.useDefaults) MobileSessionSpend(KEY, useDefaults = true, prompt = "Stop and hand off.") else MobileSessionSpend(
                            KEY, false,
                            soft = request.soft.toDoubleOrNull()?.toString()?.removeSuffix(".0").orEmpty(),
                            hard = request.hard.toDoubleOrNull()?.toString()?.removeSuffix(".0").orEmpty(),
                            session = request.session, weekly = request.weekly, prompt = request.prompt, startNewChat = request.startNewChat,
                        )
                    }
                } else if (url.query == "defaults=1") {
                    sent += "GET defaults"
                    answer = heldDefaults.toJson().toString()
                    return 200
                } else {
                    sent += "GET " + java.net.URLDecoder.decode(url.query.removePrefix("key="), "UTF-8")
                }
                if (refused != null) {
                    answer = """{"v":1,"error":"${refused.code}","message":"${refused.message}"}"""
                    return refused.status
                }
                answer = held.toJson().toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "claude:/repo:chat"
    }
}
