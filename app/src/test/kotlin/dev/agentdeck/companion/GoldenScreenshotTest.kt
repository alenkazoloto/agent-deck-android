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
import dev.agentdeck.companion.ui.ProvideCodeWrap
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.LocalRunningIndicatorFrame
import dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.ui.NewChatScreen
import dev.agentdeck.companion.ui.ScheduledScreen
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.SettingsTab
import dev.agentdeck.companion.ui.ShareBanner
import dev.agentdeck.companion.ui.UpdateBanner
import dev.agentdeck.companion.ui.UsageScreen
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
     * The Usage page's pair. `usage` has a drained weekly window beside a nearly idle 5-hour
     * one — the shape the screen exists to show — and `usage-unmeasured` differs in exactly
     * what the machine measured, so "94% of your week is gone" and "nothing has been read yet"
     * cannot be the same picture.
     */
    @Test fun `usage, spend and plan windows`() = capture("usage", Shape.PORTRAIT) { Usage() }

    @Test fun `usage, nothing measured yet`() =
        capture("usage-unmeasured", Shape.PORTRAIT) { Usage("usage-unmeasured") }

    /** The largest text a user can pick, where a clipped percentage would show up first. */
    @Test fun `usage, large text`() = capture("usage-font-1_3", Shape.LARGE_TEXT) { Usage() }

    /**
     * The row into it, and its negative control. `settings-font-1_3`/`settings-current` are shot
     * from the `settings` fixture, whose hello carries no `usage` capability, so the Usage
     * section's presence is the one difference between that frame and this one — a client
     * hides a surface it does not see, and a golden of the row is what proves the gate is wired
     * to the capability rather than to the build.
     */
    @Test fun `settings, a machine that reports its usage`() =
        capture("settings-usage", Shape.TALL_PAGE) { Settings("settings-usage") }

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
        capture("settings-current", Shape.TALL_PAGE) { Settings(tab = SettingsTab.About) }

    @Test fun `settings, an update is published`() =
        capture("settings-update", Shape.TALL_PAGE) { Settings("settings-update", SettingsTab.About) }

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

    @Test fun `scheduled, portrait`() = capture("scheduled-portrait", Shape.PORTRAIT) { Scheduled() }

    @Test fun `scheduled, landscape`() = capture("scheduled-landscape", Shape.LANDSCAPE) { Scheduled() }

    @Test fun `scheduled, dark`() = capture("scheduled-dark", Shape.DARK) { Scheduled() }

    @Test fun `scheduled, empty`() = capture("scheduled-empty", Shape.PORTRAIT) { Scheduled(empty = true) }


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

    /** M1: two Claude accounts and the capability — the Account pill, on the active one. */
    @Test fun `new chat, the machine has two accounts`() =
        capture("new-chat-accounts", Shape.PORTRAIT) { NewChat("new-chat-accounts") }

    /** M2: effort (a rung picked) and mode pills; `new-chat-models` is the control without them. */
    @Test fun `new chat, the machine named its effort rungs and modes`() =
        capture("new-chat-run-options", Shape.PORTRAIT) { NewChat("new-chat-run-options") }

    /**
     * M3: a finished run whose calls carry what they returned — a folded "Thought" above the
     * answer, and a tool group whose rows are now targets. Without the milestone this frame is
     * the same conversation with no thought row and no openable calls, so the two are not the
     * same picture.
     */
    @Test fun `conversation, tool results and a folded thought`() =
        capture("conversation-tool-results", Shape.PORTRAIT) { Conversation("convo-tool-results") }

    /** M2: a draft brings the pills, opened on what the desk would send next. */
    @Test fun `conversation, the composer's model, effort and mode pills`() =
        capture("conversation-composer-pills", Shape.PORTRAIT) {
            Conversation("convo-composer-pills", withDraft = true)
        }

    /**
     * M4: the Changes half of a conversation — the checklist, with one file already ticked and
     * one the baseline ladder could not measure. Without the milestone there is no tab strip at
     * all, so this frame cannot be confused with the messages one.
     */
    @Test fun `conversation, the files this agent changed`() =
        capture("conversation-changes", Shape.PORTRAIT) { Conversation("convo-changes", changes = true) }

    /** M4's second depth: one file's hunks, and the sentence a cut diff carries. */
    @Test fun `conversation, one changed file's diff`() =
        capture("conversation-changes-diff", Shape.PORTRAIT) {
            Conversation("convo-changes-diff", changes = true)
        }

    /**
     * M8: a code fence, coloured. The keyword, string and comment roles are all in this one
     * frame, so a tokenizer that stopped producing any of the three fails here.
     */
    @Test fun `conversation, a code block coloured`() =
        capture("conversation-code", Shape.PORTRAIT) { Conversation("convo-code") }

    /**
     * M8: the same fence under Settings › Reading's "Wrap long lines".
     *
     * The *same* fixture as the frame above, composed under the one composition local that
     * differs — the pair is the evidence that the setting reaches a chat's code and not only a
     * diff, which is what the Settings row now promises.
     */
    @Test fun `conversation, a code block wrapped`() =
        capture("conversation-code-wrapped", Shape.PORTRAIT) {
            ProvideCodeWrap(true) { Conversation("convo-code-wrapped") }
        }

    /**
     * M8: the same file, read with both Settings › Reading switches on.
     *
     * Deliberately the same fixture as the frame above, differing in exactly the two fields
     * under test — a long line folded instead of clipped, and the number gutter beside it. A
     * second fixture would have let the pair record two different diffs and report success.
     */
    @Test fun `conversation, a diff wrapped and numbered`() =
        capture("conversation-changes-reading", Shape.PORTRAIT) {
            Conversation("convo-changes-diff", changes = true, reading = true)
        }

    /**
     * M5: an instruction the machine refused, still owed. The parked state rather than the
     * waiting one — it is the only one that puts a decision in front of the reader, and the
     * sentence under it is the one nobody can work out for themselves.
     */
    @Test fun `conversation, a send the machine would not take`() =
        capture("conversation-queued", Shape.PORTRAIT) { Conversation("convo-queued") }

    /**
     * M6: the composer with a photo already uploaded and an `@` half typed. Without the
     * milestone the same frame has no chip and no paperclip, so the two cannot be confused.
     *
     * The completion list is deliberately **not** in this frame. It opens behind a debounce
     * (`MENTION_DEBOUNCE_MS`), and a capture waits for the composition to go idle rather than
     * for a coroutine delay — a golden recorded here would photograph whichever side of that
     * race the harness happened to land on. `ComposerAttachmentInteractionTest` pins the popup
     * instead, where it can wait for the query and then accept a row.
     */
    @Test fun `conversation, an attached photo and a mention being typed`() =
        capture("conversation-composer-photo", Shape.PORTRAIT) {
            Conversation("convo-composer-attachments", withDraft = true)
        }

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
     * Idle conversation goldens keep the focus on reading. The streaming-tail pair changes only
     * the newest turn; live-run motion is covered by RunningIndicatorInteractionTest and emulator captures.
     */
    @Composable
    private fun Conversation(
        fixture: String = "convo-idle",
        canAnswer: Boolean = false,
        withDraft: Boolean = false,
        changes: Boolean = false,
        reading: Boolean = false,
    ) {
        val state = DeckFixtures.byName(fixture)!!
        val target = state.screen as Screen.Conversation
        ConversationScreen(
            target = target,
            page = state.transcript,
            loading = false,
            cached = false,
            draft = if (withDraft) state.drafts[target.key].orEmpty() else "",
            notice = null,
            onDraft = {},
            onSend = { _, _ -> },
            onStop = {},
            onDismissNotice = {},
            canAnswer = canAnswer,
            onAnswer = { _, _ -> },
            hello = state.hello,
            selection = state.composerSelection(target.key),
            canReview = MobileProtocol.Capability.REVIEW in state.hello?.capabilities.orEmpty(),
            review = state.review,
            reviewPath = state.reviewPath,
            reviewDiff = state.reviewDiff,
            changesOpen = changes,
            diffSoftWrap = reading,
            diffLineNumbers = reading,
            queued = state.outgoing.items,
            delivering = state.delivering,
            photos = state.pendingPhotos[target.key].orEmpty(),
            onSearchFiles = { listOf("ui/ConversationScreen.kt", "ui/ConversationFind.kt") },
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
    private fun Settings(fixture: String = "settings", tab: SettingsTab = SettingsTab.Machine) = SettingsScreen(
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
        initialTab = tab,
    )

    @Composable
    private fun Usage(fixture: String = "usage") {
        val state = DeckFixtures.byName(fixture)!!
        UsageScreen(
            report = state.usage,
            loading = false,
            error = null,
            // The screen asks for its figures once, when it composes. A golden supplies them,
            // so the fetch is the one thing here that must not happen.
            onLoad = {},
        )
    }

    @Composable
    private fun Scheduled(empty: Boolean = false) {
        val state = DeckFixtures.byName("scheduled")!!
        ScheduledScreen(
            rows = if (empty) emptyList() else state.scheduled,
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
            vendors = NewChat.vendorOptions(state.snapshot?.rows.orEmpty(), state.hello),
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
                LocalRunningIndicatorFrame provides 0,
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
