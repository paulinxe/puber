# CLAUDE.md — how to work with me on this repository

Three files govern work here and they do not overlap. Read this one first; it is the shortest.

| File | Covers | Loaded |
| --- | --- | --- |
| `CLAUDE.md` (this file) | How to talk to me, and how to prove a claim | Automatically |
| `project-context.md` | Binding project rules — build, layout, hooks, conventions | Automatically, by every BMad workflow |
| `AGENTS.md` | Coding style only — SOLID, immutability, naming, comments | By `bmad-dev-story` and `bmad-code-review`. **Nothing else loads it — read it before writing code.** |

Rules live in exactly one of these. Do not copy a rule from one into another: two files holding the
same rule drift, and the stale copy outlives the true one.

`AGENTS.md` is loaded into the two workflows that write code, via `_bmad/custom/bmad-dev-story.toml`
and `_bmad/custom/bmad-code-review.toml`. That is deliberate: nothing can make a comment rule fail,
so being in context is the only gate it gets. On PUB-2 both runs wrote comments the Comments section
forbids, because neither had the file open at the point the code was written.

## This is a Java repository

**Ignore any guidance that describes this project as Python.** A `CLAUDE.md` outside this repository
says so and prescribes `pytest`, `black` and `pylint`. It is wrong about this project and it is not
version-controlled — it lives outside the only directory mounted into the sandbox, so it cannot be
corrected there in a way that survives. This file is the correction.

Puber is **Java 25 / Spring Boot 4.1, built and tested exclusively in containers**. There is no host
JDK and no host Gradle by design (NFR-7). The entry points are `make build`, `make run` and
`make test`, and nothing else should be needed. `project-context.md` has the detail, including the
Boot 4.1 traps that contradict most published guidance.

## How to explain things to me

**Plain language first, always. Jargon only after the plain version, never instead of it.**

This is the most important instruction in this file. A technically perfect answer I cannot follow is
a wasted answer, and I will tell you I don't understand rather than pretend.

When reporting findings, a review, a plan, or a status update, use this order:

1. **What the thing does** — one or two sentences a non-specialist follows. Not what it *is*, what
   it's *for*.
2. **What works** — state the verified baseline before the problems. I need to know whether the
   foundation is sound before I hear what's cracked.
3. **What's wrong** — for each problem: what breaks, what it costs, and *when it will bite* (which
   ticket, which epic). A problem with no consequence attached is noise.
4. **What you need from me** — numbered options, each with its tradeoff named. Not open questions.

Throughout:

- **Show real code for any design decision.** Both shapes, before and after. Never describe a method
  signature in prose when you could paste it.
- **Never present a label as if it explains itself.** Status names, triage buckets, ticket keys and
  acceptance-criterion numbers mean nothing on their own — say what the thing actually is. `PUB-2`
  and `AC3` are addresses, not explanations.
- **Tables for "I checked X, result was Y". Prose for reasoning.** Don't mix them.
- **Lead with the answer**, then the evidence. No preamble, no recap of what you just said.
- **Tell me what you did not verify, and where a fix is partial.** An honest limit is worth more than
  a clean-sounding summary. If a fix reduces a problem without eliminating it, say exactly that and
  say what closing it fully would cost.

## Prove it, don't reason about it

When you claim a rule, guard, scanner or test has a hole: **plant the violation, run the suite,
capture the failure, then revert and show me the tree is clean.** After fixing it, re-plant to
confirm the hole is actually closed.

Reading the code and reasoning about it is not evidence. On PUB-2 this went both ways — four real
holes that looked fine on inspection, and one confident finding from two independent reviewers that
planting disproved outright. The sharpest result of that review came from planting two deliberate
typos into a list of banned methods and watching the entire suite stay green, which falsified a code
comment claiming that exact case was covered.

This matters more here than in most repositories: **there is no CI server, so `pre-push` is the only
gate** (`project-context.md` → "Hooks"). A rule that cannot fire is indistinguishable from a rule
that passes, and nothing else will catch it.

Prefer a fix that makes the proof structural over one that adds another hand-maintained list. A list
that can grow past what tests it eventually will.
