package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.MachinePicker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * J13: the root bar names the machine whose work is listed — with one pairing as well as two —
 * and the same control switches machines or starts pairing another.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MachinePickerInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val switched = mutableListOf<String>()
    private var added = 0

    private fun show(machines: List<PairedMachine>, active: PairedMachine) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            MachinePicker(machines, active, onSwitch = { switched += it }, onAdd = { added++ })
        }
    }

    @Test fun `a single pairing is still named and offers pairing another`() {
        show(listOf(DESK), DESK)
        compose.onNodeWithContentDescription("Machine: desk. Switch or pair a machine").assertIsDisplayed()
            .performClick()
        compose.onNodeWithText("Pair another machine…").performClick()
        assertEquals(1, added)
        assertEquals(emptyList<String>(), switched)
    }

    @Test fun `picking another machine switches to it, picking the active one does nothing`() {
        show(listOf(DESK, LAPTOP), DESK)
        compose.onNodeWithContentDescription("Machine: desk. Switch or pair a machine").performClick()
        compose.onNodeWithText("laptop").performClick()
        assertEquals(listOf(LAPTOP.id), switched)
        compose.onNodeWithContentDescription("Machine: desk. Switch or pair a machine").performClick()
        // The bar and the menu both read "desk"; the menu's item is the last one composed.
        compose.onAllNodesWithText("desk", useUnmergedTree = true).onLast().performClick()
        assertEquals(listOf(LAPTOP.id), switched)
        assertEquals(0, added)
    }

    private companion object {
        val DESK = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
        val LAPTOP = DESK.copy(machineName = "laptop", deviceId = "device-2")
    }
}
