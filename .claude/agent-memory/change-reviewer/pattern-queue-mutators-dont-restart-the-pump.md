---
name: pattern-queue-mutators-dont-restart-the-pump
description: In DeckViewModel, a self-terminating drain loop plus mutators that only writeQueue strands work; enumerate every writer of the queue and ask which restarts the pump.
metadata:
  type: project
---

A drain/pump coroutine in `mobile/.../DeckViewModel.kt` that `return@launch`s when nothing is due
must be restarted by **every** state change that can make something due again — not only the
enqueue path.

**Why:** M5's outgoing queue exits its loop when `nextWakeMs` is null (all parked / empty), and
head-of-line blocking means removing a parked head makes its siblings due. `writeQueue`,
`editQueued`, `discardQueued` and `switchTo` all mutate the queue without calling `drain()`, so a
committed prompt can sit undelivered indefinitely. `observeLink`'s `recovered` edge
(`Live && previous != Live`) does not fire on a machine switch between two reachable machines, so
it is not a safety net.

**How to apply:** when reviewing any change to a self-terminating loop here, list every writer of
the collection the loop reads and check each one either restarts the loop or provably cannot make
an item due. Same question for `drainJob?.isActive` guards: a cancelled job reports inactive while
its HTTP call is still on an IO thread.

Related: [[pattern-null-key-collapses-per-conversation-guards]]
