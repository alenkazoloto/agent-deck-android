package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileAttachment
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.PendingPhoto
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * A text file picked in the composer, through the view model and a real [BridgeClient]: what goes
 * on the wire (the declared type and the file's own bytes), what the reader sees (a chip named for
 * the file, and a sentence for one the phone will not upload), and that the send names the id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextFileAttachTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore

    private class Upload(val contentType: String?, val body: ByteArray, val name: String? = null)

    private val uploads = mutableListOf<Upload>()
    private val sendBodies = mutableListOf<String>()
    private var helloCapabilities = listOf(MobileProtocol.Capability.ATTACHMENTS, MobileProtocol.Capability.ATTACHMENT_TEXT)

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
    }

    @After fun tearDown() {
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `a text file goes up as text and shows as a chip named for it`() {
        val model = opened()
        val text = "fun main() {\n\tprintln(\"héllo\")\n}\n".toByteArray()

        model.attachFile(TARGET.key, "Main.kt", text)
        settle { model.state.value.pendingPhotos[TARGET.key] != null }

        val upload = uploads.single()
        assertEquals("the machine reads the declared type to pick its reader", MobileAttachment.TEXT_CONTENT_TYPE, upload.contentType)
        assertArrayEquals("the file's own bytes, not a re-encode", text, upload.body)
        assertEquals(listOf(PendingPhoto("att-1", "Main.kt · 1 KB")), model.state.value.pendingPhotos[TARGET.key])
        assertTrue("the attach spinner must not outlive the upload", !model.state.value.attaching)
    }

    @Test fun `the send names the file's id beside the typed words`() {
        val model = opened()
        model.attachFile(TARGET.key, "notes.md", "# notes\n".toByteArray())
        settle { model.state.value.pendingPhotos[TARGET.key] != null }

        model.send(TARGET, "read this", stopFirst = false)
        settle { sendBodies.isNotEmpty() }

        val body = MobileProtocol.parseObject(sendBodies.single())!!
        assertEquals(listOf("att-1"), body.getAsJsonArray("attachmentIds").map { it.asString })
        assertNull("the chip goes once the send consumed it", model.state.value.pendingPhotos[TARGET.key])
    }

    @Test fun `a binary file is refused on the phone with the reason and never uploaded`() {
        val model = opened()

        model.attachFile(TARGET.key, "archive.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0))
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.contains("not UTF-8 text"))
        assertTrue("nothing was uploaded", uploads.isEmpty())
        assertNull(model.state.value.pendingPhotos[TARGET.key])
        assertTrue(!model.state.value.attaching)
    }

    @Test fun `a file over the text bound is refused with its bound`() {
        val model = opened()

        model.attachFile(TARGET.key, "big.log", ByteArray(MobileAttachment.MAX_TEXT_BYTES + 1) { 'a'.code.toByte() })
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.contains("over 1 MB"))
        assertTrue(uploads.isEmpty())
    }

    @Test fun `a PDF goes up as a PDF under its own name when the machine takes PDFs`() {
        helloCapabilities += MobileProtocol.Capability.ATTACHMENT_PDF
        val model = opened()
        settle { MobileProtocol.Capability.ATTACHMENT_PDF in model.state.value.hello?.capabilities.orEmpty() }
        val pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n%%EOF\n".toByteArray()

        model.attachFile(TARGET.key, "Q3 report é.pdf", pdf)
        settle { model.state.value.pendingPhotos[TARGET.key] != null }

        val upload = uploads.single()
        assertEquals(MobileAttachment.PDF_CONTENT_TYPE, upload.contentType)
        assertArrayEquals(pdf, upload.body)
        assertEquals("the name travels percent-encoded", "Q3+report+%C3%A9.pdf", upload.name)
        assertEquals(listOf(PendingPhoto("att-1", "Q3 report é.pdf · 1 KB")), model.state.value.pendingPhotos[TARGET.key])
    }

    @Test fun `a PDF is refused on the phone by a machine that does not take PDFs`() {
        val model = opened()

        model.attachFile(TARGET.key, "a.pdf", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n%%EOF\n".toByteArray())
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.contains("too old to take PDFs"))
        assertTrue(uploads.isEmpty())
    }

    @Test fun `a PDF over its bound is refused with its bound`() {
        helloCapabilities += MobileProtocol.Capability.ATTACHMENT_PDF
        val model = opened()
        settle { MobileProtocol.Capability.ATTACHMENT_PDF in model.state.value.hello?.capabilities.orEmpty() }

        model.attachFile(TARGET.key, "big.pdf", "%PDF-1.4\n".toByteArray() + ByteArray(MobileAttachment.MAX_PDF_BYTES) + "%%EOF".toByteArray())
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.contains("over 8 MB"))
        assertTrue(uploads.isEmpty())
    }

    @Test fun `files and photos share one per-message ceiling`() {
        val model = opened()
        repeat(MobileAttachment.MAX_PER_SEND) { i ->
            model.attachFile(TARGET.key, "f$i.txt", "x".toByteArray())
            settle { model.state.value.pendingPhotos[TARGET.key]?.size == i + 1 }
        }

        model.attachFile(TARGET.key, "one-more.txt", "x".toByteArray())
        settle { model.state.value.snack != null }

        assertEquals("Up to ${MobileAttachment.MAX_PER_SEND} photos and files per message", model.state.value.snack?.message)
        assertEquals(MobileAttachment.MAX_PER_SEND, uploads.size)
    }

    // ---- harness -------------------------------------------------------------------------

    private fun opened(): DeckViewModel {
        val model = DeckViewModel(app).also { it.connectionForTest = { fakeBridge() } }
        model.open(DeepLink.Conversation(TARGET.key, TARGET.title, TARGET.vendor, TARGET.projectPath))
        settle { model.state.value.screen is Screen.Conversation }
        return model
    }

    private fun settle(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            ShadowLooper.idleMainLooper()
            if (until()) return
        }
        throw AssertionError("never settled")
    }

    private fun fakeBridge() = BridgeClient(listOf("machine.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream() = body
            private fun reply(): Pair<Int, String> = when {
                url.path.endsWith("/v1/hello") -> 200 to """{"v":1,"capabilities":[${helloCapabilities.joinToString(",") { "\"$it\"" }}]}"""
                url.path.endsWith(MobileAttachment.ROUTE) -> 200 to """{"v":1,"attachmentId":"att-${uploads.size}","bytes":1}"""
                url.path.endsWith("/v1/send") -> 200 to """{"v":1,"taskId":"t-1","state":"queued"}"""
                url.path.startsWith("/v1/session/") -> 200 to """{"v":1,"key":"${TARGET.key}","title":"${TARGET.title}","turns":[]}"""
                else -> 200 to "{}"
            }
            override fun getResponseCode(): Int {
                if (url.path.endsWith(MobileAttachment.ROUTE)) uploads += Upload(getRequestProperty("Content-Type"), body.toByteArray(), getRequestProperty(MobileAttachment.NAME_HEADER))
                if (url.path.endsWith("/v1/send")) sendBodies += body.toString(Charsets.UTF_8)
                return reply().first
            }
            override fun getErrorStream(): InputStream = reply().second.byteInputStream()
            override fun getInputStream(): InputStream = reply().second.byteInputStream()
        }
    }

    private companion object {
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
        val TARGET = Screen.Conversation(
            key = "claude:/repo:session", title = "Free the port",
            vendor = AgentVendor.CLAUDE, projectPath = "/repo",
        )
    }
}
