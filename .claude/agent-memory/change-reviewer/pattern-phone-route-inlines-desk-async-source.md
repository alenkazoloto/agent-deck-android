---
name: pattern-phone-route-inlines-desk-async-source
description: Phone bridge routes that mirror a desk popup group the desk fetches asynchronously (symbols, index lookups) often run it inline with no deadline, so one slow source delays or drops every sibling row in the same response.
metadata:
  type: feedback
---

When a `/v1/*` phone route adds a source the desk queries on a pooled thread and paints "a turn later" (ProjectSymbols, file index), check whether the route runs it synchronously inside the same response as cheap rows (git, chats, files) with no deadline.

**Why:** the phone's HTTP read timeout drops the whole answer, and cancelled phone requests keep running on the bridge's bounded handler pool; the desk's async isolation is lost. The `#` issues route set the precedent of a hard deadline for exactly this reason.

**How to apply:** trace the order of calls in the route (is the slow source computed before the files?), look for a deadline/future, and check whether `runCatching` around the combined builder means a throw in the new source also erases the old rows.
