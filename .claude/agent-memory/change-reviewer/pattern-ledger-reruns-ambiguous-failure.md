---
name: pattern-ledger-reruns-ambiguous-failure
description: Bridge operation-id ledgers re-run any FAILED entry as "a refusal changed nothing", but a CLI timeout or transport error may already have posted, so a phone poll or resend double-posts.
metadata:
  type: project
---

The mobile `act()` ledgers (MobileWorktreeManaging and the routes copied from it) re-run an entry whose Deferred completed FAILED, on the theory that a refusal changed nothing. FAILED also covers `Exec` timeouts (`ok` is false when `timedOut`), "Could not reach …" HTTP nulls and caught exceptions. Those can land after the forge accepted the write.

**Why:** The 2026-09-23 change-request-review review found this path. The phone polls the same operation id every 1.5 s after WORKING, and its STILL_POSTING resend reuses the id too. If the first attempt completes FAILED between polls, the next ask runs the post again.

**How to apply:** For any non-idempotent verb (post, reply, approve, push), check that the ledger separates pre-flight refusals, which are safe to re-check, from execution failures, which must be read back. Also check phone flows. Closing and reopening a sheet resets `working` but leaves the verb job running, so a second tap can mint a new operation id. See [[pattern-queue-mutators-dont-restart-the-pump]] for another retry/lifecycle gap.
