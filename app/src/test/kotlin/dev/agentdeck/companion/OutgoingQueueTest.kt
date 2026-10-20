package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.OutgoingQueue
import dev.agentdeck.companion.data.OutgoingSend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue's whole contract, without a socket: what retries by itself, what parks and waits
 * for the user, and what survives being written to a file and read back.
 *
 * The distinction these tests exist for is the one the plan calls "uncertain delivery": an
 * attempt that handed bytes to the machine may already have run the prompt, so repeating it is
 * a decision the app is not allowed to make on its own unless the machine has promised to
 * collapse the repeat.
 */
class OutgoingQueueTest {

    private fun item(id: String = "a", prompt: String = "run the tests") = OutgoingSend(
        clientMessageId = id,
        key = "claude:/repo:session",
        projectPath = "/repo",
        vendor = AgentVendor.CLAUDE,
        label = "Fix the parser",
        prompt = prompt,
    )

    @Test fun `a parked message is previewed word for word, photos named when there is no text`() {
        assertEquals("“run the tests”", OutgoingQueue.preview(item()))
        assertEquals("“look” · 2 attachments", OutgoingQueue.preview(item(prompt = " look ").copy(attachmentIds = listOf("p1", "p2"))))
        assertEquals("1 attachment", OutgoingQueue.preview(item(prompt = "").copy(attachmentIds = listOf("p1"))))
        assertEquals(null, OutgoingQueue.preview(item(prompt = "  ")))
    }

    @Test fun `a send that never reached the machine retries on a climbing backoff`() {
        val first = OutgoingQueue.afterFailure(
            item(), nowMs = 1_000, error = "offline",
            refused = false, reachedMachine = false, dedupes = false,
        )
        assertFalse(first.parked)
        assertEquals(1_000 + OutgoingQueue.FIRST_BACKOFF_MS, first.nextAttemptAtMs)

        val second = OutgoingQueue.afterFailure(
            first, nowMs = 10_000, error = "offline",
            refused = false, reachedMachine = false, dedupes = false,
        )
        assertEquals(10_000 + 2 * OutgoingQueue.FIRST_BACKOFF_MS, second.nextAttemptAtMs)
    }

    @Test fun `the backoff climbs to five minutes and stops there`() {
        assertEquals(OutgoingQueue.FIRST_BACKOFF_MS, OutgoingQueue.backoffMs(1))
        assertEquals(OutgoingQueue.MAX_BACKOFF_MS, OutgoingQueue.backoffMs(12))
        assertEquals(OutgoingQueue.MAX_BACKOFF_MS, OutgoingQueue.backoffMs(40))
    }

    @Test fun `an uncertain delivery parks against a plugin that cannot dedupe`() {
        val parked = OutgoingQueue.afterFailure(
            item(), nowMs = 1_000, error = "interrupted",
            refused = false, reachedMachine = true, dedupes = false,
        )
        assertTrue(parked.parked)
        assertTrue(parked.uncertain)
        assertNull(OutgoingQueue(listOf(parked)).due(nowMs = Long.MAX_VALUE))
    }

    @Test fun `an uncertain delivery retries by itself once the machine honours the id`() {
        val waiting = OutgoingQueue.afterFailure(
            item(), nowMs = 1_000, error = "interrupted",
            refused = false, reachedMachine = true, dedupes = true,
        )
        assertFalse(waiting.parked)
        // The mark stays: the reader is still owed the sentence about a possible duplicate if
        // this later parks for another reason.
        assertTrue(waiting.uncertain)
        assertEquals("a", OutgoingQueue(listOf(waiting)).due(nowMs = 1_000_000)?.clientMessageId)
    }

    @Test fun `a refusal always parks, dedupe or not`() {
        val parked = OutgoingQueue.afterFailure(
            item(), nowMs = 1_000, error = "This chat has reached its spending limit.",
            refused = true, reachedMachine = true, dedupes = true,
        )
        assertTrue(parked.parked)
        assertEquals("This chat has reached its spending limit.", parked.lastError)
    }

    @Test fun `retry clears the park and the wait`() {
        val parked = OutgoingQueue.afterFailure(
            item(), nowMs = 1_000, error = "spend limit",
            refused = true, reachedMachine = false, dedupes = false,
        )
        val resumed = OutgoingQueue.resumed(parked)
        assertFalse(resumed.parked)
        assertEquals(0L, resumed.nextAttemptAtMs)
        assertEquals("a", OutgoingQueue(listOf(resumed)).due(nowMs = 0)?.clientMessageId)
    }

    @Test fun `the oldest due item is delivered first`() {
        val queue = OutgoingQueue(listOf(item("a", "first"), item("b", "second")))
        assertEquals("a", queue.due(nowMs = 0)?.clientMessageId)
        assertEquals("b", queue.without("a").due(nowMs = 0)?.clientMessageId)
    }

    @Test fun `a re-enqueued retry replaces its row in place rather than joining the back`() {
        val queue = OutgoingQueue(listOf(item("a"), item("b")))
            .with(item("a").copy(attempts = 3))
        assertEquals(listOf("a", "b"), queue.items.map { it.clientMessageId })
        assertEquals(3, queue.items.first().attempts)
    }

    /** The order guarantee: a backoff on one instruction must not let the next one overtake it. */
    @Test fun `a later send into the same conversation waits behind the one still failing`() {
        val waiting = OutgoingQueue.afterFailure(
            item("a"), nowMs = 0, error = "offline",
            refused = false, reachedMachine = false, dedupes = false,
        )
        val queue = OutgoingQueue(listOf(waiting, item("b")))
        assertNull("the second instruction reached the agent first", queue.due(nowMs = 1))
        assertEquals("a", queue.due(nowMs = OutgoingQueue.FIRST_BACKOFF_MS)?.clientMessageId)
    }

    @Test fun `a parked conversation stops its own queue and no other`() {
        val queue = OutgoingQueue(
            listOf(
                item("a").copy(parked = true),
                item("b"),
                item("c").copy(key = "claude:/other:session"),
            ),
        )
        assertEquals("c", queue.due(nowMs = 0)?.clientMessageId)
        assertEquals(0L, queue.nextWakeMs(nowMs = 0))
        assertNull(OutgoingQueue(listOf(item("a").copy(parked = true), item("b"))).nextWakeMs(0))
    }

    @Test fun `the queue is bounded and drops the oldest`() {
        var queue = OutgoingQueue()
        repeat(OutgoingQueue.MAX_ITEMS + 5) { queue = queue.with(item("id-$it")) }
        assertEquals(OutgoingQueue.MAX_ITEMS, queue.items.size)
        assertEquals("id-5", queue.items.first().clientMessageId)
    }

    @Test fun `a queued send survives the round trip through the file`() {
        val queue = OutgoingQueue(
            listOf(
                item("a").copy(
                    model = "claude-opus-5", effort = "high", permissionMode = "plan",
                    attempts = 2, nextAttemptAtMs = 77, lastError = "offline",
                    parked = true, uncertain = true, stopFirst = true, sentTo = "ide-1",
                ),
                item("b").copy(key = null, accountId = "work"),
                item("c").copy(key = null, acpAgentId = "gemini"),
            ),
        )
        val restored = OutgoingQueue.fromJson(
            MobileProtocol.parseObject(OutgoingQueue.toJson(queue).toString()),
        )
        assertEquals(queue, restored)
        assertTrue(restored.items[1].newChat)
    }

    @Test fun `an ACP start names its agent on the wire and holds its own lane, and a keyed send drops it`() {
        val start = item().copy(key = null, acpAgentId = "gemini")
        assertEquals("gemini", start.request().acpAgentId)
        assertNotEquals("an ACP start shared the Claude start's lane", start.copy(acpAgentId = null).lane, start.lane)
        assertNull(item().copy(acpAgentId = "gemini").request().acpAgentId)
    }

    @Test fun `a malformed row is dropped rather than failing the whole queue`() {
        val restored = OutgoingQueue.fromJson(
            MobileProtocol.parseObject("""{"items":[{"id":"a"},{"nonsense":1}]}"""),
        )
        assertTrue(restored.isEmpty)
    }

    @Test fun `the request carries the client id on every attempt and drops the account on a keyed send`() {
        val keyed = item().copy(accountId = "work").request()
        assertEquals("a", keyed.clientMessageId)
        assertNull("a resumed conversation's account is structural", keyed.accountId)
        val fresh = item().copy(key = null, accountId = "work").request()
        assertEquals("work", fresh.accountId)
        assertTrue(fresh.newChat)
    }

    @Test fun `the chip says nothing until there is something to say`() {
        assertNull(OutgoingQueue.chipLine(emptyList(), deliveringId = null))
        assertEquals("Sending…", OutgoingQueue.chipLine(listOf(item()), deliveringId = "a"))
        assertEquals(
            "Queued · waiting for the machine",
            OutgoingQueue.chipLine(listOf(item()), deliveringId = null),
        )
        assertEquals(
            "Queued (2) · waiting for the machine",
            OutgoingQueue.chipLine(listOf(item("a"), item("b")), deliveringId = null),
        )
        assertEquals(
            "Not sent · Spend limit reached.",
            OutgoingQueue.chipLine(
                listOf(item("a"), item("b").copy(parked = true, lastError = "Spend limit reached.")),
                deliveringId = "a",
            ),
        )
    }

    /**
     * The restart contract: an attempt that began and never reported back may already have run
     * on the machine, so the next start asks rather than repeating it.
     */
    @Test fun `an attempt interrupted by process death is parked, not silently retried`() {
        val queue = OutgoingQueue(listOf(OutgoingQueue.attempting(item(), "ide-1")))
        assertTrue(queue.items.single().inFlight)
        assertEquals("a", queue.due(nowMs = 0)?.clientMessageId)

        val restored = OutgoingQueue.restored(queue, OutgoingQueue.INTERRUPTED)
        val row = restored.items.single()
        assertTrue(row.parked)
        assertTrue(row.uncertain)
        assertFalse(row.inFlight)
        assertEquals(OutgoingQueue.INTERRUPTED, row.lastError)
        assertNull("an interrupted attempt was repeated on the next start", restored.due(nowMs = 0))
    }

    @Test fun `a completed attempt is no longer in flight, so a restart leaves it alone`() {
        val failed = OutgoingQueue.afterFailure(
            OutgoingQueue.attempting(item(), "ide-1"), nowMs = 0, error = "offline",
            refused = false, reachedMachine = false, dedupes = false,
        )
        assertFalse(failed.inFlight)
        assertFalse("a dial that never connected was called uncertain", failed.uncertain)
        assertEquals(failed, OutgoingQueue.restored(OutgoingQueue(listOf(failed)), "x").items.single())
    }

    /** A machine that honours the client id may take back an uncertain item; never a refusal. */
    @Test fun `only an uncertain park is resumable, and a refusal never is`() {
        val uncertain = OutgoingQueue.afterFailure(
            OutgoingQueue.attempting(item("a"), "ide-1"), nowMs = 0, error = "interrupted",
            refused = false, reachedMachine = true, dedupes = false,
        )
        val refused = OutgoingQueue.afterFailure(
            item("b"), nowMs = 0, error = "This chat has reached its spending limit.",
            refused = true, reachedMachine = true, dedupes = true,
        )
        val resumed = OutgoingQueue.resumable(OutgoingQueue(listOf(uncertain, refused)), "ide-1")
        assertFalse(resumed.items[0].parked)
        assertEquals("the automatic retry lost its guard", "ide-1", resumed.items[0].request().retryOf)
        assertTrue("a refusal was un-parked by a reconnect", resumed.items[1].parked)
    }

    /**
     * M2 host restart: the IDE that may have run it forgot the ids it accepted, so a hello from
     * a new instance — or from a plugin that names none — is no promise about this send.
     */
    @Test fun `an uncertain park is not resumed by a restarted or unnamed machine`() {
        val uncertain = OutgoingQueue.restored(
            OutgoingQueue(listOf(OutgoingQueue.attempting(item("a"), "ide-1"))), OutgoingQueue.INTERRUPTED,
        )
        assertTrue(OutgoingQueue.resumable(uncertain, "ide-2").items.single().parked)
        assertTrue(OutgoingQueue.resumable(uncertain, null).items.single().parked)
        assertFalse(OutgoingQueue.resumable(uncertain, "ide-1").items.single().parked)
    }

    @Test fun `the first uncertain target sticks, a clean failure forgets it, and the reader's retry drops it`() {
        val lost = OutgoingQueue.afterFailure(
            OutgoingQueue.attempting(item(), "ide-1"), nowMs = 0, error = "lost",
            refused = false, reachedMachine = true, dedupes = true,
        )
        // The backoff retry dials a machine that has since restarted: still names ide-1.
        val again = OutgoingQueue.attempting(lost, "ide-2")
        assertEquals("ide-1", again.request().retryOf)

        val offline = OutgoingQueue.afterFailure(
            OutgoingQueue.attempting(item(), "ide-1"), nowMs = 0, error = "offline",
            refused = false, reachedMachine = false, dedupes = true,
        )
        assertNull(offline.sentTo)
        assertNull(offline.request().retryOf)

        val chosen = OutgoingQueue.retried(again.copy(parked = true, refused = true))
        assertFalse(chosen.uncertain)
        assertNull("the reader's Retry was refused again as unconfirmed", chosen.request().retryOf)
    }

    /** Two projects are two lines. A parked new chat on one must not hold the other's up. */
    @Test fun `queued new chats are laned by project, not merged under the null key`() {
        val here = item("a").copy(key = null, projectPath = "/repo-one")
        val there = item("b").copy(key = null, projectPath = "/repo-two")
        val queue = OutgoingQueue(listOf(here.copy(parked = true), there))
        assertTrue("two projects shared one lane", here.lane != there.lane)
        assertEquals("b", queue.due(nowMs = 0)?.clientMessageId)
    }

    @Test fun `a parked item never wakes the drain`() {
        val queue = OutgoingQueue(listOf(item("a").copy(parked = true)))
        assertNull(queue.nextWakeMs(nowMs = 0))
        assertEquals(0L, queue.with(item("b", prompt = "next").copy(key = "other")).nextWakeMs(nowMs = 0))
    }
}
