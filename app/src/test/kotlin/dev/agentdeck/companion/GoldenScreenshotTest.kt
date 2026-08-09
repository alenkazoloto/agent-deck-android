package dev.agentdeck.companion

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.ScheduledScreen
import dev.agentdeck.companion.ui.SettingsScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Layout regressions, caught on the JVM with no device (MU-02).
 *
 * `deck-screenshot.sh` needs a booted emulator and about forty seconds per state, which makes
 * it evidence a human takes when they change something — not a gate a build can hold. These
 * are the same screens, painted by the same Composables against the same fixtures, at the four
 * shapes a phone actually takes: portrait, landscape, `fontScale = 1.3`, and dark.
 *
 * A golden that does not exist yet is recorded; every one that does is verified, and a layout
 * that moved fails `./gradlew testDebugUnitTest` with a diff image under
 * `build/outputs/roborazzi/`. Re-recording is deleting the PNG — see [capture] for why the
 * switch is the file rather than a flag.
 *
 * **One capture per test method**, which is why this reads as ten near-identical tests rather
 * than two loops: `captureRoboImage` stands up its own composition host, and a second call in
 * the same method dies inside Espresso's idling. The loop version recorded three goldens of
 * four and reported a failure that named a line rather than a cause.
 *
 * The screens are rendered directly rather than through `AgentDeckApp`, on purpose: the shell
 * builds a real `DeckViewModel`, which opens the keystore and the connection, and a golden that
 * needed those would be photographing the harness. What is under test here is layout —
 * clipping, wrapping, whether a control still fits — and layout lives in the screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GoldenScreenshotTest {

    @Test fun `fleet, portrait`() = capture("fleet-portrait", Shape.PORTRAIT) { Fleet() }

    @Test fun `fleet, landscape`() = capture("fleet-landscape", Shape.LANDSCAPE) { Fleet() }

    @Test fun `fleet, large text`() = capture("fleet-font-1_3", Shape.LARGE_TEXT) { Fleet() }

    @Test fun `fleet, dark`() = capture("fleet-dark", Shape.DARK) { Fleet() }

    @Test fun `conversation, portrait`() =
        capture("conversation-portrait", Shape.PORTRAIT) { Conversation() }

    @Test fun `conversation, landscape`() =
        capture("conversation-landscape", Shape.LANDSCAPE) { Conversation() }

    @Test fun `conversation, large text`() =
        capture("conversation-font-1_3", Shape.LARGE_TEXT) { Conversation() }

    @Test fun `conversation, dark`() = capture("conversation-dark", Shape.DARK) { Conversation() }

    /**
     * The two screens that are almost entirely *text*, at the largest scale a user can pick —
     * where a clipped label shows up first, and neither of them existed a day ago.
     */
    @Test fun `settings, large text`() =
        capture("settings-font-1_3", Shape.LARGE_TEXT) { Settings() }

    @Test fun `scheduled, large text`() =
        capture("scheduled-font-1_3", Shape.LARGE_TEXT) { Scheduled() }

    // ---- the screens under test --------------------------------------------------------

    @Composable
    private fun Fleet() {
        val state = DeckFixtures.byName("fleet-uncapped")!!
        FleetScreen(
            snapshot = state.snapshot,
            filter = FleetFilter(),
            sort = FleetSort.ATTENTION,
            refreshing = false,
            snoozed = emptyMap(),
            openKey = null,
            onFilter = {},
            onSort = {},
            onRefresh = {},
            onOpen = {},
            onSnooze = {},
            onStop = {},
        )
    }

    /**
     * `convo-idle`, never a live run: a running conversation paints `TypingDots`, an
     * `infiniteRepeatable` that by construction never settles — and a capture waits for the
     * composition to go idle, so a golden of one hangs the suite rather than failing it. The
     * live-run states stay with `deck-screenshot.sh`, which photographs a real frame.
     */
    @Composable
    private fun Conversation() {
        val state = DeckFixtures.byName("convo-idle")!!
        ConversationScreen(
            target = state.screen as Screen.Conversation,
            page = state.transcript,
            loading = false,
            cached = false,
            draft = "",
            notice = null,
            onDraft = {},
            onSend = { _, _ -> },
            onStop = {},
            onDismissNotice = {},
        )
    }

    @Composable
    private fun Settings() = SettingsScreen(
        state = DeckFixtures.byName("settings")!!,
        onSettings = {},
        onSwitchMachine = {},
        onAddMachine = {},
        onUnpair = {},
        onRefreshHello = {},
    )

    @Composable
    private fun Scheduled() {
        val state = DeckFixtures.byName("scheduled")!!
        ScheduledScreen(
            rows = state.scheduled,
            loading = false,
            canCreate = MobileProtocol.Capability.SCHEDULE_CREATE in
                state.hello?.capabilities.orEmpty(),
            projects = state.snapshot?.openProjects.orEmpty(),
            draft = "",
            onDraft = {},
            onRefresh = {},
            onCreate = { _, _ -> },
            onCommand = { _, _, _ -> },
        )
    }

    /**
     * Portrait, landscape, large text, dark — the four a layout has to survive. Landscape is
     * its own entry rather than a second suite because it is the shape that breaks a composer:
     * the keyboard takes most of the window and there is nothing left for the field.
     */
    private enum class Shape(
        val qualifiers: String,
        val fontScale: Float,
        val dark: Boolean,
    ) {
        PORTRAIT("w411dp-h891dp", 1f, false),
        LANDSCAPE("w891dp-h411dp", 1f, false),
        LARGE_TEXT("w411dp-h891dp", 1.3f, false),
        DARK("w411dp-h891dp", 1f, true),
    }

    /**
     * Records a golden that does not exist yet and verifies every one that does.
     *
     * No Gradle flag decides this, on purpose. Roborazzi's own switch is a system property its
     * Gradle plugin sets, and that plugin reaches for AGP's legacy `TestedExtension`, which
     * AGP 9 removed — so wiring the property by hand was two attempts at a `withType<Test>`
     * block that matched **nothing** and reported success while painting no image at all. A
     * suite that silently records instead of verifying is worse than no suite, so the decision
     * is the one thing that cannot be misconfigured: does the file exist.
     *
     * To re-record after an intended change: delete the PNG (or the whole directory) and run
     * the suite once. The diff of a *new* golden is then the change itself, in the commit.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, shape: Shape, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(shape.qualifiers)
        val path = "src/test/golden/$name.png"
        val taskType = if (File(path).isFile) RoborazziTaskType.Verify else RoborazziTaskType.Record
        captureRoboImage(
            filePath = path,
            roborazziOptions = RoborazziOptions(taskType = taskType),
        ) {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, shape.fontScale)) {
                // Never Material You in a golden: a wallpaper-derived palette differs per
                // device, so the diff would fire on the emulator's wallpaper.
                AgentDeckTheme(dark = shape.dark, dynamic = false) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }
}
