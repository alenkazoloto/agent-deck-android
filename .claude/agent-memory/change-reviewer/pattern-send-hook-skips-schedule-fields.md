---
name: send-hook-skips-schedule-fields
description: An early-return hook at the top of MobileActions.send bypasses dueAtMs/repeat/afterRun, so phone "Schedule into chat" runs the prompt immediately
metadata:
  type: project
---

`/v1/send` carries more than a reply. The phone's Schedule-into-chat, limit continuation, and "after this run" also post there, with `dueAtMs`/`repeat*`/`afterRun`. A new conversation kind that intercepts `send` before the scheduling block (for example the ACP hook, 2026-09-24) sends every such prompt now and answers `running`.

**Why:** the phone gates schedule offers on hello capabilities, not on the key's kind, so the offer stays visible for the new key.

**How to apply:** when a change adds a key-specific branch in `MobileActions.send`, check that it handles or rejects future `dueAtMs`/`afterRun`. Also check `MainActivity`'s `scheduleIntoChat`/`limitContinuation` gates, and the `SwipeableRow`/`FleetRow` TalkBack actions, which take `canOrganize` separately from `RowSheet`.
