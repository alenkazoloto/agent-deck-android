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

    private fun zip(vararg names: String): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.ZipOutputStream(out).use { zip ->
            for (name in names) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write("hello".toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun `a ZIP archive goes up as an archive under its own name when the machine takes archives`() {
        helloCapabilities += MobileProtocol.Capability.ATTACHMENT_ZIP
        val model = opened()
        settle { MobileProtocol.Capability.ATTACHMENT_ZIP in model.state.value.hello?.capabilities.orEmpty() }
        val archive = zip("src/Main.kt", "README.md")

        model.attachFile(TARGET.key, "repro project.zip", archive)
        settle { model.state.value.pendingPhotos[TARGET.key] != null }

        val upload = uploads.single()
        assertEquals(MobileAttachment.ZIP_CONTENT_TYPE, upload.contentType)
        assertArrayEquals(archive, upload.body)
        assertEquals(listOf(PendingPhoto("att-1", "repro project.zip · 1 KB")), model.state.value.pendingPhotos[TARGET.key])
    }

    @Test fun `a ZIP archive is refused on the phone by a machine that does not take archives`() {
        val model = opened()

        model.attachFile(TARGET.key, "a.zip", zip("a.txt"))
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.contains("too old to take ZIP archives"))
        assertTrue(uploads.isEmpty())
    }

    private fun gzip(): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.GZIPOutputStream(out).use { it.write("hello, a compressed log line\n".repeat(20).toByteArray()) }
    }.toByteArray()

    @Test fun `a gzip file goes up as gzip under its own name when the machine takes it`() {
        helloCapabilities += MobileProtocol.Capability.ATTACHMENT_GZIP
        val model = opened()
        settle { MobileProtocol.Capability.ATTACHMENT_GZIP in model.state.value.hello?.capabilities.orEmpty() }
        val packed = gzip()

        model.attachFile(TARGET.key, "cache.tar.gz", packed)
        settle { model.state.value.pendingPhotos[TARGET.key] != null }

        val upload = uploads.single()
        assertEquals(MobileAttachment.GZIP_CONTENT_TYPE, upload.contentType)
        assertArrayEquals(packed, upload.body)
        assertEquals(listOf(PendingPhoto("att-1", "cache.tar.gz · 1 KB")), model.state.value.pendingPhotos[TARGET.key])
    }

    @Test fun `a gzip file is refused on the phone by a machine that does not take it`() {
        val model = opened()

        model.attachFile(TARGET.key, "a.gz", gzip())
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.contains("too old to take gzip files"))
        assertTrue(uploads.isEmpty())
    }

    /** One `ustar` entry: a 512-byte header with a true checksum, the data padded to a block, and the two zero blocks that end a tar. */
    private fun tar(name: String = "notes.txt"): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header)
        "0000644".toByteArray().copyInto(header, 100)
        "00000000005".toByteArray().copyInto(header, 124)
        header[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(header, 257)
        "00".toByteArray().copyInto(header, 263)
        val sum = (0 until 512).sumOf { if (it in 148..155) 0x20 else header[it].toInt() and 0xFF }
        String.format("%06o", sum).toByteArray().copyInto(header, 148)
        header[154] = 0
        header[155] = ' '.code.toByte()
        return header + "hello".toByteArray().copyOf(512) + ByteArray(1024)
    }

    @Test fun `a tar archive goes up as tar under its own name when the machine takes it`() {
        helloCapabilities += MobileProtocol.Capability.ATTACHMENT_TAR
        val model = opened()
        settle { MobileProtocol.Capability.ATTACHMENT_TAR in model.state.value.hello?.capabilities.orEmpty() }
        val archive = tar()

        model.attachFile(TARGET.key, "snapshot.tar", archive)
        settle { model.state.value.pendingPhotos[TARGET.key] != null }

        val upload = uploads.single()
        assertEquals(MobileAttachment.TAR_CONTENT_TYPE, upload.contentType)
        assertArrayEquals(archive, upload.body)
        assertEquals(listOf(PendingPhoto("att-1", "snapshot.tar · 2 KB")), model.state.value.pendingPhotos[TARGET.key])
    }

    @Test fun `a tar archive is refused on the phone by a machine that does not take it`() {
        val model = opened()

        model.attachFile(TARGET.key, "a.tar", tar())
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.contains("too old to take tar archives"))
        assertTrue(uploads.isEmpty())
    }

    /** A real `xz` stream of "hello\n" (check CRC32): stream header, one block, index, footer. */
    private fun xz(): ByteArray = java.util.Base64.getDecoder().decode("/Td6WFoAAAFpIt42BMAKBiEBFgAAAAAAAAAAAKowjqYBAAVoZWxsbwoAAAAgMDo2AAEiBj5WV26QQpkNAQAAAAABWVo=")

    @Test fun `an xz file goes up as xz under its own name when the machine takes it`() {
        helloCapabilities += MobileProtocol.Capability.ATTACHMENT_XZ
        val model = opened()
        settle { MobileProtocol.Capability.ATTACHMENT_XZ in model.state.value.hello?.capabilities.orEmpty() }
        val archive = xz()

        model.attachFile(TARGET.key, "logs.tar.xz", archive)
        settle { model.state.value.pendingPhotos[TARGET.key] != null }

        val upload = uploads.single()
        assertEquals(MobileAttachment.XZ_CONTENT_TYPE, upload.contentType)
        assertArrayEquals(archive, upload.body)
        assertEquals(listOf(PendingPhoto("att-1", "logs.tar.xz · 1 KB")), model.state.value.pendingPhotos[TARGET.key])
    }

    @Test fun `an xz file is refused on the phone by a machine that does not take it`() {
        val model = opened()

        model.attachFile(TARGET.key, "a.xz", xz())
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.contains("too old to take xz files"))
        assertTrue(uploads.isEmpty())
    }

    @Test fun `the refusal names xz files too when this machine takes them`() {
        assertEquals(
            "That file is not UTF-8 text, a PDF, a ZIP archive, a gzip file, a tar archive or an xz file — only photos, text files, PDFs, ZIP archives, gzip files, tar archives and xz files can be attached",
            dev.agentdeck.companion.data.TextFileAttachment.refusal(ByteArray(64) { 0 }, pdfSupported = true, zipSupported = true, gzipSupported = true, tarSupported = true, xzSupported = true),
        )
    }

    /** A real `bzip2` stream of "hello\n" (level 9): header, one block, end marker and combined CRC. */
    private fun bzip2(): ByteArray = java.util.Base64.getDecoder().decode("QlpoOTFBWSZTWcHAgOIAAAFBAAAQAkSgADDNAMNGKZcXckU4UJDBwIDi")

    @Test fun `a bzip2 file goes up as bzip2 under its own name when the machine takes it`() {
        helloCapabilities += MobileProtocol.Capability.ATTACHMENT_BZIP2
        val model = opened()
        settle { MobileProtocol.Capability.ATTACHMENT_BZIP2 in model.state.value.hello?.capabilities.orEmpty() }
        val archive = bzip2()

        model.attachFile(TARGET.key, "logs.tar.bz2", archive)
        settle { model.state.value.pendingPhotos[TARGET.key] != null }

        val upload = uploads.single()
        assertEquals(MobileAttachment.BZIP2_CONTENT_TYPE, upload.contentType)
        assertArrayEquals(archive, upload.body)
        assertEquals(listOf(PendingPhoto("att-1", "logs.tar.bz2 · 1 KB")), model.state.value.pendingPhotos[TARGET.key])
    }

    @Test fun `a bzip2 file is refused on the phone by a machine that does not take it`() {
        val model = opened()

        model.attachFile(TARGET.key, "a.bz2", bzip2())
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.contains("too old to take bzip2 files"))
        assertTrue(uploads.isEmpty())
    }

    @Test fun `the refusal names bzip2 files too when this machine takes them`() {
        assertEquals(
            "That file is not UTF-8 text, a PDF, a ZIP archive, a gzip file, a tar archive, an xz file or a bzip2 file — only photos, text files, PDFs, ZIP archives, gzip files, tar archives, xz files and bzip2 files can be attached",
            dev.agentdeck.companion.data.TextFileAttachment.refusal(ByteArray(64) { 0 }, pdfSupported = true, zipSupported = true, gzipSupported = true, tarSupported = true, xzSupported = true, bzip2Supported = true),
        )
    }

    /** A real `zstd` frame of "hello\n" (level 19, read from a pipe): magic, descriptor with a checksum, window, one raw block and the XXH64 tail. */
    private fun zstd(): ByteArray = java.util.Base64.getDecoder().decode("KLUv/QRoMQAAaGVsbG8KU4i9kQ==")

    @Test fun `a Zstandard file goes up as Zstandard under its own name when the machine takes it`() {
        helloCapabilities += MobileProtocol.Capability.ATTACHMENT_ZSTD
        val model = opened()
        settle { MobileProtocol.Capability.ATTACHMENT_ZSTD in model.state.value.hello?.capabilities.orEmpty() }
        val archive = zstd()

        model.attachFile(TARGET.key, "logs.tar.zst", archive)
        settle { model.state.value.pendingPhotos[TARGET.key] != null }

        val upload = uploads.single()
        assertEquals(MobileAttachment.ZSTD_CONTENT_TYPE, upload.contentType)
        assertArrayEquals(archive, upload.body)
        assertEquals(listOf(PendingPhoto("att-1", "logs.tar.zst · 1 KB")), model.state.value.pendingPhotos[TARGET.key])
    }

    @Test fun `a Zstandard file is refused on the phone by a machine that does not take it`() {
        val model = opened()

        model.attachFile(TARGET.key, "a.zst", zstd())
        settle { model.state.value.snack != null }

        assertTrue(model.state.value.snack!!.message.contains("too old to take Zstandard files"))
        assertTrue(uploads.isEmpty())
    }

    @Test fun `the refusal names Zstandard files too when this machine takes them`() {
        assertEquals(
            "That file is not UTF-8 text or a Zstandard file — only photos, text files and Zstandard files can be attached",
            dev.agentdeck.companion.data.TextFileAttachment.refusal(ByteArray(64) { 0 }, zstdSupported = true),
        )
    }

    @Test fun `the refusal for a file that is none of the accepted kinds names exactly the kinds this machine takes`() {
        val binary = ByteArray(64) { 0 }
        assertEquals("That file is not UTF-8 text — only photos and text files can be attached", dev.agentdeck.companion.data.TextFileAttachment.refusal(binary))
        assertTrue(dev.agentdeck.companion.data.TextFileAttachment.refusal(binary, zipSupported = true)!!.endsWith("only photos, text files and ZIP archives can be attached"))
        assertTrue(dev.agentdeck.companion.data.TextFileAttachment.refusal(binary, pdfSupported = true, zipSupported = true)!!.endsWith("only photos, text files, PDFs and ZIP archives can be attached"))
        assertEquals(
            "That file is not UTF-8 text, a PDF, a ZIP archive or a gzip file — only photos, text files, PDFs, ZIP archives and gzip files can be attached",
            dev.agentdeck.companion.data.TextFileAttachment.refusal(binary, pdfSupported = true, zipSupported = true, gzipSupported = true),
        )
        assertEquals(
            "That file is not UTF-8 text or a gzip file — only photos, text files and gzip files can be attached",
            dev.agentdeck.companion.data.TextFileAttachment.refusal(binary, gzipSupported = true),
        )
    }

    @Test fun `the refusal names tar archives too when this machine takes them`() {
        assertEquals(
            "That file is not UTF-8 text, a PDF, a ZIP archive, a gzip file or a tar archive — only photos, text files, PDFs, ZIP archives, gzip files and tar archives can be attached",
            dev.agentdeck.companion.data.TextFileAttachment.refusal(ByteArray(64) { 0 }, pdfSupported = true, zipSupported = true, gzipSupported = true, tarSupported = true),
        )
    }

    @Test fun `a pasted text goes up as a text file and shows as a chip counting its lines`() {
        val model = opened()
        val text = (1..6).joinToString("\n") { "line $it" }

        model.attachPastedText(TARGET.key, text)
        settle { model.state.value.pendingPhotos[TARGET.key] != null }

        val upload = uploads.single()
        assertEquals(MobileAttachment.TEXT_CONTENT_TYPE, upload.contentType)
        assertArrayEquals(text.toByteArray(), upload.body)
        assertEquals("pasted-text.txt", upload.name)
        assertEquals(listOf(PendingPhoto("att-1", "Pasted text (6 lines) · 1 KB")), model.state.value.pendingPhotos[TARGET.key])
    }

    @Test fun `a paste that cannot be attached comes back into the draft with the reason`() {
        val model = opened()
        repeat(MobileAttachment.MAX_PER_SEND) { i ->
            model.attachFile(TARGET.key, "f$i.txt", "x".toByteArray())
            settle { model.state.value.pendingPhotos[TARGET.key]?.size == i + 1 }
        }
        model.setDraft(TARGET.key, "look at this: ")

        model.attachPastedText(TARGET.key, "one\ntwo\nthree\nfour")
        settle { model.state.value.snack != null }

        assertEquals("look at this: one\ntwo\nthree\nfour", model.draft(TARGET.key))
        assertEquals("nothing more was uploaded", MobileAttachment.MAX_PER_SEND, uploads.size)
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
