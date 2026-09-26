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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileAcpSession
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileDeskCommands
import com.github.claudeagents.core.mobile.MobileBackgroundTask
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The full Material 3 role set, not three overrides on the baseline.
 *
 * Overriding only `primary`/`secondary`/`tertiary` leaves every *container* and *surface* role
 * at Material's purple baseline, which is what shipped: a blue-primary app whose user bubbles,
 * chips and selected states were all lavender (docs/img/2026-08-01-img_2.png).
 * The roles are filled here so tonal surfaces and their `on*` pairs come from one hue family
 * and every pairing keeps its contrast.
 *
 * Values follow the redesign's tokens (PLAN-MOBILE-REDESIGN, `docs/design/mobile-redesign-2026-09-22`):
 * ink, accent, canvas (`surfaceContainerLow`, the search field's fill) and line. One deliberate
 * departure: light `onSurfaceVariant` stays `#566274`, because the token's `#637084` measures
 * 3.9:1 on `surfaceContainerHighest`. `ThemeContrastTest` holds every text/fill pair to 4.5:1.
 */
internal val DarkColors = darkColorScheme(
    primary = Color(0xFF91AAFF),
    onPrimary = Color(0xFF0A1F3D),
    primaryContainer = Color(0xFF263453),
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
    background = Color(0xFF1B2230),
    onBackground = Color(0xFFEEF2F9),
    surface = Color(0xFF1B2230),
    onSurface = Color(0xFFEEF2F9),
    surfaceVariant = Color(0xFF29323F),
    onSurfaceVariant = Color(0xFFA5AFC1),
    surfaceContainerLowest = Color(0xFF10151E),
    surfaceContainerLow = Color(0xFF141922),
    surfaceContainer = Color(0xFF222A38),
    surfaceContainerHigh = Color(0xFF29323F),
    surfaceContainerHighest = Color(0xFF343E4C),
    outline = Color(0xFF8893A3),
    outlineVariant = Color(0xFF323C4C),
    inverseSurface = Color(0xFFEEF2F9),
    inverseOnSurface = Color(0xFF222A35),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF315CE8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDF1FF),
    onPrimaryContainer = Color(0xFF001945),
    inversePrimary = Color(0xFFB0C6FF),
    secondary = Color(0xFF3F7D2C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEEDDF),
    onSecondaryContainer = Color(0xFF0B2000),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2C1800),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF18212F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18212F),
    surfaceVariant = Color(0xFFE7ECF2),
    onSurfaceVariant = Color(0xFF566274),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F7F9),
    surfaceContainer = Color(0xFFEDF1F6),
    surfaceContainerHigh = Color(0xFFE6EBF2),
    surfaceContainerHighest = Color(0xFFDCE3EC),
    outline = Color(0xFF738095),
    outlineVariant = Color(0xFFE3E7EE),
    inverseSurface = Color(0xFF283341),
    inverseOnSurface = Color(0xFFEDF1F6),
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

/** The app uses a consistent neutral palette; callers can opt into Material You colours. */
@Composable
fun AgentDeckTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = false,
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

/**
 * The name a conversation gives its speaker when the chat is an ACP agent's: the agent's own
 * (the row carries it as `accountLabel`), never the vendor the wire files every ACP row under.
 * Null for a native chat, which [AgentVendor.label] names.
 */
fun acpAgentVoice(key: String, agentName: String?): String? =
    if (MobileAcpSession.isKey(key)) agentName?.takeIf { it.isNotBlank() } ?: "ACP agent" else null

/**
 * The desk's `/` rows a chat may offer. An ACP agent takes no model or mode, its changes are read-only (Changes), and its conversation is no Claude
 * transcript to rename, branch, rewind or export; its own commands come from the agent (`/v1/commands`), so the desk's
 * rows keep only what works on any chat. Every other chat keeps [actions] whole.
 */
fun acpDeskActions(key: String, actions: Set<MobileDeskCommands.Action>): Set<MobileDeskCommands.Action> =
    if (!MobileAcpSession.isKey(key)) actions else actions.intersect(
        setOf(
            MobileDeskCommands.Action.STOP, MobileDeskCommands.Action.NEW_CHAT, MobileDeskCommands.Action.COPY_LAST_RESPONSE,
            MobileDeskCommands.Action.SETTINGS, MobileDeskCommands.Action.CHATS, MobileDeskCommands.Action.USAGE,
            MobileDeskCommands.Action.SCHEDULE, MobileDeskCommands.Action.CHANGES,
        ),
    )

/**
 * Whether a conversation opens the Changes tab: a machine that reviews at all, and for an ACP chat one that reads its
 * agent's edits ([MobileProtocol.Capability.ACP_REVIEW]) — an older one answers that key `not-found`.
 */
fun offersChanges(key: String, capabilities: Collection<String>): Boolean =
    MobileProtocol.Capability.REVIEW in capabilities &&
        (!MobileAcpSession.isKey(key) || MobileProtocol.Capability.ACP_REVIEW in capabilities)

fun acpWorkingText(agentName: String): String = "$agentName is working…"

/** What the working bubble says about delegated work; nothing for none, so an older machine draws no line. */
fun subagentsText(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 subagent running"
    else -> "$count subagents running"
}

private const val SUBAGENT_NAMES_SHOWN = 3

/** One line per named running subagent, the rest folded into "+N more"; none from a machine that sends no names. */
fun subagentNameLines(count: Int, names: List<String>): List<String> {
    if (names.isEmpty()) return emptyList()
    val shown = names.take(SUBAGENT_NAMES_SHOWN)
    val more = count - shown.size
    return if (more > 0) shown + "+$more more" else shown
}

/** The heading over the chat's background tasks; nothing for none, so an older machine draws no note. */
fun backgroundTasksText(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 background task"
    else -> "$count background tasks"
}

internal const val BACKGROUND_TASKS_SHOWN = 4

/** "Agent · Review changes", or the kind alone for a task the CLI did not name. */
fun backgroundTaskLine(task: MobileBackgroundTask): String =
    listOfNotNull(task.kind.takeIf { it.isNotBlank() }, task.name).joinToString(" · ")

/** One line per task up to [BACKGROUND_TASKS_SHOWN], the rest folded into "+N more". */
fun backgroundTaskLines(tasks: List<MobileBackgroundTask>): List<String> {
    val shown = tasks.take(BACKGROUND_TASKS_SHOWN).map(::backgroundTaskLine)
    val more = tasks.size - shown.size
    return if (more > 0) shown + "+$more more" else shown
}

/**
 * The *reader's* clock — the phone's — for the two questions that are genuinely about the
 * reader's day: "is this snapshot from today?" and "is this prompt due today?".
 *
 * It is a `() -> Long` rather than a `Long` so the default stays live: a Composable that
 * recomposes an hour later asks again. It is a CompositionLocal rather than a parameter
 * because the only caller that overrides it is a fixture painting a screenshot, and a
 * screenshot has to be able to pin *every* clock below it at once.
 *
 * **Not for a timestamp the machine minted.** A fleet row's `lastActivityMs`, a turn's
 * `timestampMs` and a snapshot's own stamp all come from the machine's clock, so they are
 * measured against `generatedAtMs` — the same rule `FleetGrouping.groupOf` already follows,
 * and the reason a row could read "3h" while sitting under the "Recent" heading.
 */
val LocalNow = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/**
 * The zone the machine's scheduler reads a wall time in (`MobileHello.timeZoneId`), null until a hello names one. A due time is an
 * instant the phone can word in its own zone, but "daily at 09:00" and a desk row mean the machine's — [Times.hostClock] says both.
 */
val LocalHostZone = staticCompositionLocalOf<ZoneId?> { null }

object Times {

    /**
     * "just now" · "4m" · "3h" · "2d". Compact enough for a row's trailing slot.
     *
     * [nowMs] has no default on purpose: the four `fleet-*` goldens failed by the calendar for
     * three weeks because this one defaulted to the phone's wall clock while the fixture
     * pinned the snapshot's stamp. Every caller now names the clock it means — [LocalNow] for
     * the reader's day, `generatedAtMs` for anything the machine stamped.
     */
    fun relative(timestampMs: Long, nowMs: Long): String {
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
     * prompt due tomorrow cannot read as if it were today. [nowMs] has no default — see
     * [relative] for what the default cost.
     */
    fun clock(timestampMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        if (timestampMs <= 0L) return ""
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = timestampMs }
        val time = String.format(
            Locale.getDefault(),
            "%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val sameYear = calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        if (sameYear && calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) return time
        val datePattern = if (sameYear) "%1\$tb %1\$te" else "%1\$tb %1\$te, %1\$tY"
        return "${String.format(Locale.getDefault(), datePattern, calendar)}, $time"
    }

    /**
     * [clock] for a moment the machine's scheduler owns. Read in the machine's zone as its desk reads it, with the phone's own
     * reading beside it — "09:00 Berlin (12:00 here)" — when the two zones disagree at that instant; alone when they agree
     * or the machine named no zone.
     */
    fun hostClock(timestampMs: Long, nowMs: Long, host: ZoneId?): String {
        val here = clock(timestampMs, nowMs)
        if (host == null || timestampMs <= 0L) return here
        val hostZone = TimeZone.getTimeZone(host)
        if (hostZone.getOffset(timestampMs) == TimeZone.getDefault().getOffset(timestampMs)) return here
        return "${clock(timestampMs, nowMs, hostZone)} ${zoneLabel(host)} ($here here)"
    }

    /** "Europe/Berlin" → "Berlin": the city is what a person names a zone by. */
    fun zoneLabel(zone: ZoneId): String = zone.id.substringAfterLast('/').replace('_', ' ')
}

fun formatCost(costUsd: Double, known: Boolean): String =
    if (!known) "cost unknown" else String.format(Locale.US, "$%.2f", costUsd)
