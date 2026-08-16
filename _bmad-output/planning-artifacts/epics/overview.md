## Overview

This document provides the complete epic and story breakdown for Puber, decomposing the requirements
from the PRD, Architecture spine, and the SPEC kernel into implementable stories.

**Authority chain.** `ARCHITECTURE-SPINE.md` governs every technical decision; the PRD governs product
shape; `SPEC.md` is the preservation-validated contract that distills both and carries the
`slice` / `property` / `enabler` classification that drives decomposition. `docs/puber.md` and
`docs/tickets/pb-*.md` are historical and explicitly non-authoritative — excluded from extraction.

**No UX design document exists.** Puber is a backend system; its only UI is FR-50's lightweight live
operational dashboard, whose shape is governed by AD-51 and AD-37 rather than by a UX spec. The
UX Design Requirements section below is therefore intentionally empty.

### Testing policy — tests ship with the feature that needs them

**No story in this breakdown exists solely to add tests, and none may be added later.** Every story
carries the tests that prove its own acceptance criteria; a feature is not done until it is proven.
A separate "write the tests for X" story is forbidden, because it lets X be marked done while
unproven and turns its proof into work that can be deprioritised independently of the feature it
belongs to.

**The diagnostic:** a story must *deliver* a requirement, not merely *prove* one. A story that
delivers an FR or a CAP is legitimate even when tests are its only consumer for a phase — the
Simulator (FR-49) is the standing example. A story that delivers no requirement and exists only to
prove requirements delivered elsewhere is the forbidden shape, and its criteria belong on the stories
that deliver them.

This has a specific consequence for the `property` capabilities of `SPEC.md` — CAP-13 (race-safe
concurrency), CAP-20 (declared status vs. observed reachability), CAP-23 (failures degrade),
CAP-24 (duplicate delivery is safe) and CAP-31 (every transition is audited). SPEC describes each as
*"acceptance criteria on every story that could break it, plus a suite that proves it."* Both halves
still hold — but **the suite is built inside the stories whose behaviour it proves, never as a story
of its own.** A property therefore surfaces only as acceptance criteria, distributed across every
story that could violate it.

**Standing acceptance criteria.** The following apply to every story performing the action described,
whether or not the story restates them; they are written out on individual stories only where the
detail is easy to get wrong:

- Any guarded conditional update affecting **zero rows is a rejection**, never a silent success, and
  never a retry treated as success (AD-15).
- Any bounded time window is exercised by **advancing the `Clock`**, never by sleeping (NFR-9).
- Any metric assertion reads a **delta across the action under test**, never an absolute, and no
  reset hook is added to a service (Metrics convention, AD-56).
- Any lost race maps to `ABORTED`, is retried internally, and is **never surfaced to the caller** (AD-38).
- Any consumer or externally-triggered handler is **idempotent on a stable event identifier** (NFR-4,
  AD-36) — binding from Epic 4 onward, where such handlers first exist.

