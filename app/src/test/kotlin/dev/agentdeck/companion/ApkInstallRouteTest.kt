package dev.agentdeck.companion

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.agentdeck.companion.data.ApkInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Every screen this app sends the reader to on the way to an install.
 *
 * The view model has no activity — it holds the `Application` — and Android refuses
 * `startActivity` from a non-activity context unless the intent carries
 * [Intent.FLAG_ACTIVITY_NEW_TASK]. `ApkInstall` swallows the throw (`runCatching`) and no caller
 * reads the boolean, so a missing flag is not a crash and not a message: it is a button that
 * does nothing. That is what "install unknown apps" was — the download landed, the install was
 * refused, the app said "turn that on and press Install again", and the page that turns it on
 * never opened, on this emulator, at versionCode 3 offered 4.
 *
 * Asserted per departure rather than once, because the flag lived at two of the three sites and
 * the third was written without it.
 */
@RunWith(RobolectricTestRunner::class)
class ApkInstallRouteTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** Red before the fix: nothing is started at all, and `requestPermission` returns false. */
    @Test
    fun `the unknown-sources page opens from the application context`() {
        assertTrue("the page that turns on installs must open", ApkInstall.requestPermission(context))
        val intent = nextActivity()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
        assertNewTask(intent)
    }

    @Test
    fun `the release page opens from the application context`() {
        assertTrue(ApkInstall.openPage(context, "https://github.com/o/r/releases/latest"))
        assertNewTask(nextActivity())
    }

    @Test
    fun `the installer opens on the downloaded file from the application context`() {
        val apk = ApkInstall.dir(context).resolve("agent-deck-4.apk").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4))
        }
        assertTrue(ApkInstall.launch(context, apk))
        val intent = nextActivity()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(
            "the installer cannot read a content:// URI it was not granted",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertNewTask(intent)
    }

    /** A blank address is not a page, and must not be started. */
    @Test
    fun `a blank release address starts nothing`() {
        assertEquals(false, ApkInstall.openPage(context, "  "))
        assertEquals(null, Shadows.shadowOf(context).nextStartedActivity)
    }

    private fun nextActivity(): Intent =
        requireNotNull(Shadows.shadowOf(context).nextStartedActivity) {
            "no activity was started — the intent never left this app"
        }.also { assertNotNull(it.action) }

    private fun assertNewTask(intent: Intent) = assertTrue(
        "a start from the Application context needs FLAG_ACTIVITY_NEW_TASK",
        intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
    )
}
