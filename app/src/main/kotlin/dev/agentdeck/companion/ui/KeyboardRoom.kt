package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

/**
 * Whether the keyboard leaves too little height for the top bar *and* the field being typed into.
 *
 * A phone on its side with the keyboard up can have about a hundred dp left; the top bar took
 * all of it and the composer went under the keyboard (J14, `M2JourneysE2eTest`). The need is the
 * bar plus a composer that grows with the text size, so a 412 dp landscape phone at default text
 * keeps its bar, title, Find and Outline while typing, and the same phone at 200 % does not.
 */
object KeyboardRoom {
    private const val BAR_DP = 64f
    private const val COMPOSER_DP = 120f

    @Composable
    fun crowded(): Boolean {
        val density = LocalDensity.current
        val keyboardDp = WindowInsets.ime.getBottom(density) / density.density
        if (keyboardDp <= 0f) return false
        val room = LocalConfiguration.current.screenHeightDp - keyboardDp
        return room < BAR_DP + COMPOSER_DP * density.fontScale
    }
}
