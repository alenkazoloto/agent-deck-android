package dev.agentdeck.companion

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobileDeepLink
import com.github.claudeagents.core.mobile.MobilePush
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.NotifyTrigger
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.notify.PlanUsageNotice
import dev.agentdeck.companion.push.PushRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The desk's plan-usage warning arriving as a push to a phone whose app is closed — through
 * [PushRegistration.show], the receiver's own route, into the shade.
 *
 * The three things a reader would notice: it is off until they turn it on (a plan window is not
 * an agent waiting on them), it says what the desk said, and a second push about the same window
 * — two IDEs on one machine each send one — is one row, not two.
 */
@RunWith(RobolectricTestRunner::class)
class PlanUsagePushTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = app.getSystemService(NotificationManager::class.java)
    }

    private fun payload(percent: Int = 85, reset: Long = RESET, text: String = "18:30") = MobilePush.Payload(
        MobilePush.Trigger.PLAN_USAGE,
        emptyList(),
        1,
        MobilePush.PlanUsageAlert(percent, reset, text),
    )

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun `a plan window alert is off until the reader turns it on`() {
        assertFalse(NotifyTrigger.PLAN_USAGE in AppSettings().triggers)

        PushRegistration.show(app, payload())

        assertTrue("an alert nobody asked for was posted", posted().isEmpty())
    }

    @Test
    fun `it says what the desk said and opens Usage`() {
        store.saveSettings(AppSettings(triggers = setOf(NotifyTrigger.PLAN_USAGE)))

        PushRegistration.show(app, payload())

        val notification = posted().single()
        assertEquals("Plan usage at 85%", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("The 5-hour window resets 18:30.", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(NotifyTrigger.PLAN_USAGE.id, notification.channelId)
        val link = shadowOf(notification.contentIntent).savedIntent.data.toString()
        assertEquals(MobileDeepLink.USAGE, link)
    }

    @Test
    fun `a second push about the same window replaces the row and the next window adds one`() {
        store.saveSettings(AppSettings(triggers = setOf(NotifyTrigger.PLAN_USAGE)))

        PushRegistration.show(app, payload())
        PushRegistration.show(app, payload(percent = 91))
        assertEquals("the same window stacked two rows", 1, posted().size)
        assertEquals("Plan usage at 91%", posted().single().extras.getString(Notification.EXTRA_TITLE))

        PushRegistration.show(app, payload(reset = RESET + 5 * 3_600_000L))
        assertEquals(2, posted().size)
    }

    @Test
    fun `the channel is its own and less urgent than an agent waiting`() {
        store.saveSettings(AppSettings(triggers = setOf(NotifyTrigger.PLAN_USAGE)))

        PushRegistration.show(app, payload())

        val channel = manager.getNotificationChannel(NotifyTrigger.PLAN_USAGE.id)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(NotifyTrigger.NEEDS_YOU.id).importance)
    }

    @Test
    fun `the settings row survives a restart`() {
        val saved = AppSettings(triggers = setOf(NotifyTrigger.NEEDS_YOU, NotifyTrigger.PLAN_USAGE))

        assertEquals(saved.triggers, AppSettings.fromJson(saved.toJson()).triggers)
    }

    @Test
    fun `no blank reset leaves a dangling sentence`() {
        assertEquals("The 5-hour window is filling up.", PlanUsageNotice.body(MobilePush.PlanUsageAlert(80, RESET, "")))
    }

    @Test
    fun `an alert with no plan payload is not this trigger's to draw`() {
        assertNull(MobilePush.Payload.fromJson(MobilePush.Payload(MobilePush.Trigger.PLAN_USAGE, emptyList(), 1).toJson()))
    }

    private companion object {
        const val RESET = 1_800_000_000_000L
    }
}
