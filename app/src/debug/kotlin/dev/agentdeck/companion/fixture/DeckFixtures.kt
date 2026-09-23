package dev.agentdeck.companion.fixture

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.WaitingReason
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileModelOption
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileReviewFile
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileReviewHunk
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileRunSelection
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import com.github.claudeagents.core.mobile.MobileScheduledRow
import com.github.claudeagents.core.mobile.MobileTodo
import com.github.claudeagents.core.mobile.MobileQuestion
import com.github.claudeagents.core.mobile.MobileQuestionOption
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileToolResult
import com.github.claudeagents.core.mobile.MobileUsageAccount
import com.github.claudeagents.core.mobile.MobileUsageBucket
import com.github.claudeagents.core.mobile.MobileUsageCard
import com.github.claudeagents.core.mobile.MobileUsageReport
import com.github.claudeagents.core.mobile.MobileUsageWindow
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.DeckState
import dev.agentdeck.companion.LastGoodHost
import dev.agentdeck.companion.Link
import dev.agentdeck.companion.Screen
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.MessageSearch
import com.github.claudeagents.core.mobile.MobileSessionSearchHit
import dev.agentdeck.companion.data.FleetScope
import dev.agentdeck.companion.data.HostReach
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SharedInput
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
            MobileProtocol.Capability.MODELS,
            MobileProtocol.Capability.UNPAIR,
        ),
        // The ladder `/v1/hello` carries, minus the null-slug "Default model" row: sending no
        // model at all is a property of the request, so the client mints that row itself.
        models = mapOf(
            AgentVendor.CLAUDE to listOf(
                MobileModelOption("sonnet", "Sonnet 5"),
                MobileModelOption("opus", "Opus 5"),
                MobileModelOption("haiku", "Haiku 4.5"),
            ),
            AgentVendor.CODEX to listOf(
                MobileModelOption("gpt-5.1-codex", "gpt-5.1-codex"),
                MobileModelOption("gpt-5.1-codex-mini", "gpt-5.1-codex-mini"),
            ),
        ),
    )

    /**
     * The whole state space, by name. `deck-screenshot.sh --state <name>` passes one of these.
     */
    /**
     * The commit sheet over `convo-commit`: the desk's seed, a modified and a deleted file ticked,
     * an untracked one offered unticked, and the count this commit leaves out. The `-failed` state
     * carries git's refusal under an edited message — the draft a failed commit keeps.
     */
    fun commitSheet(name: String): dev.agentdeck.companion.data.CommitSheet? {
        if (name != "convo-commit" && name != "convo-commit-failed") return null
        val preview = com.github.claudeagents.core.mobile.MobileReviewCommitPreview(
            key = "convo-1",
            branch = "fix/clock-drift",
            message = "Make the clock injectable\n\nClaude changed 3 files",
            files = listOf(
                com.github.claudeagents.core.mobile.MobileReviewCommitFile("src/main/kotlin/Clock.kt", "modified", true),
                com.github.claudeagents.core.mobile.MobileReviewCommitFile("src/test/kotlin/ClockTest.kt", "untracked", false),
                com.github.claudeagents.core.mobile.MobileReviewCommitFile("src/main/kotlin/LegacyTimer.kt", "deleted", true),
            ),
            alreadyCommitted = 1,
            previewToken = "fixture",
        )
        val failed = name == "convo-commit-failed"
        return dev.agentdeck.companion.data.CommitSheet(
            key = "convo-1",
            preview = preview,
            message = if (failed) "Inject the clock into the scheduler" else preview.message,
            chosen = setOf("src/main/kotlin/Clock.kt", "src/main/kotlin/LegacyTimer.kt"),
            error = if (failed) "ktlint: src/main/kotlin/Clock.kt:12 Unexpected blank line" else null,
        )
    }

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
        // M3: a pinned row leads the list and one Done row has left it for the Done tab. The
        // control is `fleet-uncapped` without `session-actions`: no star, no tab, no sheet rows.
        "fleet-organized" -> organized(FleetScope.ALL)
        // M3: the Chats list narrowed to one desk folder. Its control is `fleet-organized`, the
        // same rows under "All folders".
        "fleet-folder" -> organized(FleetScope.ALL).let { it.copy(filter = it.filter.copy(folderId = "release")) }
        "fleet-organized-done" -> organized(FleetScope.DONE)
        // M3: two pins in the desk's order — the older one first — on a machine that moves pins
        // and writes titles. Its control is `fleet-organized`: one pin, neither capability, so its
        // sheet has no Move pin rows and no Regenerate title.
        "fleet-pins" -> organized(FleetScope.ALL).let { state ->
            val snapshot = state.snapshot!!
            state.copy(
                snapshot = snapshot.copy(
                    rows = snapshot.rows.map {
                        when (it.key) {
                            "done-3" -> it.copy(pinRank = 0)
                            "recent-1" -> it.copy(pinned = true, pinRank = 1)
                            else -> it
                        }
                    },
                ),
                hello = state.hello!!.copy(
                    capabilities = state.hello!!.capabilities + MobileProtocol.Capability.PIN_ORDER +
                        MobileProtocol.Capability.SESSION_RETITLE,
                ),
            )
        }
        // The machine's preview of a delete, awaiting the reader. Its negative control is
        // `fleet-organized`: the same list with no dialog, because nothing was asked yet.
        "fleet-delete-confirm" -> organized(FleetScope.ALL).copy(
            deletePreview = com.github.claudeagents.core.mobile.MobileSessionDeletePreview(
                key = "recent-1", title = "Fix the pairing retry", movesToTrash = true,
                transcriptCopies = 1, scheduledPrompts = 1, previewToken = "fixture",
            ),
        )
        // M3: the machine's list of messages a Claude chat forks from, awaiting the reader's pick.
        // Its negative control is `fleet-organized`: the same list with no picker.
        "fleet-fork-picker" -> organized(FleetScope.ALL).copy(
            forkPoints = com.github.claudeagents.core.mobile.MobileSessionForkPoints(
                key = "recent-1",
                points = listOf(
                    com.github.claudeagents.core.mobile.MobileForkPoint("a1", "Why does pairing retry forever on a dead host?", 1, NOW - 50 * 60_000),
                    com.github.claudeagents.core.mobile.MobileForkPoint("a2", "Cap the retries at five and back off", 2, NOW - 35 * 60_000),
                    com.github.claudeagents.core.mobile.MobileForkPoint("a3", "Now add a test for the timeout path", 3, NOW - 12 * 60_000),
                ),
            ),
        )
        // M3: "pairing" matches two titles, and the machine's message search found two more
        // chats that only mention it. The control is `fleet-filtered`: no capability, no section.
        "fleet-message-search" -> messageSearch()
        "convo-codex" -> conversation(vendor = AgentVendor.CODEX)
        "convo-truncated" -> conversation(vendor = AgentVendor.CLAUDE, hasMore = true, turns = 40)
        "convo-whole" -> conversation(vendor = AgentVendor.CLAUDE, hasMore = false, turns = 40)
        "convo-idle" -> conversation(vendor = AgentVendor.CLAUDE, running = false)
        "convo-refused" -> conversation(vendor = AgentVendor.CLAUDE)
            .copy(notice = "This project is not open in the IDE on workshop.")
        "convo-empty" -> conversation(vendor = AgentVendor.CLAUDE, turns = 0, running = false)
        // The negative control for `convo-idle`, differing in exactly one field: the last
        // turn's `streaming`. A half-written assistant turn used to paint identically to a
        // finished one (MP-14).
        "convo-writing" ->
            conversation(vendor = AgentVendor.CLAUDE, running = false, streamingTail = true)
        // The pair: identical routes differing only in the checklist the fix put on the wire.
        // Two turns, not the default six: the question sits on the *newest* turn, which is
        // where a live ask really is — and at six the card was below the fold, so the pair
        // recorded two byte-identical PNGs of the conversation above it and reported success.
        "convo-question" -> conversation(
            vendor = AgentVendor.CLAUDE,
            running = false,
            turns = 2,
            questions = listOf(QUESTION),
        )
        // Two questions, the second multi-select: the card collects a pick per question and
        // sends them together (PLAN-MOBILE-REDESIGN P16).
        "convo-questions-multi" -> conversation(
            vendor = AgentVendor.CLAUDE,
            running = false,
            turns = 2,
            questions = listOf(QUESTION, SCOPE_QUESTION),
        ).let { it.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.ANSWER)) }
        "convo-question-answered" -> conversation(
            vendor = AgentVendor.CLAUDE,
            running = false,
            turns = 2,
            questions = listOf(QUESTION),
            questionLive = false,
        )
        "convo-tasks" -> conversation(vendor = AgentVendor.CLAUDE, todos = TODOS)
        "convo-tasks-none" -> conversation(vendor = AgentVendor.CLAUDE)
        "convo-markdown" -> conversation(vendor = AgentVendor.CLAUDE, markdown = RICH_MARKDOWN)
        // A fence with a line wider than any phone, so the wrap setting has something to do.
        // `convo-code` and `convo-code-wrapped` are the *same* state: the pair differs only in
        // the `LocalCodeWrap` the golden composes it under, which is the field under test.
        "convo-code", "convo-code-wrapped" ->
            conversation(vendor = AgentVendor.CLAUDE, running = false, markdown = LONG_FENCE)
        // The phone has no network, which is not the machine failing to answer — the pair
        // exists because one sentence blames the wrong end of the link.
        "fleet-offline" -> fleet(backlog = 12).copy(link = Link.Offline)
        // Another app's share sheet, waiting for the user to name a destination. Differs from
        // `fleet-capped` in one field, so the pair photographs where the banner sits in the real
        // stack — under the connection's row, over the update one — which is a decision
        // `MainActivity` makes and the golden, rendering the strip alone, cannot see.
        "fleet-sharing" -> fleet(backlog = 167).copy(
            sharing = SharedInput(
                text = "Kotlin coroutines https://kotlinlang.org/docs/coroutines-guide.html",
                label = "Kotlin coroutines https://kotlinlang.org/docs/co…",
            ),
        )
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
        // "After the usage limit resets" in the create dialog's When pill: the active account's
        // window resets in 90 minutes. `scheduled` is the control — no accounts, so no choice.
        "scheduled-reset" -> scheduled().copy(
            hello = HELLO.copy(
                capabilities = HELLO.capabilities + MobileProtocol.Capability.ACCOUNTS,
                accounts = mapOf(
                    AgentVendor.CLAUDE to listOf(
                        MobileScheduleAccountOption("default", "Personal"),
                        MobileScheduleAccountOption("work", "Work", resetAtMs = NOW + 90 * MINUTE),
                    ),
                ),
                activeAccounts = mapOf(AgentVendor.CLAUDE to "work"),
            ),
        )
        "settings" -> settings()
        // The Review root: the fleet's finished-unreviewed rows across two projects, then the
        // same screen with nothing to review, then a plugin too old to serve `/v1/review`.
        "review" -> review(backlog = 6)
        "review-empty" -> review(backlog = 0)
        "review-unsupported" -> review(backlog = 6).copy(hello = HELLO)
        // The Usage row is progressive disclosure on the `usage` capability, and `settings`
        // above is the negative control: it carries HELLO without it, so "this machine can
        // report its spend" and "it cannot" are two frames of the Settings page, not one.
        "settings-usage" -> settings().copy(hello = HELLO_USAGE)
        "usage" -> usage()
        // Its pair. A machine that has never had a plan window answered for it is not a machine
        // at 0%, and the card that says so is the one a reader meets on a fresh pairing.
        "usage-unmeasured" -> usage().copy(usage = USAGE_UNMEASURED)
        // The drained Work week offers "Schedule a prompt for …" once the machine lists that
        // account's reset; `usage` is the control — no account list, so no offer.
        "usage-limit" -> usage().copy(hello = HELLO_LIMIT)
        // Where that offer lands: the create dialog on Work, "after the limit resets" picked.
        "scheduled-after-reset" -> scheduled().copy(
            hello = HELLO_LIMIT,
            scheduleAfterReset = NewChatTarget("", AgentVendor.CLAUDE, accountId = "work"),
        )
        // A chat on the drained account offers "Continue at …"; `convo-idle` is the control.
        "convo-limit" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(hello = HELLO_LIMIT, snapshot = MobileFleetSnapshot(listOf(limitRow()), badgeCount = 0, openProjects = listOf("/Users/dev/Plugin"), usageLine = null, generatedAtMs = NOW))
        }
        // The machine switcher is progressive disclosure: absent with one pairing, present
        // with two, and `settings` above is that negative control.
        "settings-two-machines" -> settings().copy(machines = listOf(MACHINE, LAPTOP))
        // The self-update offer. `settings` above is the negative control and differs in exactly
        // one input — the published `versionCode` — so "Up to date." and the Download row cannot
        // photograph identically.
        "settings-update" -> settings().copy(update = UPDATED)
        "new-chat" -> newChat()
        // The model picker's pair. They differ in the one capability that decides whether the
        // machine ever named a ladder — an older plugin says nothing, and the row is absent
        // rather than an empty dropdown.
        "new-chat-no-models" -> newChat().copy(
            hello = HELLO.copy(
                capabilities = HELLO.capabilities - MobileProtocol.Capability.MODELS,
                models = emptyMap(),
            ),
        )
        // M1's account picker. `new-chat` is the control: same screen, same machine, minus the
        // capability and the second account, so "no picker" and "a picker" cannot photograph alike.
        "new-chat-accounts" -> newChat().copy(
            hello = HELLO.copy(
                capabilities = HELLO.capabilities + MobileProtocol.Capability.ACCOUNTS,
                accounts = mapOf(
                    AgentVendor.CLAUDE to listOf(
                        MobileScheduleAccountOption("default", "Personal"),
                        MobileScheduleAccountOption("work", "Work"),
                    ),
                ),
                activeAccounts = mapOf(AgentVendor.CLAUDE to "work"),
            ),
        )
        // M2: the effort and mode pills. `new-chat` is the control, minus the two capabilities.
        "new-chat-run-options" -> newChat().let { state ->
            state.copy(
                hello = HELLO_RUN_OPTIONS,
                newChatTarget = state.newChatTarget?.copy(effort = "xhigh"),
            )
        }
        // The composer's pills appear with a draft, opened on what the desk would send next;
        // `convo-idle` is the control — no capability, no pills.
        "convo-composer-pills" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO_RUN_OPTIONS,
                transcript = state.transcript?.copy(
                    selection = MobileRunSelection(model = "opus", effort = "xhigh", permissionMode = "acceptEdits"),
                ),
            )
        }
        // Redesign M2: a run blocked on a tool permission names the wait in place of "working…".
        // `convo-claude` is the control — the same live run with no waiting row.
        "convo-waiting" -> conversation(vendor = AgentVendor.CLAUDE, streamingTail = false).let { state ->
            state.copy(
                hello = HELLO_RUN_OPTIONS,
                snapshot = MobileFleetSnapshot(
                    rows = listOf(
                        row("convo-1", "Fix the flaky pairing test", SessionAttentionState.WAITING_ON_YOU,
                            waitingReason = WaitingReason.PERMISSION),
                    ),
                    badgeCount = 1,
                    openProjects = listOf("/Users/dev/Plugin"),
                    usageLine = null,
                    generatedAtMs = NOW,
                ),
            )
        }
        // What a finished run looks like once the machine carries tool output: every call
        // opens, the failed one says why, and the model's reasoning is folded away above.
        "convo-tool-results" -> conversation(
            vendor = AgentVendor.CLAUDE,
            running = false,
            toolResults = true,
            thought = "Two candidate causes. The stamp is formatted without a date, so a " +
                "turn from yesterday reads as today — that fits the failure better than the " +
                "clock skew does.",
        ).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.TOOL_RESULTS,
                ),
            )
        }
        // The checklist a reviewer lands on: mixed sizes, one already ticked, one the ladder
        // could not measure — the negative control that keeps "+0 −0" from reading as real.
        "convo-changes" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW),
                review = REVIEW,
            )
        }
        // "Commit…" under the checklist, and its sheet over it ([commitSheet]). The negative
        // control, `convo-commit-unsupported`, is the same open list from a machine without
        // `review-commit`: no button, no sheet.
        "convo-commit", "convo-commit-failed", "convo-commit-unsupported" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW +
                        if (name == "convo-commit-unsupported") emptySet() else setOf(MobileProtocol.Capability.REVIEW_COMMIT),
                ),
                review = REVIEW,
                changesOpenKey = "convo-1",
            )
        }
        // The second depth: one file's hunks, with the omission sentence a cut diff carries.
        "convo-changes-diff" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW),
                review = REVIEW,
                reviewPath = "src/main/kotlin/Clock.kt",
                reviewDiff = REVIEW_DIFF,
            )
        }
        // M5: an instruction the machine refused, still owed and waiting on the reader. The
        // parked state rather than the waiting one, because it is the only one with controls.
        "convo-queued" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                outgoing = dev.agentdeck.companion.data.OutgoingQueue(
                    listOf(
                        dev.agentdeck.companion.data.OutgoingSend(
                            clientMessageId = "queued-1",
                            key = (state.screen as Screen.Conversation).key,
                            projectPath = state.screen.projectPath,
                            vendor = AgentVendor.CLAUDE,
                            label = state.screen.title,
                            prompt = "Rerun the failing test and paste the output.",
                            lastError = "This chat has reached its spending limit. Raise it in the IDE to continue.",
                            attempts = 1,
                            parked = true,
                            uncertain = true,
                        ),
                    ),
                ),
            )
        }
        // M6: a draft mid-mention with a photo already uploaded. Both surfaces at once, because
        // they share the composer and the frame's whole claim is what that row looks like now.
        "convo-composer-attachments" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            val key = (state.screen as Screen.Conversation).key
            state.copy(
                // Spelled out rather than copied from `state.hello`: `conversation()` leaves it
                // null, and a `?.copy` there silently produced a frame with neither surface.
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities +
                        MobileProtocol.Capability.ATTACHMENTS + MobileProtocol.Capability.FILES,
                ),
                drafts = state.drafts + (key to "Why does this fail? @Conver"),
                pendingPhotos = mapOf(
                    key to listOf(
                        dev.agentdeck.companion.data.PendingPhoto("photo-1", "Photo · 1.2 MB"),
                    ),
                ),
            )
        }
        else -> null
    }

    private val REVIEW = MobileReviewList(
        key = "convo-1",
        files = listOf(
            MobileReviewFile("src/main/kotlin/Clock.kt", MobileReviewFile.MODIFIED, added = 31, removed = 4),
            MobileReviewFile("src/test/kotlin/ClockTest.kt", MobileReviewFile.ADDED, added = 88, removed = 0),
            MobileReviewFile("docs/architecture/time.md", MobileReviewFile.MODIFIED, added = 6, removed = 2, reviewed = true),
            MobileReviewFile(
                "build/generated/Stamps.kt", MobileReviewFile.UNKNOWN,
                note = "No recorded version from before this conversation, so its changes cannot be shown.",
            ),
        ),
        added = 125, removed = 6, reviewedFiles = 1,
    )

    private val REVIEW_DIFF = MobileReviewFileDiff(
        file = MobileReviewFile("src/main/kotlin/Clock.kt", MobileReviewFile.MODIFIED, added = 31, removed = 4),
        hunks = listOf(
            MobileReviewHunk(
                beforeStart = 17, afterStart = 17,
                lines = listOf(
                    " import java.time.Instant",
                    " ",
                    "-fun now(): Long = System.currentTimeMillis()",
                    "+fun now(): Long = Clock.getInstance().nowMs()",
                    " ",
                    " /** The one clock a test can move. */",
                ),
            ),
            MobileReviewHunk(
                beforeStart = 44, afterStart = 45,
                lines = listOf(
                    "     fun stamp(ms: Long): String =",
                    "-        formatter.format(Instant.ofEpochMilli(ms))",
                    "+        dateFormatter.format(Instant.ofEpochMilli(ms))",
                    " }",
                ),
            ),
        ),
        omittedBytes = 41_000,
    )

    private val READ_RESULT = MobileToolResult(
        MobileToolResult.READ,
        output = "    44\tfun clock(ms: Long, now: Long): String =\n" +
            "    45\t    if (sameDay(ms, now)) time(ms) else dateAndTime(ms)\n",
    )

    private val BASH_RESULT = MobileToolResult(
        MobileToolResult.EXECUTE,
        input = "./gradlew testDebugUnitTest --tests '*PairingTest*'",
        output = "> Task :app:testDebugUnitTest\n\nPairingTest > pairs once PASSED\n\n" +
            "BUILD SUCCESSFUL in 41s\n",
    )

    private val EDIT_RESULT = MobileToolResult(
        MobileToolResult.EDIT,
        output = "String to replace not found in file.\n  ui/Common.kt: 1 occurrence expected, 0 found\n",
        omittedBytes = 42_000,
    )

    private val HELLO_RUN_OPTIONS = HELLO.copy(
        capabilities = HELLO.capabilities + MobileProtocol.Capability.EFFORT +
            MobileProtocol.Capability.PERMISSION_MODES,
        effort = mapOf(
            AgentVendor.CLAUDE to listOf(
                MobileModelOption("low", "Low effort"),
                MobileModelOption("medium", "Medium effort"),
                MobileModelOption("xhigh", "Extra-high effort"),
                MobileModelOption("max", "Max effort"),
            ),
            AgentVendor.CODEX to listOf(
                MobileModelOption("low", "Low effort"),
                MobileModelOption("high", "High effort"),
            ),
        ),
        permissionModes = mapOf(
            AgentVendor.CLAUDE to listOf(
                MobileModelOption("plan", "Plan mode"),
                MobileModelOption("acceptEdits", "Auto-accept edits"),
                MobileModelOption("auto", "Auto"),
                MobileModelOption("bypassPermissions", "Full access"),
            ),
            AgentVendor.CODEX to listOf(
                MobileModelOption("read-only", "Supervised"),
                MobileModelOption("workspace-write", "Auto-accept edits"),
                MobileModelOption("danger-full-access", "Full access"),
            ),
        ),
    )

    /** Names in the order a full sweep shoots them. */
    fun names(): List<String> = listOf(
        "pair",
        "fleet-capped", "fleet-uncapped", "fleet-stale", "fleet-repair", "fleet-empty",
        "fleet-filtered", "fleet-opening", "fleet-offline", "fleet-snoozed", "fleet-sharing",
        "convo-claude", "convo-codex", "convo-truncated", "convo-whole", "convo-idle",
        "convo-refused", "convo-empty", "convo-cached", "convo-writing",
        "convo-tasks", "convo-tasks-none", "convo-markdown", "convo-code", "convo-code-wrapped",
        "scheduled", "scheduled-no-create", "scheduled-reset", "settings", "settings-two-machines",
        "settings-update", "new-chat", "new-chat-no-models", "new-chat-accounts",
        "new-chat-run-options", "convo-composer-pills", "convo-waiting", "convo-questions-multi", "convo-tool-results",
        "convo-changes", "convo-changes-diff", "convo-commit", "convo-commit-failed", "convo-commit-unsupported", "convo-queued", "convo-composer-attachments",
        "settings-usage", "usage", "usage-unmeasured", "usage-limit", "scheduled-after-reset", "convo-limit",
        "review", "review-empty", "review-unsupported",
        "fleet-organized", "fleet-organized-done", "fleet-message-search", "fleet-delete-confirm", "fleet-folder",
        "fleet-fork-picker", "fleet-pins",
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

    private val WAITING_REASONS = listOf(WaitingReason.PERMISSION, WaitingReason.QUESTION, WaitingReason.PLAN_APPROVAL)

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
        waitingReason: WaitingReason? =
            WaitingReason.QUESTION.takeIf { attention == SessionAttentionState.WAITING_ON_YOU },
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
        waitingReason = waitingReason,
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
    private fun organized(scope: FleetScope): DeckState = fleet(backlog = 4, sort = FleetSort.RECENT).let { state ->
        val snapshot = state.snapshot!!
        state.copy(
            snapshot = snapshot.copy(
                rows = snapshot.rows.map {
                    when (it.key) {
                        "done-3" -> it.copy(pinned = true, title = "Release checklist", folderId = "release")
                        "recent-2" -> it.copy(done = true)
                        "recent-1", "done-1" -> it.copy(folderId = "release")
                        else -> it
                    }
                },
                folders = listOf(
                    com.github.claudeagents.core.mobile.MobileFolder("release", "Release 1.4", note = "Everything that has to land before the tag", count = 3),
                    com.github.claudeagents.core.mobile.MobileFolder("bugs", "Bug bash"),
                    com.github.claudeagents.core.mobile.MobileFolder("old", "Onboarding", done = true),
                ),
            ),
            hello = HELLO.copy(
                capabilities = HELLO.capabilities + MobileProtocol.Capability.SESSION_ACTIONS + MobileProtocol.Capability.SESSION_EXPORT +
                    MobileProtocol.Capability.SESSION_DELETE + MobileProtocol.Capability.SESSION_FOLDERS +
                    MobileProtocol.Capability.FOLDER_ACTIONS + MobileProtocol.Capability.SESSION_FORK + MobileProtocol.Capability.SESSION_BRANCH,
            ),
            filter = FleetFilter(scope = scope),
        )
    }

    private fun messageSearch(): DeckState = fleet(backlog = 8, sort = FleetSort.RECENT).let { state ->
        val filter = FleetFilter(query = "pairing")
        val keys = MessageSearch.population(state.snapshot!!.rows, filter)
        state.copy(
            filter = filter,
            hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.SESSION_SEARCH),
            messageSearch = MessageSearch(
                query = "pairing",
                keys = keys,
                hits = listOf(
                    MobileSessionSearchHit("running-1", "\u2026register the token only after pairing succeeds, so a revoked phone\u2026"),
                    MobileSessionSearchHit("done-2", "\u2026the pairing code expires after 120 s; the extractor keeps\u2026"),
                ),
                scanned = keys.size,
                nextCursor = null,
            ),
        )
    }

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
                        // One of each reason, matching WAITING_TITLES; the host's live line is
                        // the pending tool's title, which a question or plan ask does not add.
                        waitingReason = WAITING_REASONS[(it - 1) % WAITING_REASONS.size],
                        liveLine = "Bash — psql -f migrate_accounts_v3.sql".takeIf { _ -> (it - 1) % WAITING_REASONS.size == 0 },
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
    /**
     * One fence, one line too wide for a phone, in a language `SyntaxLite` knows.
     *
     * The comment, the string and the three keywords are deliberately all present: a fence that
     * only had keywords would photograph the same whether the string and comment rules ran.
     */
    private const val LONG_FENCE =
        "The clock reader now takes its own instant:\n\n" +
            "```kotlin\n" +
            "// A pinned clock, so a stamp written either side of midnight reads the same twice.\n" +
            "fun clock(ms: Long, now: Long): String =\n" +
            "    if (sameDay(ms, now)) hourMinute(ms) else \"\${dayOfWeek(ms)} \${hourMinute(ms)}\"\n" +
            "```\n"

    private const val RICH_MARKDOWN =
        "Here is the plan:\n\n" +
            "- [x] Reproduce the flake\n" +
            "- [x] Pin `Times.clock` either side of midnight\n" +
            "- [ ] Check the Codex path\n\n" +
            "Then, in order:\n\n" +
            "1. Run the suite\n" +
            "2. Shoot the screenshot\n" +
            "3. Update the changelog\n"

    /**
     * The `AskUserQuestion` a run is parked on, in the shape the plugin puts it on the wire.
     * `convo-question` shows it live and `convo-question-answered` shows the same call with a
     * result behind it — the pair differs in the tool call's status alone, which is exactly the
     * fact that decides whether the options may be tapped.
     */
    private val QUESTION = MobileQuestion(
        key = "What should the flaky-test fix cover?",
        header = "What should the flaky-test fix cover?",
        options = listOf(
            MobileQuestionOption(
                "Pin the clock only",
                "Smallest change: the test supplies its own instant and nothing else moves.",
            ),
            MobileQuestionOption(
                "Pin the clock and the pairing window",
                "Also bounds the retry ladder, which is the other thing that reads wall time.",
            ),
            MobileQuestionOption("Leave it — I'll look at the ladder myself"),
        ),
    )

    private val SCOPE_QUESTION = MobileQuestion(
        key = "Which suites should it re-run?",
        header = "Which suites should it re-run?",
        options = listOf(
            MobileQuestionOption("Pairing"),
            MobileQuestionOption("Outgoing queue"),
            MobileQuestionOption("Goldens"),
        ),
        multiSelect = true,
    )

    private fun conversation(
        vendor: AgentVendor,
        running: Boolean = true,
        /**
         * Whether the newest turn is mid-write. Defaults to [running] so every existing
         * fixture is byte-identical; it is separable because the *golden* for a live turn
         * must not also be a live run — `TypingDots` is an `infiniteRepeatable` and a capture
         * waits for the composition to go idle.
         */
        streamingTail: Boolean = running,
        hasMore: Boolean = false,
        turns: Int = 6,
        todos: List<MobileTodo> = emptyList(),
        markdown: String? = null,
        /** An `AskUserQuestion` on the newest assistant turn; empty leaves the turn as it was. */
        questions: List<MobileQuestion> = emptyList(),
        /** Whether that question is still parked — the one field the pair differs in. */
        questionLive: Boolean = true,
        /** The machine advertises `tool-results`, so finished calls carry what they returned. */
        toolResults: Boolean = false,
        /** Extended thinking on the newest assistant turn. */
        thought: String? = null,
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
                    toolCalls = if (questions.isNotEmpty() && i == turns - 1) {
                        listOf(
                            MobileToolCall(
                                "q1", "AskUserQuestion", "AskUserQuestion", "",
                                if (questionLive) MobileToolCall.RUNNING else MobileToolCall.OK,
                                questions = questions,
                            ),
                        )
                    } else if (i == 1) {
                        listOf(
                            MobileToolCall(
                                "c1", "Read", "Read ui/Common.kt", "lines 44-81", MobileToolCall.OK,
                                result = if (toolResults) READ_RESULT else null,
                            ),
                            MobileToolCall(
                                "c2", "Bash", "Bash — run the tests",
                                "command: ./gradlew testDebugUnitTest",
                                if (running) MobileToolCall.RUNNING else MobileToolCall.OK,
                                // A running call has no result yet — which is the case the
                                // capability exists to keep telling apart from an old IDE.
                                result = if (toolResults && !running) BASH_RESULT else null,
                            ),
                            MobileToolCall(
                                "c3", "Edit", "Edit ui/Common.kt", "1 replacement", MobileToolCall.ERROR,
                                result = if (toolResults) EDIT_RESULT else null,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                    streaming = streamingTail && i == turns - 1,
                    thought = thought?.takeIf { i == turns - 1 },
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

    private val HELLO_USAGE = HELLO.copy(
        capabilities = HELLO.capabilities + MobileProtocol.Capability.USAGE,
    )

    /**
     * A week that is drained while the 5-hour window is nearly idle — the whole reason the
     * screen lists windows rather than one number. `usage-unmeasured` is the negative control
     * and differs in exactly what the machine measured, not in what the screen draws.
     */
    private val USAGE = MobileUsageReport(
        cards = listOf(
            MobileUsageCard("Today", MobileUsageBucket(input = 41_200, output = 8_900, cacheRead = 2_100_000, costUsd = 3.17)),
            MobileUsageCard("7 days", MobileUsageBucket(input = 512_000, output = 96_400, cacheRead = 31_000_000, costUsd = 41.02)),
            MobileUsageCard("30 days", MobileUsageBucket(input = 2_100_000, output = 410_000, cacheRead = 148_000_000, costUsd = 186.44, costEstimated = true)),
            MobileUsageCard("All time", MobileUsageBucket(input = 9_400_000, output = 1_800_000, cacheRead = 620_000_000, costUsd = 912.80, costKnown = false)),
        ),
        models = listOf(
            MobileUsageCard("Opus 5", MobileUsageBucket(input = 1_400_000, output = 310_000, costUsd = 148.20)),
            MobileUsageCard("Sonnet 5", MobileUsageBucket(input = 620_000, output = 88_000, costUsd = 31.40)),
            MobileUsageCard("Haiku 4.5", MobileUsageBucket(input = 80_000, output = 12_000, costUsd = 6.84, costEstimated = true)),
        ),
        accounts = listOf(
            MobileUsageAccount(
                id = "work", vendor = AgentVendor.CLAUDE, label = "Work", active = true,
                windows = listOf(
                    MobileUsageWindow("weekly", 100, resetAtMs = NOW + 2 * DAY, resetText = "Aug 3 09:00", reached = true),
                    MobileUsageWindow("weekly Opus", 78, resetAtMs = NOW + 2 * DAY, resetText = "Aug 3 09:00"),
                    MobileUsageWindow("5-hour", 12, resetAtMs = NOW + 3 * HOUR, resetText = "23:00"),
                ),
            ),
            MobileUsageAccount(
                id = "personal", vendor = AgentVendor.CLAUDE, label = "Personal",
                windows = listOf(MobileUsageWindow("5-hour", 46, resetAtMs = NOW + HOUR, resetText = "21:00")),
            ),
            MobileUsageAccount(
                id = "codex", vendor = AgentVendor.CODEX, label = "alena@example.com",
                note = "Codex reports its limits only while it runs, and this account has not run recently.",
            ),
        ),
        generatedAtMs = NOW,
    )

    private val USAGE_UNMEASURED = MobileUsageReport(
        cards = USAGE.cards.map { MobileUsageCard(it.label, MobileUsageBucket()) },
        // Vendor-correct sentences, because the two vendors are empty for different reasons and
        // the reader's next move differs: Claude's numbers come from an endpoint the IDE polls,
        // Codex's are whatever its last local run recorded. This is the route's own wording.
        accounts = USAGE.accounts.map {
            MobileUsageAccount(
                it.id, it.vendor, it.label,
                note = when (it.vendor) {
                    AgentVendor.CLAUDE -> "No plan usage has been read for this account yet."
                    AgentVendor.CODEX ->
                        "Codex reports its limits only while it runs, and this account has not run recently."
                },
                active = it.active,
            )
        },
        generatedAtMs = NOW,
        indexing = true,
    )

    private val HELLO_LIMIT = HELLO_USAGE.copy(
        capabilities = HELLO_USAGE.capabilities + MobileProtocol.Capability.ACCOUNTS,
        accounts = mapOf(
            AgentVendor.CLAUDE to listOf(
                MobileScheduleAccountOption("work", "Work", resetAtMs = NOW + 2 * DAY, weeklyLimit = true),
                MobileScheduleAccountOption("personal", "Personal"),
            ),
        ),
        activeAccounts = mapOf(AgentVendor.CLAUDE to "work"),
    )

    private fun limitRow() = MobileFleetRow(
        key = "convo-1", vendor = AgentVendor.CLAUDE, accountId = "work", accountLabel = "Work",
        projectPath = "/Users/dev/Plugin", projectName = "Plugin", gitBranch = "main",
        title = "Fix the flaky pairing test", attention = null, waitingReason = null,
        lastActivityMs = NOW - MINUTE, costUsd = 1.37, costKnown = true, contextPct = 44, messageCount = 6,
    )

    private fun usage() = settings().copy(
        screen = Screen.Usage,
        hello = HELLO_USAGE,
        usage = USAGE,
    )

    private fun settings() = DeckState(
        screen = Screen.Settings,
        machine = MACHINE,
        machines = listOf(MACHINE),
        link = Link.Live,
        hello = HELLO,
        // A LAN address, because that is the state the Diagnostics row exists to make legible:
        // a machine reachable from the desk and from nowhere else (MP-07).
        lastGood = LastGoodHost(MACHINE.hosts.first(), NOW - 4 * MINUTE, HostReach.LAN),
        snapshot = fleet(backlog = 12).snapshot,
        update = CURRENT,
    )

    private fun review(backlog: Int) = settings().copy(
        screen = Screen.Review,
        hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW),
        snapshot = fleet(backlog = backlog).snapshot,
    )

    private fun scheduled() = DeckState(
        screen = Screen.Scheduled,
        machine = MACHINE,
        machines = listOf(MACHINE),
        link = Link.Live,
        hello = HELLO,
        snapshot = fleet(backlog = 12).snapshot,
        // Due tomorrow and due yesterday, so MU-05's day stamp has something to prove — and
        // one row per state the machine can actually send. Two of these said `"pending"`, which
        // **no machine has ever sent**: `MobileActions.scheduled()` emits exactly
        // MobileScheduledRow.RUNNING / PAUSED / QUEUED. So the committed golden photographed a
        // state the app cannot receive, and the screen's one state-sensitive branch was
        // exercised by a single row out of three (MP-10).
        scheduled = listOf(
            // Repeating, queued, and it failed last night — the row that read exactly like one
            // that had never run until the wire gained a past tense (MP-10). The two other rows
            // are the control: neither carries an outcome, and `queued` is all they can say.
            MobileScheduledRow(
                "s1", "Run the nightly regression sweep", "/Users/dev/Plugin", null,
                NOW + DAY, MobileScheduledRow.QUEUED, true,
                lastRunAtMs = NOW - DAY, lastRunFailed = true,
            ),
            MobileScheduledRow("s2", "Summarise yesterday's review queue", "/Users/dev/site", "abc", NOW - DAY, MobileScheduledRow.PAUSED, false),
            MobileScheduledRow("s3", "Bump the changelog for 2.1", "/Users/dev/Plugin", null, NOW - 2 * MINUTE, MobileScheduledRow.RUNNING, false),
        ),
    )

    private fun newChat() = DeckState(
        screen = Screen.NewChat,
        machine = MACHINE,
        link = Link.Live,
        snapshot = fleet(backlog = 12).snapshot,
        hello = HELLO,
        // No model named, deliberately. `ModelRows` keeps a model *in use* even where the
        // machine advertised nothing — so a fixture that named one would supply the gate's own
        // input, and the negative control would photograph a picker with the gate bypassed.
        // It did, once. The pair now differs in the capability and in nothing else.
        newChatTarget = NewChatTarget("/Users/dev/Plugin"),
        drafts = mapOf(dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY to "add a smoke test for the tunnel"),
    )
}
