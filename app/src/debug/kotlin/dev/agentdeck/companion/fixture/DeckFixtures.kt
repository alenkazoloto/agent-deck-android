package dev.agentdeck.companion.fixture

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.WaitingReason
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduledRow
import com.github.claudeagents.core.mobile.MobileTodo
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.DeckState
import dev.agentdeck.companion.Link
import dev.agentdeck.companion.Screen
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.UpdateRelease
import dev.agentdeck.companion.data.UpdateState

/**
 * Every state the UI branches on, as a `DeckState` a screenshot can be taken of.
 *
 * **Debug source set only** — nothing here is compiled into a release build, and the seam it
 * reaches (`DeckFixtureHook`) is null unless [FixtureInstaller] ran.
 *
 * Two rules these fixtures follow, both learned the hard way (`Memory.md`):
 *
 * 1. **Selected on the property under test, never "the first row".** `waiting-first` puts the
 *    waiting rows *last* in the input list, so blocked-first ordering and no ordering cannot
 *    photograph identically. The backlog is 167 rows because ten of each group would make the
 *    capped and uncapped lists the same picture.
 * 2. **Every state ships with the negative control that isolates it.** `fleet-capped` /
 *    `fleet-uncapped`, `convo-truncated` / `convo-whole`, `convo-codex` / `convo-claude`
 *    differ in exactly one input, so the pair is evidence and either shot alone is a picture.
 *
 * Clocks are fixed: [NOW] is the snapshot's own stamp, so a day-stamped time renders the same
 * on every run instead of flipping at local midnight.
 */
object DeckFixtures {

    /** 2026-07-31 21:14 UTC, fixed so the golden never depends on when it was taken. */
    const val NOW = 1_785_532_440_000L
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private val MACHINE = PairedMachine(
        machineName = "workshop",
        hosts = listOf("192.168.1.24", "100.71.4.9"),
        port = 63350,
        spkiFingerprint = "9f2c4a1e7b3d5068a2c9e14f7b6d3a85c0e2f419d7a6b3c85e0f21d4a97b6c3e",
        token = "fixture-token",
        deviceId = "fixture-device",
        preferredHost = "192.168.1.24",
    )

    /** The second pairing. Only `settings-two-machines` holds it — see that state for why. */
    private val LAPTOP = MACHINE.copy(
        machineName = "laptop",
        hosts = listOf("192.168.1.31"),
        deviceId = "fixture-device-2",
        preferredHost = "192.168.1.31",
    )

    /** What `/v1/hello` answers, including the capability the Schedule button is gated on. */
    private val HELLO = MobileHello(
        protocolVersion = MobileProtocol.VERSION,
        machineName = "workshop",
        ideName = "IntelliJ IDEA",
        pluginVersion = "1.6.0",
        capabilities = listOf(
            MobileProtocol.Capability.FLEET,
            MobileProtocol.Capability.TRANSCRIPT,
            MobileProtocol.Capability.SEND,
            MobileProtocol.Capability.STOP,
            MobileProtocol.Capability.SCHEDULED,
            MobileProtocol.Capability.SCHEDULE_CREATE,
            MobileProtocol.Capability.UNPAIR,
        ),
    )

    /**
     * The whole state space, by name. `deck-screenshot.sh --state <name>` passes one of these.
     */
    fun byName(name: String): DeckState? = when (name) {
        "pair" -> pair()
        "fleet-capped" -> fleet(backlog = 167)
        "fleet-uncapped" -> fleet(backlog = 4)
        "fleet-stale" -> fleet(backlog = 167).copy(link = Link.Stale("workshop is not answering"))
        "fleet-repair" -> fleet(backlog = 12).copy(link = Link.Repair("This machine's key changed."))
        "fleet-empty" -> fleet(backlog = 0, waiting = 0, running = 0, failed = 0, recent = 0)
        "fleet-filtered" -> fleet(backlog = 167).copy(filter = FleetFilter(query = "no-such-conversation"))
        // The opening view, and only this one reads the app's real default: every other fleet
        // fixture pins Attention so it can photograph the grouping, which would also pin — and
        // so hide — the very choice this state exists to show.
        "fleet-opening" -> fleet(backlog = 167, sort = DeckState().sort)
        "convo-codex" -> conversation(vendor = AgentVendor.CODEX)
        "convo-truncated" -> conversation(vendor = AgentVendor.CLAUDE, hasMore = true, turns = 40)
        "convo-whole" -> conversation(vendor = AgentVendor.CLAUDE, hasMore = false, turns = 40)
        "convo-idle" -> conversation(vendor = AgentVendor.CLAUDE, running = false)
        "convo-refused" -> conversation(vendor = AgentVendor.CLAUDE)
            .copy(notice = "This project is not open in the IDE on workshop.")
        "convo-empty" -> conversation(vendor = AgentVendor.CLAUDE, turns = 0, running = false)
        // The pair: identical routes differing only in the checklist the fix put on the wire.
        "convo-tasks" -> conversation(vendor = AgentVendor.CLAUDE, todos = TODOS)
        "convo-tasks-none" -> conversation(vendor = AgentVendor.CLAUDE)
        "convo-markdown" -> conversation(vendor = AgentVendor.CLAUDE, markdown = RICH_MARKDOWN)
        // The phone has no network, which is not the machine failing to answer — the pair
        // exists because one sentence blames the wrong end of the link.
        "fleet-offline" -> fleet(backlog = 12).copy(link = Link.Offline)
        // Two swipes taken, against the same fleet `fleet-capped` paints untouched.
        "fleet-snoozed" -> fleet(backlog = 167).let { state ->
            val waiting = state.snapshot?.rows.orEmpty().filter { it.key.startsWith("waiting-") }
            state.copy(snoozed = waiting.take(2).associate { it.key to it.lastActivityMs })
        }
        "convo-claude" -> conversation(vendor = AgentVendor.CLAUDE)
        // The offline read: the same page, off the disk, saying how old it is.
        "convo-cached" -> conversation(vendor = AgentVendor.CLAUDE, running = false)
            .copy(transcriptCached = true)
        "scheduled" -> scheduled()
        // The create button appears only where the machine advertises it; the pair differs in
        // that one capability and in nothing else.
        "scheduled-no-create" -> scheduled().copy(
            hello = HELLO.copy(
                capabilities = HELLO.capabilities - MobileProtocol.Capability.SCHEDULE_CREATE,
            ),
        )
        "settings" -> settings()
        // The machine switcher is progressive disclosure: absent with one pairing, present
        // with two, and `settings` above is that negative control.
        "settings-two-machines" -> settings().copy(machines = listOf(MACHINE, LAPTOP))
        // The self-update offer. `settings` above is the negative control and differs in exactly
        // one input — the published `versionCode` — so "Up to date." and the Download row cannot
        // photograph identically.
        "settings-update" -> settings().copy(update = UPDATED)
        "new-chat" -> newChat()
        else -> null
    }

    /** Names in the order a full sweep shoots them. */
    fun names(): List<String> = listOf(
        "pair",
        "fleet-capped", "fleet-uncapped", "fleet-stale", "fleet-repair", "fleet-empty",
        "fleet-filtered", "fleet-opening", "fleet-offline", "fleet-snoozed",
        "convo-claude", "convo-codex", "convo-truncated", "convo-whole", "convo-idle",
        "convo-refused", "convo-empty", "convo-cached",
        "convo-tasks", "convo-tasks-none", "convo-markdown",
        "scheduled", "scheduled-no-create", "settings", "settings-two-machines",
        "settings-update", "new-chat",
    )

    // ---- pairing --------------------------------------------------------------------------

    /**
     * The empty form only.
     *
     * **A half-filled one is not reachable from here, and there is deliberately no fixture
     * pretending otherwise.** This hook overrides [DeckState]; the pairing form's five fields
     * live in `rememberSaveable` *inside* `PairScreen`, one layer below it. A `pair-partial`
     * state was written anyway and was byte-identical to this one — a name claiming a second
     * state, photographing the first.
     *
     * Drive the real form instead; it is the better evidence anyway, because it exercises the
     * production input path rather than a seeded copy of it:
     *
     * ```
     * adb shell input tap 540 1014 && adb shell input text "192.168.1.24"
     * ```
     *
     * That is how docs/img/2026-07-31-mobile-pair-host-filled.png was taken, and how the
     * rotation pair beside it was.
     */
    private fun pair() = DeckState(screen = Screen.Pair, link = Link.Connecting)

    // ---- fleet ----------------------------------------------------------------------------

    private fun row(
        key: String,
        title: String,
        attention: SessionAttentionState?,
        vendor: AgentVendor = AgentVendor.CLAUDE,
        project: String = "/Users/dev/Plugin",
        branch: String? = "main",
        ageMs: Long = 4 * MINUTE,
        liveLine: String? = null,
        costUsd: Double = 0.42,
        contextPct: Int? = 37,
        messages: Int = 24,
        model: String? = "Sonnet 5",
    ) = MobileFleetRow(
        key = key,
        vendor = vendor,
        accountId = "default",
        accountLabel = null,
        projectPath = project,
        projectName = project.substringAfterLast('/'),
        gitBranch = branch,
        title = title,
        attention = attention,
        waitingReason = if (attention == SessionAttentionState.WAITING_ON_YOU) WaitingReason.QUESTION else null,
        lastActivityMs = NOW - ageMs,
        costUsd = costUsd,
        costKnown = true,
        contextPct = contextPct,
        messageCount = messages,
        model = model,
        liveLine = liveLine,
    )

    /**
     * [backlog] is the knob the cap is photographed against: 167 is this developer's real
     * `DONE_UNREVIEWED` count and 4 is the same screen with nothing to cap.
     *
     * The waiting and running rows are appended **last**, so a build that lost blocked-first
     * ordering paints them at the bottom instead of accidentally looking correct.
     */
    private fun fleet(
        backlog: Int,
        waiting: Int = 3,
        running: Int = 1,
        failed: Int = 1,
        recent: Int = 2,
        sort: FleetSort = FleetSort.ATTENTION,
    ): DeckState {
        val rows =
            (1..backlog).map {
                row(
                    key = "done-$it",
                    title = "Refactor the $it${ordinal(it)} extractor pass",
                    attention = SessionAttentionState.DONE_UNREVIEWED,
                    ageMs = it * HOUR,
                    vendor = if (it % 3 == 0) AgentVendor.CODEX else AgentVendor.CLAUDE,
                    project = if (it % 2 == 0) "/Users/dev/Plugin" else "/Users/dev/site",
                )
            } +
                (1..recent).map {
                    row("recent-$it", "Look at the flaky pairing test", null, ageMs = it * MINUTE)
                } +
                (1..failed).map {
                    row(
                        key = "failed-$it",
                        title = "Publish the 2.1 release notes",
                        attention = SessionAttentionState.FAILED,
                        vendor = AgentVendor.CODEX,
                        ageMs = 20 * MINUTE,
                        liveLine = "Exited with status 1 · npm run build",
                    )
                } +
                (1..running).map {
                    row(
                        key = "running-$it",
                        title = "Wire the push registration endpoint",
                        attention = SessionAttentionState.RUNNING,
                        ageMs = 30_000,
                        liveLine = "Bash — ./gradlew testDebugUnitTest",
                        contextPct = 71,
                    )
                } +
                (1..waiting).map {
                    row(
                        key = "waiting-$it",
                        title = WAITING_TITLES[(it - 1) % WAITING_TITLES.size],
                        attention = SessionAttentionState.WAITING_ON_YOU,
                        vendor = if (it == 2) AgentVendor.CODEX else AgentVendor.CLAUDE,
                        ageMs = it * 2 * MINUTE,
                        liveLine = "Waiting on your answer",
                        contextPct = 88,
                        messages = 132,
                    )
                }

        return DeckState(
            screen = Screen.Fleet,
            machine = MACHINE,
            link = Link.Live,
            sort = sort,
            snapshot = MobileFleetSnapshot(
                rows = rows,
                badgeCount = waiting,
                openProjects = listOf("/Users/dev/Plugin", "/Users/dev/site"),
                usageLine = "Session 46% · resets 23:00 · week 61%",
                generatedAtMs = NOW,
            ),
        )
    }

    /** Distinct per row: three identical titles read as a rendering bug in a screenshot. */
    private val WAITING_TITLES = listOf(
        "Approve the migration for accounts_v3",
        "Which of the two retry ladders should stay?",
        "Plan ready — 6 files, 2 deletions",
    )

    private fun ordinal(n: Int) = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    // ---- conversation -----------------------------------------------------------------------

    /**
     * The checklist an agent published, as `TodoWrite` sends it. Used by `convo-tasks` and
     * withheld by `convo-tasks-none` — the two differ in this value and in nothing else, so
     * the pair is evidence rather than a picture (Memory.md).
     */
    private val TODOS = listOf(
        MobileTodo("Reproduce the flake with a fixed clock", MobileTodo.COMPLETED),
        MobileTodo("Pin Times.clock either side of midnight", MobileTodo.COMPLETED),
        MobileTodo("Running the suite", MobileTodo.IN_PROGRESS),
        MobileTodo("Check the Codex path", MobileTodo.PENDING),
        MobileTodo("Update the changelog", MobileTodo.PENDING),
    )

    /**
     * Markdown an agent really writes: a task list, an ordered list, a fence. Before the
     * parser learned the first two, `- [x]` matched the bullet rule and the checkbox arrived
     * on screen as the literal text "[x]".
     */
    private const val RICH_MARKDOWN =
        "Here is the plan:\n\n" +
            "- [x] Reproduce the flake\n" +
            "- [x] Pin `Times.clock` either side of midnight\n" +
            "- [ ] Check the Codex path\n\n" +
            "Then, in order:\n\n" +
            "1. Run the suite\n" +
            "2. Shoot the screenshot\n" +
            "3. Update the changelog\n"

    private fun conversation(
        vendor: AgentVendor,
        running: Boolean = true,
        hasMore: Boolean = false,
        turns: Int = 6,
        todos: List<MobileTodo> = emptyList(),
        markdown: String? = null,
    ): DeckState {
        val key = "convo-1"
        val body = List(turns) { i ->
            if (i % 2 == 0) {
                MobileTurn(
                    id = "t$i",
                    role = "user",
                    text = "Can you check why the pairing test is flaky and fix it?",
                    timestampMs = NOW - (turns - i) * MINUTE,
                )
            } else {
                MobileTurn(
                    id = "t$i",
                    role = "assistant",
                    text = markdown
                        ?: ("It fails when the clock crosses a **day boundary** — `Times.clock` " +
                            "formatted `HH:mm` with no date, so a stamp from yesterday read as today.\n\n" +
                            "```kotlin\nfun clock(ms: Long, now: Long): String\n```\n\n" +
                            "I pinned it with a fixed clock either side of midnight."),
                    timestampMs = NOW - (turns - i) * MINUTE,
                    // On the newest assistant turn, where a live run's checklist actually sits.
                    todos = if (i == turns - 1) todos else emptyList(),
                    toolCalls = if (i == 1) {
                        listOf(
                            MobileToolCall("c1", "Read", "Read ui/Common.kt", "lines 44-81", MobileToolCall.OK),
                            MobileToolCall(
                                "c2", "Bash", "Bash — run the tests",
                                "command: ./gradlew testDebugUnitTest",
                                if (running) MobileToolCall.RUNNING else MobileToolCall.OK,
                            ),
                            MobileToolCall("c3", "Edit", "Edit ui/Common.kt", "1 replacement", MobileToolCall.ERROR),
                        )
                    } else {
                        emptyList()
                    },
                    streaming = running && i == turns - 1,
                )
            }
        }

        return DeckState(
            screen = Screen.Conversation(key, "Fix the flaky pairing test", vendor, "/Users/dev/Plugin"),
            machine = MACHINE,
            link = Link.Live,
            transcript = MobileTranscriptPage(
                key = key,
                title = "Fix the flaky pairing test",
                turns = body,
                hasMore = hasMore,
                costUsd = 1.37,
                costKnown = true,
                contextPct = 44,
                model = if (vendor == AgentVendor.CODEX) "GPT-5.4-Codex" else "Sonnet 5",
                liveLine = if (running) "Bash — ./gradlew testDebugUnitTest" else null,
                running = running,
                generatedAtMs = NOW,
            ),
            drafts = mapOf(key to "also check the Codex path"),
        )
    }

    // ---- scheduled and new chat ---------------------------------------------------------------

    /**
     * On the newest published build. The release is *present* rather than null: "up to date" and
     * "never checked" are two different sentences, and the control that must not paint a
     * Download row is the one that has looked.
     */
    private val CURRENT = UpdateState(
        installedCode = 2,
        installedName = "1.1",
        checkedAtMs = NOW,
        release = UpdateRelease(
            versionName = "1.1",
            versionCode = 2,
            variant = "debug",
            apkName = "agent-deck-1.1-debug.apk",
            apkUrl = "https://github.com/alenkazoloto/agent-deck-android/releases/download/v1.1/agent-deck-1.1-debug.apk",
            sizeBytes = 6_711_000,
            sha256 = "d1e7c0aa5f2b48c39a7e6d1f0b3c845e29f7a6b0c3d5e18f24a9b7c6d0e3f512",
            minSdk = 26,
            releaseUrl = "https://github.com/alenkazoloto/agent-deck-android/releases/tag/v1.1",
        ),
    )

    /** One build behind: the same state with the published `versionCode` one higher. */
    private val UPDATED = CURRENT.copy(
        release = CURRENT.release!!.copy(
            versionName = "1.2",
            versionCode = 3,
            apkName = "agent-deck-1.2-debug.apk",
            apkUrl = "https://github.com/alenkazoloto/agent-deck-android/releases/download/v1.2/agent-deck-1.2-debug.apk",
            releaseUrl = "https://github.com/alenkazoloto/agent-deck-android/releases/tag/v1.2",
        ),
    )

    private fun settings() = DeckState(
        screen = Screen.Settings,
        machine = MACHINE,
        machines = listOf(MACHINE),
        link = Link.Live,
        hello = HELLO,
        snapshot = fleet(backlog = 12).snapshot,
        update = CURRENT,
    )

    private fun scheduled() = DeckState(
        screen = Screen.Scheduled,
        machine = MACHINE,
        machines = listOf(MACHINE),
        link = Link.Live,
        hello = HELLO,
        snapshot = fleet(backlog = 12).snapshot,
        scheduled = listOf(
            // Due tomorrow and due yesterday, so MU-05's day stamp has something to prove.
            MobileScheduledRow("s1", "Run the nightly regression sweep", "/Users/dev/Plugin", null, NOW + DAY, "pending", true),
            MobileScheduledRow("s2", "Summarise yesterday's review queue", "/Users/dev/site", "abc", NOW - DAY, "paused", false),
            MobileScheduledRow("s3", "Bump the changelog for 2.1", "/Users/dev/Plugin", null, NOW + 90 * MINUTE, "pending", false),
        ),
    )

    private fun newChat() = DeckState(
        screen = Screen.NewChat,
        machine = MACHINE,
        link = Link.Live,
        snapshot = fleet(backlog = 12).snapshot,
        newChatTarget = NewChatTarget("/Users/dev/Plugin"),
        drafts = mapOf(dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY to "add a smoke test for the tunnel"),
    )
}
