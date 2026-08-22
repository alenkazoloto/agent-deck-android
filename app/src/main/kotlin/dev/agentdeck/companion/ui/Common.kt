package dev.agentdeck.companion.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.github.claudeagents.core.AgentVendor
import java.util.Calendar
import java.util.Locale

/**
 * The full Material 3 role set, not three overrides on the baseline.
 *
 * Overriding only `primary`/`secondary`/`tertiary` leaves every *container* and *surface* role
 * at Material's purple baseline, which is what shipped: a blue-primary app whose user bubbles,
 * chips and selected states were all lavender (docs/img/2026-08-01-img_2.png).
 * The roles are filled here so tonal surfaces and their `on*` pairs come from one hue family
 * and every pairing keeps its contrast.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF0A1F3D),
    primaryContainer = Color(0xFF24406E),
    onPrimaryContainer = Color(0xFFD3E1FF),
    inversePrimary = Color(0xFF2F5BB7),
    secondary = Color(0xFF9ECE6A),
    onSecondary = Color(0xFF14290A),
    secondaryContainer = Color(0xFF2E4620),
    onSecondaryContainer = Color(0xFFDDF2C4),
    tertiary = Color(0xFFE0AF68),
    onTertiary = Color(0xFF3A2708),
    tertiaryContainer = Color(0xFF55401A),
    onTertiaryContainer = Color(0xFFFFE0B0),
    error = Color(0xFFF7768E),
    onError = Color(0xFF40101C),
    errorContainer = Color(0xFF64202F),
    onErrorContainer = Color(0xFFFFD9DF),
    background = Color(0xFF16161E),
    onBackground = Color(0xFFC6C9D6),
    surface = Color(0xFF16161E),
    onSurface = Color(0xFFC6C9D6),
    surfaceVariant = Color(0xFF2A2C3A),
    onSurfaceVariant = Color(0xFFB0B4C6),
    surfaceContainerLowest = Color(0xFF0E0E14),
    surfaceContainerLow = Color(0xFF1A1B24),
    surfaceContainer = Color(0xFF1E1F29),
    surfaceContainerHigh = Color(0xFF282A36),
    surfaceContainerHighest = Color(0xFF333544),
    outline = Color(0xFF6B7089),
    outlineVariant = Color(0xFF3B3E4E),
    inverseSurface = Color(0xFFC6C9D6),
    inverseOnSurface = Color(0xFF22232D),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BB7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001945),
    inversePrimary = Color(0xFFB0C6FF),
    secondary = Color(0xFF3F7D2C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC0F0A6),
    onSecondaryContainer = Color(0xFF0B2000),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2C1800),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F5FA),
    surfaceContainer = Color(0xFFF1EFF4),
    surfaceContainerHigh = Color(0xFFEBE9EE),
    surfaceContainerHighest = Color(0xFFE5E3E9),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
)

/**
 * Chat is long-form reading on a small screen, so the body roles get more line height than
 * Material's defaults — the rest of the scale is inherited rather than restated, because a
 * type ramp is a system and overriding four roles out of fifteen is how one stops being one.
 */
private val DeckTypography: Typography
    @Composable get() = MaterialTheme.typography.let { base ->
        base.copy(
            bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
            bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
            bodySmall = base.bodySmall.copy(lineHeight = 19.sp),
        )
    }

/**
 * [dynamic] is Material You: on Android 12+ the scheme is derived from the user's wallpaper,
 * which is the platform guideline and what makes the app look native beside the system's own
 * surfaces. It is a parameter rather than a hard `SDK_INT` branch so a screenshot fixture can
 * pin the brand scheme — a wallpaper-derived palette differs on every device, and evidence
 * shot against one would be evidence about that phone.
 */
@Composable
fun AgentDeckTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, typography = DeckTypography, content = content)
}

/**
 * Vendor as one glyph. The fleet row has no width for a word — so every rendering of this
 * owes the reader [AgentVendor.label] as its `contentDescription`, or TalkBack announces the
 * character ("asterisk", "black diamond") and the row loses which agent it belongs to.
 */
fun AgentVendor.glyph(): String = when (this) {
    AgentVendor.CLAUDE -> "✳"
    AgentVendor.CODEX -> "◆"
}

fun AgentVendor.label(): String = when (this) {
    AgentVendor.CLAUDE -> "Claude"
    AgentVendor.CODEX -> "Codex"
}

/**
 * "Claude is working…" / "Codex is working…" — the sentence `VendorCatalog.workingText` gives
 * the desktop, reproduced rather than shared because `VendorCatalog` drags both model
 * catalogues in behind it and the Android build takes only the wire format. `VendorCatalogTest`
 * pins the plugin's side to this format so the two copies cannot drift apart silently.
 */
fun AgentVendor.workingText(): String = "${label()} is working…"

object Times {

    /** "just now" · "4m" · "3h" · "2d". Compact enough for a row's trailing slot. */
    fun relative(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (timestampMs <= 0L) return ""
        val delta = nowMs - timestampMs
        if (delta < 0) return "now"
        val minutes = delta / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            else -> "${minutes / (60 * 24)}d"
        }
    }

    /**
     * "21:14" for today, "Jul 30, 21:14" for any other day. The staleness stamp and a
     * scheduled row's due time both anchor to [nowMs] so a snapshot from yesterday or a
     * prompt due tomorrow cannot read as if it were today. [nowMs] defaults to real time;
     * inject a fixed value to assert across a midnight boundary without a live clock.
     */
    fun clock(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (timestampMs <= 0L) return ""
        val calendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val time = String.format(
            Locale.getDefault(),
            "%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val sameYear = calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        if (sameYear && calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) return time
        val datePattern = if (sameYear) "%1\$tb %1\$te" else "%1\$tb %1\$te, %1\$tY"
        return "${String.format(Locale.getDefault(), datePattern, calendar)}, $time"
    }
}

fun formatCost(costUsd: Double, known: Boolean): String =
    if (!known) "cost unknown" else String.format(Locale.US, "$%.2f", costUsd)
