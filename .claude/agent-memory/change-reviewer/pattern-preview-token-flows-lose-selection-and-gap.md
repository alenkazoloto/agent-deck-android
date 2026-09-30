---
name: pattern-preview-token-flows-lose-selection-and-gap
description: Phone preview-token/confirm write flows (commit, revert) — check stale re-open keeps the reader's picks, the token's scope vs the chosen subset, and the revalidate-to-write gap
metadata:
  type: project
---

Phone "preview → token → confirmed POST" writes (Changes-tab commit, revert) are cloned from one another, and the clones drift.

- The stale path re-opens the preview. Check that the reader's selection survives it. The commit flow keeps a Draft and intersects it with the new offer, but the first revert clone re-ticked every file. For a destructive write, that re-ticks exactly the file the reader unticked because it was changing.
- The token hashes the whole plan, including unticked files. Anything the host recomputes for the subset *after* the token check (for example, foreign overlaps) is compared against itself, not against what the preview showed.
- The host's write hops to the EDT with `ModalityState.nonModal()` after revalidation and is not cancelled on the 8 s budget. An open desk modal makes the gap between revalidation and write unbounded, while the desk's own route only makes an immediate EDT hop.

**Why:** Found in review on 2026-09-23. The desk's guards hold only because its revalidation happens right before its write.
**How to apply:** When reviewing a new phone write slice, diff its data-layer flow against the sibling slice's (`ReviewCommit.kt`) line by line for selection, stale and ledger handling. Related: [[pattern-new-composer-state-misses-its-companion-paths]].
