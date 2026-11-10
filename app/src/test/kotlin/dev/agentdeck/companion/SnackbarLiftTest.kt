package dev.agentdeck.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.ui.LocalSnackbarLift
import dev.agentdeck.companion.ui.height
import dev.agentdeck.companion.ui.liftsSnackbar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen transition keeps the outgoing composer composed beside the incoming one; its leaving
 * must not drop the incoming composer's lift, or the next snackbar covers Send again (J13).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnackbarLiftTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the outgoing composer leaving keeps the incoming one's lift`() {
        val lift = mutableStateMapOf<Any, Dp>()
        val outgoing = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalSnackbarLift provides lift) {
                if (outgoing.value) Box(Modifier.liftsSnackbar().height(120.dp))
                Box(Modifier.liftsSnackbar().height(80.dp))
            }
        }
        compose.runOnIdle { assertEquals(120.dp, lift.height()) }
        outgoing.value = false
        compose.runOnIdle { assertEquals(80.dp, lift.height()) }
    }
}
