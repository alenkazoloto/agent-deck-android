---
name: pattern-null-key-collapses-per-conversation-guards
description: In the mobile app a new chat's conversation key is null, so per-key filters, ordering and `screen.key == item.key` comparisons silently merge every project.
metadata:
  type: project
---

`key == null` means "a chat this send will start" in the mobile companion. Any guard that groups,
filters or compares on that key treats **all** new chats — across every project, vendor and
account — as one conversation.

**Why:** seen in M5: `outgoing.forKey(null)` deduped by prompt text across projects,
`OutgoingQueue.due` serialised every project's new chat behind one parked item, and
`(screen as? Screen.Conversation)?.key == item.key` evaluated `null == null` to true on
non-conversation screens, routing a new-chat error into a `notice` nothing paints.

**How to apply:** for each new `key`-keyed predicate, substitute null and ask what the expression
means then. Grouping new chats needs `projectPath` (plus vendor/account) in the key; equality
checks against a nullable screen key need an explicit non-null guard.

Related: [[pattern-queue-mutators-dont-restart-the-pump]]
