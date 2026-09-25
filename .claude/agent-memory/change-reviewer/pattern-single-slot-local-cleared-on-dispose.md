---
name: pattern-single-slot-local-cleared-on-dispose
description: A screen-registered single-slot CompositionLocal state (e.g. snackbar lift) that onDispose resets to 0 goes stale under ScreenHost's AnimatedContent overlap
metadata:
  type: project
---

ScreenHost (MainActivity) is an `AnimatedContent`; the outgoing screen stays composed ~180 ms after the incoming one has laid out. Any "last writer wins" slot that a screen sets in `onSizeChanged`/layout and resets in `onDispose` is zeroed by the outgoing screen *after* the incoming one wrote it, and `onSizeChanged` does not fire again for a constant size.

**Why:** found in the SnackbarLift review (M3 close, 2026-09-23): Conversation→Conversation (branch/notification Open) and NewChat→Conversation leave the lift at 0 dp.

**How to apply:** whenever a change adds per-screen registration into shared composition state, check same-type and sibling transitions in ScreenHost/TwoPane; the fix direction is owner-keyed registration (clear only if still the owner, or max over live owners).
