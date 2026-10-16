package dev.agentdeck.companion.fixture

import com.github.claudeagents.core.WaitingReason
import com.github.claudeagents.core.mobile.*
import dev.agentdeck.companion.DeckState
import dev.agentdeck.companion.Screen

/** Synthetic ACP v1 detail; the older-host control deliberately omits every new capability. */
object AcpChatFixtures {
    const val EPOCH = "fixture-epoch-4"
    val config = listOf(
        MobileAcpConfig("model", "Model", "model", "balanced", listOf(
            MobileAcpChoice("balanced", "Balanced"), MobileAcpChoice("precise", "Precise"),
        )),
        MobileAcpConfig("mode", "Mode", "mode", "ask", listOf(
            MobileAcpChoice("ask", "Ask before edits"), MobileAcpChoice("auto", "Allow workspace edits"),
        )),
    )
    val question = MobileAcpCall(
        toolCallId = "fixture-question", decisionToken = "question-token-4", kind = "question",
        title = "Choose the retry policy",
        questions = listOf(
            MobileAcpQuestion("policy", "Which retry policy should the client use?", listOf(
                MobileAcpAnswerOption("bounded", "Bounded retries"), MobileAcpAnswerOption("manual", "Manual retry"),
            )),
            MobileAcpQuestion("checks", "Which checks should the change include?", listOf(
                MobileAcpAnswerOption("unit", "Unit tests"), MobileAcpAnswerOption("integration", "Integration tests"),
            ), allowMultiple = true),
        ),
    )
    val plan = MobileAcpCall(
        toolCallId = "fixture-plan", decisionToken = "plan-token-4", kind = "plan",
        title = "Retry policy implementation", overview = "Keep failures recoverable and preserve the draft.",
        plan = "## Bounded retry plan\n\n1. Cap attempts at three.\n2. Preserve the unsent draft.\n3. Add a deterministic timeout test.",
    )

    fun state(name: String, base: DeckState, hello: MobileHello): DeckState {
        val saved = name in setOf("fleet-acp-saved", "convo-acp-saved", "convo-acp-unavailable")
        val older = name == "convo-acp-older"
        val pending = when (name) {
            "convo-acp-question" -> listOf(question)
            "convo-acp-plan" -> listOf(plan)
            else -> emptyList()
        }
        val row = MobileAcpSession(
            key = requireNotNull(base.transcript).key, agentName = "Gemini CLI", projectPath = "/work/project",
            title = "Review the retry policy", running = false, lastActivityMs = DeckFixtures.NOW,
            awaiting = pending.isNotEmpty() || older,
            waitingReason = if (name == "convo-acp-plan") WaitingReason.PLAN_APPROVAL else WaitingReason.QUESTION,
            remembered = saved, canResume = saved,
            reopenError = "The agent could not load this saved chat. Retry in a moment."
                .takeIf { name == "convo-acp-unavailable" },
        )
        val liveRows = if (saved) emptyList() else listOf(row)
        val savedRows = if (saved) listOf(row) else emptyList()
        val snapshot = MobileFleetSnapshot.fromJson(requireNotNull(base.snapshot).copy(
            rows = emptyList(), acpSessions = liveRows,
            rememberedAcpSessions = savedRows, badgeCount = 0,
        ).toJson()).copy(acpSessions = liveRows, rememberedAcpSessions = savedRows)
        val page = requireNotNull(base.transcript).copy(
            title = row.title, running = false, liveLine = null, hasMore = false,
            turns = if (saved) emptyList() else listOf(
                MobileTurn("fixture-user", "user", "Review the retry policy before changing the client.", DeckFixtures.NOW - 60_000),
                MobileTurn("fixture-agent", "assistant", "I will keep the change focused and preserve unsent input.", DeckFixtures.NOW),
            ),
            acp = if (older || saved) null else MobileAcpChat(
                epoch = EPOCH, config = config.takeIf { name == "convo-acp-config" }.orEmpty(),
                pendingCalls = pending,
                todos = if (name == "convo-acp-progress") listOf(
                    MobileTodo("Inspect the retry owner", MobileTodo.COMPLETED),
                    MobileTodo("Add a timeout regression test", MobileTodo.IN_PROGRESS),
                    MobileTodo("Verify draft recovery", MobileTodo.PENDING),
                ) else emptyList(),
                activity = listOf("Checking retry callers in the client").takeIf { name == "convo-acp-progress" }.orEmpty(),
            ),
        )
        return base.copy(
            screen = if (name == "fleet-acp-saved") Screen.Fleet else (base.screen as Screen.Conversation).copy(title = row.title),
            snapshot = snapshot, transcript = page, drafts = mapOf(row.key to "Keep this draft while I review the decision."),
            hello = hello.copy(capabilities = hello.capabilities.filterNot {
                it in setOf("acp-remembered", "acp-config", "acp-vendor-decisions")
            } + if (older) emptyList() else listOf("acp-remembered", "acp-config", "acp-vendor-decisions", MobileProtocol.Capability.PERMISSION_DECISIONS)),
        )
    }
}
