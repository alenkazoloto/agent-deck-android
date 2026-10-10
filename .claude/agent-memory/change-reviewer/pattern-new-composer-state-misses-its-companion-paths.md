---
name: pattern-new-composer-state-misses-its-companion-paths
description: A new per-conversation composer field (photos, picks, drafts) added to DeckState/OutgoingSend is routinely wired only into the happy send, leaving switchTo, editQueued and the ViewModel's blank-prompt guard stale.
metadata:
  type: feedback
---

When a change adds a new piece of composer state on the phone (`DeckState.*` map keyed by draft
key, or a new `OutgoingSend` field), check four companion paths before accepting it — each has
been missed at least once:

1. `switchTo(machine)` — resets `drafts`, `outgoing`, `composerPicks`; a new map added later is
   usually forgotten, so machine A's state leaks into machine B's composer.
2. `editQueued(id)` — puts `item.prompt` back into the draft; any sibling field on the queue item
   (attachment ids, picks) is dropped and silently lost from the message being edited.
3. The ViewModel entry point's own guard (`send`/`startNewChat` both begin
   `if (prompt.isBlank()) return`). Widening the composer's *enabled* condition in Compose
   without widening that guard produces an enabled button that does nothing — a silent refusal,
   which `RULES.md` W5 forbids.
4. Clamp-after-the-fact (`if (existing.size >= MAX) state`) swallows work the user already paid
   for; the clamp belongs before the network call, or it owes a sentence.

**Why:** the send path is what the author tests; these four are reached only by a second gesture
(switch machine, Edit a parked item, tap Send with no text, attach one too many).

**How to apply:** any diff touching `DeckViewModel` + a Compose composer — grep for the new field
name and expect hits in all of `switchTo`, `editQueued`, the blank guard and the clear-on-send.
Related: [[pattern-null-key-collapses-per-conversation-guards]].
