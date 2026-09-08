package dev.agentdeck.companion

import androidx.compose.foundation.layout.Column
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
import dev.agentdeck.companion.data.SharedInput
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.ui.NewChatScreen
import dev.agentdeck.companion.ui.ScheduledScreen
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.ShareBanner
import dev.agentdeck.companion.ui.UpdateBanner
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
     * MP-14's pair. `conversation-portrait` is the negative control and differs in one field —
     * the newest turn's `streaming` — so "the agent is working" and "the agent stopped
     * mid-sentence" cannot be the same picture. They were.
     */
    @Test fun `conversation, a turn still being written`() =
        capture("conversation-writing", Shape.PORTRAIT) { Conversation("convo-writing") }

    /**
     * The pair this item exists for. Both fixtures carry the same `AskUserQuestion` on the same
     * turn; they differ in whether its tool call still has a result outstanding, which is the
     * one fact that decides whether the options can be tapped. "The agent is asking you this"
     * and "it asked, and has moved on" were the same picture — the word "AskUserQuestion",
     * inside a collapsed tool-call group — which is the whole of what was reported.
     */
    @Test fun `conversation, a question the agent is parked on`() =
        capture("conversation-question", Shape.PORTRAIT) { Conversation("convo-question", canAnswer = true) }

    @Test fun `conversation, a question already answered`() =
        capture("conversation-question-answered", Shape.PORTRAIT) {
            Conversation("convo-question-answered", canAnswer = true)
        }

    /**
     * The two screens that are almost entirely *text*, at the largest scale a user can pick —
     * where a clipped label shows up first, and neither of them existed a day ago.
     */
    @Test fun `settings, large text`() =
        capture("settings-font-1_3", Shape.LARGE_TEXT) { Settings() }

    /**
     * The self-update pair, on the one frame that can see it: `settings-current` is the negative
     * control and differs from `settings-update` in the published `versionCode` alone, so
     * "Up to date." and the Download row cannot be the same picture. They were, once — see
     * [Shape.TALL_PAGE].
     */
    @Test fun `settings, up to date`() =
        capture("settings-current", Shape.TALL_PAGE) { Settings() }

    @Test fun `settings, an update is published`() =
        capture("settings-update", Shape.TALL_PAGE) { Settings("settings-update") }

    /**
     * The feature's other visible half, and the one no screen-level golden can reach: the banner
     * sits above every screen rather than inside one, which is why it lives in `ui/Banners.kt`
     * and not inside `MainActivity`.
     *
     * Three live states in one frame, because each carries a different control — offer, download
     * running, file waiting for the installer. The *absent* state is deliberately not here: it is
     * a predicate with five cases in `AppUpdateTest`, and a photograph of nothing would be
     * evidence of a build that never shipped the feature just as readily.
     */
    @Test fun `the update banner, in every state it paints`() =
        capture("update-banner", Shape.PORTRAIT) { UpdateBanners() }

    /**
     * A share in flight, over the list that is the picker for it.
     *
     * Photographed together rather than alone: what the row claims is that the fleet *is* the
     * pick-a-thread screen, and a banner shot on its own would prove the sentence renders
     * while saying nothing about the rows underneath it still being tappable and unclipped.
     * `fleet-portrait` is the control — the same list with no share pending.
     */
    @Test fun `fleet, with a share waiting for a destination`() =
        capture("fleet-sharing", Shape.PORTRAIT) {
            Column {
                ShareBanner(
                    SharedInput(
                        text = "Kotlin coroutines https://kotlinlang.org/docs/coroutines-guide.html",
                        label = "Kotlin coroutines https://kotlinlang.org/docs/co…",
                    ),
                    onDismiss = {},
                )
                Fleet()
            }
        }

    @Test fun `scheduled, large text`() =
        capture("scheduled-font-1_3", Shape.LARGE_TEXT) { Scheduled() }

    /**
     * MP-11's pair. The two fixtures differ in one input — whether the machine advertises
     * [MobileProtocol.Capability.MODELS] — so "the phone can name a model" and "this plugin
     * never told it any" cannot be the same picture. `new-chat-no-models` is the control, and
     * it is the state every build before this one was permanently in.
     */
    @Test fun `new chat, the machine named its models`() =
        capture("new-chat-models", Shape.PORTRAIT) { NewChat() }

    @Test fun `new chat, an older machine named none`() =
        capture("new-chat-no-models", Shape.PORTRAIT) { NewChat("new-chat-no-models") }

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
     * Never a live *run*: a running conversation paints `TypingDots`, an `infiniteRepeatable`
     * that by construction never settles — and a capture waits for the composition to go idle,
     * so a golden of one hangs the suite rather than failing it. The live-run states stay with
     * `deck-screenshot.sh`, which photographs a real frame.
     *
     * A live *turn* is a different question and is capturable, which is why `convo-writing`
     * exists: it sets the newest turn's `streaming` with `running = false`, so nothing
     * animates and the pair differs in exactly one field.
     */
    @Composable
    private fun Conversation(fixture: String = "convo-idle", canAnswer: Boolean = false) {
        val state = DeckFixtures.byName(fixture)!!
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
            canAnswer = canAnswer,
            onAnswer = { _, _ -> },
        )
    }

    /** `sdkInt` is pinned: an offer is judged against the phone, not against the harness's SDK. */
    @Composable
    private fun UpdateBanners() {
        val offered = DeckFixtures.byName("settings-update")!!.update
        Column {
            listOf(
                offered,
                offered.copy(downloadPercent = 42),
                offered.copy(readyApk = "/data/user/0/dev.agentdeck.companion/cache/updates/a.apk"),
            ).forEach { state ->
                UpdateBanner(
                    update = state,
                    notices = true,
                    onUpdate = {},
                    onInstall = {},
                    onDismiss = {},
                    sdkInt = 34,
                )
            }
        }
    }

    @Composable
    private fun Settings(fixture: String = "settings") = SettingsScreen(
        state = DeckFixtures.byName(fixture)!!,
        onSettings = {},
        onSwitchMachine = {},
        onAddMachine = {},
        onUnpair = {},
        onRefreshHello = {},
        onRefreshPush = {},
        onChoosePush = {},
        onCheckUpdate = {},
        onDownloadUpdate = {},
        onInstallUpdate = {},
        onReleasePage = {},
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
            hello = state.hello,
            draft = "",
            onDraft = {},
            onRefresh = {},
            onCreate = { _, _, _ -> },
            onCommand = { _, _, _ -> },
        )
    }

    @Composable
    private fun NewChat(fixture: String = "new-chat") {
        val state = DeckFixtures.byName(fixture)!!
        NewChatScreen(
            target = state.newChatTarget!!,
            openProjects = state.snapshot?.openProjects.orEmpty(),
            vendors = NewChat.vendorOptions(state.snapshot?.rows.orEmpty()),
            hello = state.hello,
            draft = state.drafts[NEW_CHAT_DRAFT_KEY].orEmpty(),
            sending = false,
            notice = null,
            onTarget = {},
            onDraft = {},
            onSend = {},
            onDismissNotice = {},
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

        /**
         * A viewport tall enough to hold the *whole* Settings page.
         *
         * Not a device anyone owns, and that is the point: Settings is a scrolling column six
         * sections long, so on a phone-shaped frame everything below Appearance is off the
         * bottom — the first attempt at the update pair below recorded two **byte-identical**
         * PNGs of the same four visible sections, and reported success. A golden that cannot
         * see the thing it is named after is a picture, not evidence.
         *
         * **It is a number that goes stale.** At 2000 dp it stopped reaching Diagnostics the
         * moment that section grew a row, and nothing failed — the shot still rendered, still
         * differed from its pair in the About rows it was originally aimed at, and had simply
         * gone blind to the bottom of the page. Anything added below About owes a check that
         * this frame still ends *after* the last section, not a re-record.
         */
        TALL_PAGE("w411dp-h3000dp", 1f, false),
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
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, shape.fontScale),
                // Every clock below here is the fixture's. A machine-minted stamp is already
                // measured against the snapshot's own `generatedAtMs`; this pins the *other*
                // clock — the reader's day, which Settings' "Last snapshot" and a schedule's
                // due time ask about — so a golden cannot record the date it was shot on.
                LocalNow provides { DeckFixtures.NOW },
            ) {
                // Never Material You in a golden: a wallpaper-derived palette differs per
                // device, so the diff would fire on the emulator's wallpaper.
                AgentDeckTheme(dark = shape.dark, dynamic = false) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }
}
