---
name: pattern-worktree-project-resolution-split
description: Mobile routes resolve a chat's project with a worktree→owning-root fallback, while the scheduler's per-project hooks match basePath exactly; state written by one is invisible to the other for worktree chats.
metadata:
  type: project
---

Mobile routes resolve a project through `MobileActions.openProjectFor`, which falls back to the worktree's owning root. Scheduler hooks (`ScheduledHeadlessExecution` feedbackProject, the `ended` hook in `ScheduledTasksService`) match `basePath == task.projectPath` exactly. A phone send schedules with the worktree folder as `projectPath`.

**Why:** In the 2026-09-23 review-notes review, feedback staged in the owning project's project service could not be found by a headless run for a worktree chat. The run stripped the notes and never admitted or ended the attempt.

**How to apply:** When a phone route writes project-scoped state that a scheduled or headless run consumes later, check that both sides resolve the same Project for a worktree chat. Also check a desk panel claiming the run, which uses `panel.project`.
