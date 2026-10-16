---
name: pattern-slow-answer-drops-finished-load
description: Bridge routes that answer "slow, try again" over an in-flight future map usually evict the future on completion, so a late retry restarts the work instead of reading it.
metadata:
  type: project
---

Bridge routes that bound a desk-side load with `future.get(timeout)` and answer "slow — try again"
often dedupe through an in-flight map whose `whenComplete` removes the entry. A retry that lands
after completion then starts a fresh load and times out again; the promised "a retry reads it"
only holds inside a narrow window. Seen in the Codex `/diff` phone route (2026-09-24).

**Why:** the in-flight map only joins running work; nothing retains the finished result.

**How to apply:** for any SLOW/pending outcome, check what a retry *after* completion receives,
and whether follow-up reads (e.g. `?path=` for one file) re-run the same slow load and are then
mis-rendered by the phone flow (SLOW mapped to "gone"/empty). Related: [[pattern-ledger-reruns-ambiguous-failure]].
