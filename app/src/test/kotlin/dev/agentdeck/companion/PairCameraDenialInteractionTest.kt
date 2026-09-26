package dev.agentdeck.companion

import android.Manifest
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.PairScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * t3code #6486: after "Don't allow" twice, Android answers the camera request without showing
 * anything, so the QR button did nothing for good. The form now says so and offers the app's own
 * settings page, the only place the permission can come back from.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PairCameraDenialInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** What the system prompt answers; nothing is ever shown. */
    private var granted = false

    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            dispatchResult(requestCode, granted)
        }
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                },
            ) {
                AgentDeckTheme {
                    PairScreen(state = DeckState(), onPair = { _, _, _, _, _ -> }, onScanned = { _, _ -> true }, onDismissError = {})
                }
            }
        }
    }

    private fun canAskAgain(value: Boolean) {
        val packages = ApplicationProvider.getApplicationContext<android.app.Application>().packageManager
        shadowOf(packages).setShouldShowRequestPermissionRationale(Manifest.permission.CAMERA, value)
    }

    @Test
    fun `a camera refused for good offers the app's settings page, and the button opens it`() {
        canAskAgain(false)
        show()

        compose.onNodeWithText("Scan the QR code").performClick()

        compose.onNodeWithText("Open app settings").assertIsDisplayed()
        compose.onNodeWithText("Camera access is blocked", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Open app settings").performClick()

        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${compose.activity.packageName}", opened.data.toString())
    }

    /** Negative control: the first refusal can still be undone by asking again, so no detour. */
    @Test
    fun `a first refusal only says the camera is off and offers no settings button`() {
        canAskAgain(true)
        show()

        compose.onNodeWithText("Scan the QR code").performClick()

        compose.onNodeWithText("Camera access is off", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Open app settings").assertCountEquals(0)
    }

    @Test
    fun `granting the camera in settings clears the message and the button on return`() {
        canAskAgain(false)
        show()
        compose.onNodeWithText("Scan the QR code").performClick()
        compose.onNodeWithText("Open app settings").assertIsDisplayed()

        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onAllNodesWithText("Open app settings").assertCountEquals(0)
        compose.onAllNodesWithText("Camera access is blocked", substring = true).assertCountEquals(0)
    }

    /** Still blocked on return means nothing was granted: the way to the settings page must stay. */
    @Test
    fun `returning from settings without a grant keeps the button`() {
        canAskAgain(false)
        show()
        compose.onNodeWithText("Scan the QR code").performClick()

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithText("Open app settings").assertIsDisplayed()
    }
}
