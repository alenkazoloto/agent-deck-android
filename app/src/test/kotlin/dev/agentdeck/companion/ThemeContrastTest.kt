package dev.agentdeck.companion

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.agentdeck.companion.ui.DarkColors
import dev.agentdeck.companion.ui.LightColors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redesign's tokens are targets, not pre-approved pairs (PLAN-MOBILE-REDESIGN "measure every
 * actual combination"): the plan's secondary grey read 3.9:1 on the highest container, so every
 * text role the app paints is measured against every fill it paints that role on.
 */
class ThemeContrastTest {

    @Test fun `light scheme keeps WCAG AA text contrast`() = check("light", LightColors)

    @Test fun `dark scheme keeps WCAG AA text contrast`() = check("dark", DarkColors)

    private fun check(name: String, c: ColorScheme) {
        val fills = mapOf(
            "background" to c.background,
            "surface" to c.surface,
            "surfaceVariant" to c.surfaceVariant,
            "surfaceContainerLowest" to c.surfaceContainerLowest,
            "surfaceContainerLow" to c.surfaceContainerLow,
            "surfaceContainer" to c.surfaceContainer,
            "surfaceContainerHigh" to c.surfaceContainerHigh,
            "surfaceContainerHighest" to c.surfaceContainerHighest,
        )
        val pairs = buildList {
            fills.forEach { (fill, color) ->
                add("onSurface/$fill" to (c.onSurface to color))
                add("onSurfaceVariant/$fill" to (c.onSurfaceVariant to color))
            }
            // Accent text and links sit on the page, the canvas and a selected (tinted) row.
            listOf("background" to c.background, "surface" to c.surface,
                "surfaceContainerLow" to c.surfaceContainerLow, "primaryContainer" to c.primaryContainer)
                .forEach { (fill, color) -> add("primary/$fill" to (c.primary to color)) }
            add("onPrimary/primary" to (c.onPrimary to c.primary))
            add("onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer))
            add("onSecondaryContainer/secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer))
            add("onTertiaryContainer/tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer))
            add("onError/error" to (c.onError to c.error))
            add("onErrorContainer/errorContainer" to (c.onErrorContainer to c.errorContainer))
            add("error/background" to (c.error to c.background))
            add("inverseOnSurface/inverseSurface" to (c.inverseOnSurface to c.inverseSurface))
        }
        val failures = pairs.mapNotNull { (label, pair) ->
            val ratio = contrast(pair.first, pair.second)
            if (ratio < 4.5) "$label = ${"%.2f".format(ratio)}" else null
        }
        assertTrue("$name pairs under 4.5:1: $failures", failures.isEmpty())
    }

    private fun contrast(a: Color, b: Color): Double {
        val (dark, light) = listOf(a.luminance(), b.luminance()).sorted()
        return (light + 0.05) / (dark + 0.05)
    }
}
