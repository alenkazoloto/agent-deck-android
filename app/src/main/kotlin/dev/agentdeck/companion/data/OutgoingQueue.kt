package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRunSelection
import com.github.claudeagents.core.mobile.MobileSendRequest
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Retry uncertain delivery only with SEND_DEDUPE; otherwise park it to avoid billing one prompt twice.
 * clientMessageId is minted once at submission and preserved across retries (docs/architecture/mobile.md).
 */
data class OutgoingSend(
    val clientMessageId: String,
    /** The conversation, or null for a chat this send will start. */
    val key: String?,
    val projectPath: String,
    val vendor: AgentVendor,
    /** What the queue row calls the destination — a chat title, or the project's name. */
    val label: String,
    val prompt: String,
    val accountId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val permissionMode: String? = null,
    val fastMode: Boolean? = null,
    val thinking: Boolean? = null,
    val ultracode: Boolean? = null,
    /** The ACP agent a new chat starts on instead of [vendor]'s CLI; null runs the vendor. */
    val acpAgentId: String? = null,
    /** The saved preset a new chat was started from, so the machine runs its instructions and CLI options. */
    val presetName: String? = null,
    /**
     * The fields the reader left alone, for a machine that fills them from the chat's selectors
     * on arrival (`send-desk-selection`). Persisted, so a send that waits out a dead link still
     * runs on what the desk says when it lands, not on what the page said at the tap.
     */
    val deskFields: Set<MobileRunSelection.Field> = emptySet(),
    /**
     * Persist uploaded photo IDs with the send so process death cannot detach them from their prompt.
     * Expired IDs produce a server notice without refusing the send.
     */
    val attachmentIds: List<String> = emptyList(),
    val attempts: Int = 0,
    val nextAttemptAtMs: Long = 0L,
    /** The machine's sentence, or this app's, from the attempt that did not land. */
    val lastError: String? = null,
    /** Waiting for the user rather than for the link: a refusal, or an uncertain delivery. */
    val parked: Boolean = false,
    /** Set when the bytes were handed to the socket, so a retry may run the prompt twice. */
    val uncertain: Boolean = false,
    /** "Stop & send": the interrupt and the prompt are one act and travel together. */
    val stopFirst: Boolean = false,
    /** A server refusal survives reconnects; unlike uncertain delivery, dedupe capability cannot overturn it. */
    val refused: Boolean = false,
    /** Persist the in-flight marker: process death before a verdict means the send may have reached the machine. */
    val inFlight: Boolean = false,
    /**
     * The [com.github.claudeagents.core.mobile.MobileHello.sendInstance] the uncertain attempt
     * went to. An IDE restart forgets which ids it accepted, so only that same instance can
     * collapse a repeat; the retry names it ([MobileSendRequest.retryOf]) and anything else parks.
     */
    val sentTo: String? = null,
) {
    val newChat: Boolean get() = key == null

    /** New-chat lanes use project identity; a null lane would let one parked project block every other project. */
    val lane: String get() = key ?: "new:${acpAgentId ?: vendor}:$projectPath"

    fun request(): MobileSendRequest = MobileSendRequest(
        key = key,
        projectPath = projectPath,
        prompt = prompt,
        vendor = vendor,
        model = model,
        effort = effort,
        permissionMode = permissionMode,
        fastMode = fastMode,
        thinking = thinking,
        ultracode = ultracode,
        newChat = newChat,
        accountId = accountId.takeIf { newChat },
        acpAgentId = acpAgentId.takeIf { newChat },
        presetName = presetName.takeIf { newChat },
        clientMessageId = clientMessageId,
        attachmentIds = attachmentIds,
        deskFields = deskFields,
        retryOf = sentTo.takeIf { uncertain },
    )

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", clientMessageId)
        key?.let { addProperty("key", it) }
        addProperty("projectPath", projectPath)
        addProperty("vendor", vendor.name)
        addProperty("label", label)
        addProperty("prompt", prompt)
        accountId?.let { addProperty("accountId", it) }
        model?.let { addProperty("model", it) }
        effort?.let { addProperty("effort", it) }
        permissionMode?.let { addProperty("permissionMode", it) }
        fastMode?.let { addProperty("fastMode", it) }
        thinking?.let { addProperty("thinking", it) }
        ultracode?.let { addProperty("ultracode", it) }
        acpAgentId?.let { addProperty("acpAgentId", it) }
        presetName?.let { addProperty("presetName", it) }
        if (deskFields.isNotEmpty()) add("deskFields", JsonArray().apply { deskFields.forEach { add(it.wire) } })
        if (attachmentIds.isNotEmpty()) {
            add("attachmentIds", JsonArray().apply { attachmentIds.forEach(::add) })
        }
        addProperty("attempts", attempts)
        addProperty("nextAttemptAtMs", nextAttemptAtMs)
        lastError?.let { addProperty("lastError", it) }
        addProperty("parked", parked)
        addProperty("uncertain", uncertain)
        addProperty("stopFirst", stopFirst)
        addProperty("refused", refused)
        addProperty("inFlight", inFlight)
        sentTo?.let { addProperty("sentTo", it) }
    }

    companion object {
        fun fromJson(o: JsonObject): OutgoingSend? {
            fun str(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            fun num(name: String) = runCatching { o.get(name)?.takeIf { it.isJsonPrimitive }?.asLong }.getOrNull()
            fun flag(name: String) = runCatching { o.get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean }.getOrNull()
            val id = str("id") ?: return null
            val prompt = str("prompt") ?: return null
            val project = str("projectPath") ?: return null
            val vendor = AgentVendor.entries.firstOrNull { it.name == str("vendor") } ?: AgentVendor.CLAUDE
            return OutgoingSend(
                clientMessageId = id,
                key = str("key"),
                projectPath = project,
                vendor = vendor,
                label = str("label").orEmpty(),
                prompt = prompt,
                accountId = str("accountId"),
                model = str("model"),
                effort = str("effort"),
                permissionMode = str("permissionMode"),
                fastMode = flag("fastMode"),
                thinking = flag("thinking"),
                ultracode = flag("ultracode"),
                acpAgentId = str("acpAgentId"),
                presetName = str("presetName"),
                deskFields = runCatching {
                    o.getAsJsonArray("deskFields")?.mapNotNullTo(LinkedHashSet()) { e ->
                        val name = e.takeIf { it.isJsonPrimitive }?.asString
                        MobileRunSelection.Field.entries.firstOrNull { it.wire == name }
                    }
                }.getOrNull().orEmpty(),
                attachmentIds = runCatching {
                    o.getAsJsonArray("attachmentIds")?.mapNotNull {
                        it.takeIf { e -> e.isJsonPrimitive }?.asString?.takeIf { s -> s.isNotBlank() }
                    }
                }.getOrNull().orEmpty(),
                attempts = num("attempts")?.toInt()?.coerceAtLeast(0) ?: 0,
                nextAttemptAtMs = num("nextAttemptAtMs") ?: 0L,
                lastError = str("lastError"),
                parked = flag("parked") ?: false,
                uncertain = flag("uncertain") ?: false,
                stopFirst = flag("stopFirst") ?: false,
                refused = flag("refused") ?: false,
                inFlight = flag("inFlight") ?: false,
                sentTo = str("sentTo"),
            )
        }
    }
}

/** Pure queue rules preserve submission order. SecureStore persists them; DeckViewModel drains them. */
data class OutgoingQueue(val items: List<OutgoingSend> = emptyList()) {

    val isEmpty: Boolean get() = items.isEmpty()

    fun forKey(key: String?): List<OutgoingSend> = items.filter { it.key == key }

    fun inLane(lane: String): List<OutgoingSend> = items.filter { it.lane == lane }

    /** Replace in place: writing retry backoff must not reorder an instruction behind newer submissions. */
    fun with(item: OutgoingSend): OutgoingQueue {
        val at = items.indexOfFirst { it.clientMessageId == item.clientMessageId }
        val next = if (at >= 0) items.toMutableList().also { it[at] = item } else items + item
        return OutgoingQueue(next.takeLast(MAX_ITEMS))
    }

    fun without(id: String): OutgoingQueue = OutgoingQueue(items.filterNot { it.clientMessageId == id })

    fun find(id: String): OutgoingSend? = items.firstOrNull { it.clientMessageId == id }

    /**
     * Only each conversation's head may send; backoff or a parked item must not let later instructions overtake it.
     * Other conversations drain independently.
     */
    fun due(nowMs: Long): OutgoingSend? {
        val blocked = mutableSetOf<String>()
        for (item in items) {
            if (item.lane in blocked) continue
            if (!item.parked && item.nextAttemptAtMs <= nowMs) return item
            blocked += item.lane
        }
        return null
    }

    /** When the drain should look again, or null while nothing is merely waiting on the clock. */
    fun nextWakeMs(nowMs: Long): Long? {
        val blocked = mutableSetOf<String>()
        var soonest: Long? = null
        for (item in items) {
            if (item.lane in blocked) continue
            if (item.parked) {
                blocked += item.lane
                continue
            }
            val wait = (item.nextAttemptAtMs - nowMs).coerceAtLeast(0L)
            if (soonest == null || wait < soonest) soonest = wait
        }
        return soonest
    }

    companion object {
        /** Bound disk growth by dropping oldest overflow; preserve the instructions the user most recently typed. */
        const val MAX_ITEMS = 50

        const val FIRST_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L

        /** 5 s, doubling to 5 min — the same shape the link's own reconnect uses. */
        fun backoffMs(attempts: Int): Long {
            var delay = FIRST_BACKOFF_MS
            repeat((attempts - 1).coerceIn(0, 12)) {
                delay = (delay * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            return delay.coerceAtMost(MAX_BACKOFF_MS)
        }

        /** Bytes sent may already have started a run: park unless dedupe makes a repeat safe. Always park refusals. */
        fun afterFailure(
            item: OutgoingSend,
            nowMs: Long,
            error: String?,
            refused: Boolean,
            reachedMachine: Boolean,
            dedupes: Boolean,
        ): OutgoingSend {
            val attempts = item.attempts + 1
            val park = refused || (reachedMachine && !dedupes)
            return item.copy(
                attempts = attempts,
                lastError = error,
                parked = park,
                refused = refused,
                inFlight = false,
                uncertain = item.uncertain || reachedMachine,
                sentTo = item.sentTo.takeIf { item.uncertain || reachedMachine },
                nextAttemptAtMs = if (park) 0L else nowMs + backoffMs(attempts),
            )
        }

        /** Due now, whatever the backoff had decided; an uncertain item keeps its guard. */
        fun resumed(item: OutgoingSend): OutgoingSend =
            item.copy(parked = false, refused = false, nextAttemptAtMs = 0L, lastError = null)

        /**
         * The user's own Retry. They have looked and decided it did not run, so it goes out as
         * a fresh send: without [OutgoingSend.sentTo] a restarted machine runs it rather than
         * answering `send-unconfirmed` again.
         */
        fun retried(item: OutgoingSend): OutgoingSend = resumed(item).copy(uncertain = false, sentTo = null)

        /**
         * Marked before the bytes go out, so a process death leaves evidence an attempt began.
         * [instance] is recorded until an attempt turns uncertain; after that the first
         * uncertain target stays, because that is the process that may have run it.
         */
        fun attempting(item: OutgoingSend, instance: String?): OutgoingSend =
            item.copy(inFlight = true, sentTo = if (item.uncertain) item.sentTo else instance)

        /** Uncertain persisted attempts may already have run; park until resumable proves a repeat can be deduplicated. */
        fun restored(queue: OutgoingQueue, sentence: String): OutgoingQueue = OutgoingQueue(
            queue.items.map {
                if (!it.inFlight) it
                else it.copy(inFlight = false, uncertain = true, parked = true, lastError = sentence)
            },
        )

        /** Resume uncertain sends only to the same deduping instance; restarts forget IDs, and refusals stay parked. */
        fun resumable(queue: OutgoingQueue, instance: String?): OutgoingQueue = OutgoingQueue(
            queue.items.map {
                val same = instance != null && it.sentTo == instance
                if (it.parked && it.uncertain && !it.refused && same) resumed(it) else it
            },
        )

        /** The restart sentence, worded once so the chip and the Settings row cannot disagree. */
        const val INTERRUPTED = "The app closed before this was confirmed."

        fun toJson(queue: OutgoingQueue): JsonObject = JsonObject().apply {
            addProperty("v", MobileProtocol.VERSION)
            add("items", JsonArray().also { arr -> queue.items.forEach { arr.add(it.toJson()) } })
        }

        /** Show parked work before waiting work because it needs user action; hide the chip for an empty lane. */
        fun chipLine(items: List<OutgoingSend>, deliveringId: String?): String? {
            if (items.isEmpty()) return null
            val parked = items.firstOrNull { it.parked }
            if (parked != null) {
                val reason = parked.lastError?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "The machine did not confirm it."
                return "Not sent · $reason"
            }
            if (items.any { it.clientMessageId == deliveringId }) return "Sending…"
            val n = items.size
            return if (n == 1) "Queued · waiting for the machine"
            else "Queued ($n) · waiting for the machine"
        }

        /**
         * The parked message as the reader typed it, quoted; photos named when there is no text.
         * Null for an item with neither, which the send path never enqueues.
         */
        fun preview(item: OutgoingSend): String? {
            val text = item.prompt.trim()
            val photos = item.attachmentIds.size
            val photoNote = when (photos) {
                0 -> ""
                1 -> "1 attachment"
                else -> "$photos attachments"
            }
            return when {
                text.isNotEmpty() && photos > 0 -> "“$text” · $photoNote"
                text.isNotEmpty() -> "“$text”"
                photos > 0 -> photoNote.replaceFirstChar(Char::uppercase)
                else -> null
            }
        }

        fun fromJson(o: JsonObject?): OutgoingQueue {
            val array = o?.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return OutgoingQueue()
            return OutgoingQueue(
                array.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(OutgoingSend::fromJson) }
                    .takeLast(MAX_ITEMS),
            )
        }
    }
}
