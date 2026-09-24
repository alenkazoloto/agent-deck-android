package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRunSelection
import com.github.claudeagents.core.mobile.MobileSendRequest
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * One instruction the user has already committed to, waiting for a machine that is not
 * listening yet.
 *
 * [parked] is the whole reason this type has a state at all. An item that never reached the
 * machine can be redialled as often as the link allows; an item whose bytes went into the
 * output stream may or may not have been read, and repeating *that* on the app's own initiative
 * is how a phone bills a user twice for one prompt. The queue therefore parks it and asks —
 * unless the machine advertises [MobileProtocol.Capability.SEND_DEDUPE], in which case it has
 * promised to collapse the repeat and the retry is safe (`docs/architecture/mobile.md`).
 *
 * [clientMessageId] is minted once, at the tap, and survives every retry: it is what that
 * promise is keyed on, so a new id per attempt would quietly undo it.
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
     * Photos already uploaded to this machine, waiting for the prompt that names them.
     *
     * Persisted with the item because the upload happens at the tap and the send may not: a
     * process death between the two would otherwise leave a photo staged on the machine and a
     * prompt that no longer mentions it. An id the machine has since expired is dropped on its
     * side with a notice, never a refusal, so a stale one here costs a sentence and not the send.
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
    /**
     * The machine answered and said no. Distinct from [parked] because only *this* kind of park
     * must survive a link that comes back: an uncertain delivery can be resumed the moment the
     * machine proves it honours a client id, but a refusal is an answer, and a link is not a
     * second opinion on it.
     */
    val refused: Boolean = false,
    /**
     * An attempt has begun and its outcome is not known yet.
     *
     * Persisted, and distinct from [uncertain], which is a *finished* attempt's verdict. This
     * one only ever matters across a process death: the app that wrote it never came back to
     * say how the attempt ended, so the next start must treat it as possibly delivered.
     */
    val inFlight: Boolean = false,
    /**
     * The [com.github.claudeagents.core.mobile.MobileHello.sendInstance] the uncertain attempt
     * went to. An IDE restart forgets which ids it accepted, so only that same instance can
     * collapse a repeat; the retry names it ([MobileSendRequest.retryOf]) and anything else parks.
     */
    val sentTo: String? = null,
) {
    val newChat: Boolean get() = key == null

    /**
     * Which line this send stands in.
     *
     * The conversation, or — for a chat that does not exist yet — the project it will be
     * started in. Not the bare null key: every queued new chat would share one lane, so a
     * parked new chat on one project would hold up a new chat on another, and two projects
     * could not queue the same sentence.
     */
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

/**
 * The instructions this phone owes a machine, in the order they were typed.
 *
 * It is a value, not a store: everything here is a pure function of the list and a clock, so
 * the schedule and the parking rules can be asked questions without a socket, a keystore or a
 * dispatcher. [SecureStore] holds the bytes; [dev.agentdeck.companion.DeckViewModel] drains it.
 *
 * **Order is submission order and never changes.** A queue that retried the newest first would
 * deliver two instructions to one agent in the reverse of the order the user meant them.
 */
data class OutgoingQueue(val items: List<OutgoingSend> = emptyList()) {

    val isEmpty: Boolean get() = items.isEmpty()

    fun forKey(key: String?): List<OutgoingSend> = items.filter { it.key == key }

    fun inLane(lane: String): List<OutgoingSend> = items.filter { it.lane == lane }

    /**
     * Replaces the row already under that id **in place**, or appends a new one.
     *
     * In place, because a failed attempt writes its backoff back through here: appending would
     * send the item that just failed to the end of the queue, behind everything typed after it,
     * and the order the user wrote their instructions in is the one thing this list owes them.
     */
    fun with(item: OutgoingSend): OutgoingQueue {
        val at = items.indexOfFirst { it.clientMessageId == item.clientMessageId }
        val next = if (at >= 0) items.toMutableList().also { it[at] = item } else items + item
        return OutgoingQueue(next.takeLast(MAX_ITEMS))
    }

    fun without(id: String): OutgoingQueue = OutgoingQueue(items.filterNot { it.clientMessageId == id })

    fun find(id: String): OutgoingSend? = items.firstOrNull { it.clientMessageId == id }

    /**
     * The oldest item the link may carry now — and never one queued *behind* another for the
     * same conversation.
     *
     * That second clause is the order guarantee, and it has to be here rather than in the
     * drain: an item that failed is waiting out a backoff, and without this the instruction the
     * user typed after it would overtake it and reach the agent first. A conversation whose
     * head is parked therefore stops delivering until the reader settles it, which is the
     * correct answer — the next sentence assumes the parked one landed. Another conversation's
     * queue is unaffected; they are different agents and nothing about one orders the other.
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
        /**
         * Enough that a train tunnel cannot lose a morning's instructions, small enough that the
         * file stays a file. The oldest are dropped rather than the newest: a queue at the cap is
         * already a pathological state, and the thing the user typed a second ago is the one they
         * still remember typing.
         */
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

        /**
         * What one failed attempt does to an item.
         *
         * [reachedMachine] is the fork this whole type exists for: bytes on the wire mean the
         * machine may already be running the prompt, so the item parks unless [dedupes] says the
         * machine will recognise the repeat. A refusal always parks — the machine answered, and
         * redialling an answer is not a retry, it is an argument.
         */
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

        /**
         * What a queue read off the disk means.
         *
         * Any item marked [OutgoingSend.uncertain] was on the wire when this app last stopped,
         * and nobody recorded how it ended — the machine may have run the prompt. Read back as
         * "due", it would be repeated automatically on the next start, which is the one thing
         * the uncertain-delivery rule forbids. So it parks and waits for the reader, and
         * [resumable] is what un-parks it once the machine proves it collapses a repeat.
         */
        fun restored(queue: OutgoingQueue, sentence: String): OutgoingQueue = OutgoingQueue(
            queue.items.map {
                if (!it.inFlight) it
                else it.copy(inFlight = false, uncertain = true, parked = true, lastError = sentence)
            },
        )

        /**
         * The items a machine honouring `send-dedupe` may take back automatically: everything
         * parked only because delivery was uncertain, **and sent to this same [instance]**. A
         * restarted IDE has forgotten the ids it accepted, so its repeat would run twice; an
         * unknown instance (an older plugin) proves nothing either. A refusal is never in this
         * set — the machine answered, and a reconnect does not change its answer.
         */
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

        /**
         * The one line the composer's chip shows for a conversation, or null when it has
         * nothing waiting — progressive disclosure: an empty queue is not a status.
         *
         * A parked item wins over a waiting one because it is the only state that needs the
         * reader: the waiting one is the app doing its job.
         */
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
                1 -> "1 photo"
                else -> "$photos photos"
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
