package dev.agentdeck.companion

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobilePush
import com.google.gson.JsonParser
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.NotifyTrigger
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.notify.RetryLoopNotice
import dev.agentdeck.companion.push.PushRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A running chat stuck in a retry loop, pushed to a phone whose app is closed — through
 * [PushRegistration.show] into the shade (PLAN-MOBILE-HEALTH-PUSH M3).
 */
@RunWith(RobolectricTestRunner::class)
class RetryLoopPushTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var manager: NotificationManager

    private val loop = MobilePush.RetryLoopAlert("COMMAND_FAILED", "Command exited non-zero", "Bash", 6, 1_000L)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = app.getSystemService(NotificationManager::class.java)
    }

    private fun payload() = MobilePush.Payload(MobilePush.Trigger.RETRY_LOOP, listOf("loop-key"), 1, retryLoop = loop)

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun `it names the tool, the count and the desk's failure, and offers Stop`() {
        PushRegistration.show(app, payload())

        val notification = posted().single()
        assertEquals(
            "Bash failed 6 times in a row: command exited non-zero.",
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(NotifyTrigger.RETRY_LOOP.id, notification.channelId)
        assertTrue("a running loop must be stoppable from the shade", notification.actions.any { it.title == "Stop" })
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(NotifyTrigger.RETRY_LOOP.id).importance,
        )
    }

    @Test
    fun `it is on by default, including for a phone that saved its choices before the row existed`() {
        assertTrue(NotifyTrigger.RETRY_LOOP in AppSettings().triggers)

        val upgraded = JsonParser.parseString("""{"v":1,"triggers":["needs-you"]}""").asJsonObject
        // A blob that predates both rows gains both; `HandoffAskTest` pins the handoff half.
        assertEquals(
            setOf(NotifyTrigger.NEEDS_YOU, NotifyTrigger.RETRY_LOOP, NotifyTrigger.HANDOFF_ASK),
            AppSettings.fromJson(upgraded).triggers,
        )

        val off = AppSettings(triggers = setOf(NotifyTrigger.NEEDS_YOU))
        assertFalse("a reader who turned it off must stay off", NotifyTrigger.RETRY_LOOP in AppSettings.fromJson(off.toJson()).triggers)
    }

    @Test
    fun `turned off, nothing is posted`() {
        store.saveSettings(AppSettings(triggers = setOf(NotifyTrigger.NEEDS_YOU)))

        PushRegistration.show(app, payload())

        assertTrue(posted().isEmpty())
    }

    @Test
    fun `a failure kind with no label, or no tool, still reads as a sentence`() {
        assertEquals("A tool failed 7 times in a row.", RetryLoopNotice.body(loop.copy(tool = "", label = "", run = 7)))
    }
}
