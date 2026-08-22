---
baseline_commit: 41f2540a2860f98b3a1d087b3bdaddf4542afd2b
---

# Story 1.2: Time is injectable and never read directly

Ticket: PUB-2
Epic: 1 — Foundations & Fare Quote
Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer,
I want every read of the current time to go through a `Clock` strategy that is monotonic for durations and wall-clock for recorded facts,
so that no deadline in production can be broken by a clock correction, and timing behaviour is testable in seconds rather than by waiting.

## Scope orientation — read this first

**PUB-1 shipped the substrate; this story is the first code with behaviour.** The repository already
holds the `Makefile`, `infra/docker-compose.yml`, both git hooks, `static-analyzers/`, the
containerized test runner, and `matching-service` with health, metrics, a Flyway baseline holding **no
tables**, six ArchUnit rules and eight/six unit/integration tests. Read `project-context.md` and
`AGENTS.md` before writing a line — both are binding, and neither is a summary of the other.

**This story delivers three things and nothing else:**

1. A `Clock` strategy with two time semantics — wall clock for recorded facts, monotonic for
   in-process deadlines — wired into the running service.
2. The mechanical rule that makes every *other* way of reading time a build failure.
3. A controllable clock for tests, so every bounded window in Epics 2–5 is exercisable by advancing
   time rather than by waiting.

**Deliberately excluded — do not build them:**

- **No time constant from AD-46.** The offer timeout, `NO_DRIVER` budget, staleness windows and
  session expiry belong to the stories that use them. This story delivers the mechanism, not one value.
- **No scheduler, no `@Scheduled`, no sweep, no worker.** The first one arrives in Epic 2.
- **No `fare_rules`, no migration, no table.** V1 stays the only migration; Story 1.3 adds V2.
- **No Simulator seeding.** AC5's "same seed" is CAP-40's Simulator half, delivered in Epic 7
  (`roadmap.md#week-one-before-any-phase` scheduled the *clock abstraction* here and the Simulator
  there). What this story owes is a clock whose script is deterministic — see AC5 below.
- **No second service.** AC1 says "every service"; one exists. See Dev Notes → "One service today,
  five later".

## Acceptance Criteria

Verbatim from
[`epics/epic-1-foundations-fare-quote.md#story-12-time-is-injectable-and-never-read-directly`],
with the implementation-bearing reading of each recorded underneath it. The **Given/When/Then is the
requirement**; the reading is how it is satisfied here and is where a reviewer should look for a
deviation.

### AC1 — Nothing reads time except the `Clock`

**Given** production code across every service
**When** a static-analysis test scans it
**Then** no call to `Instant.now()`, `System.currentTimeMillis()`, `LocalDateTime.now()` or SQL `now()`
appears outside the `Clock` implementation itself (NFR-9, AD-58)

- The four names are **examples, not the closed set** — the requirement is that no time read escapes
  the `Clock`. The banned set is enumerated in Dev Notes → "The banned set" and includes
  `System.nanoTime()`, every `java.time` `now()` factory, and `java.time.Clock`'s system factories.
- Two mechanisms, because one cannot cover both halves: **ArchUnit** for Java call sites (bytecode),
  and a **source scan** for SQL, which ArchUnit cannot see — a string literal and a `.sql` file are
  invisible to a bytecode importer.
- Scope is **production code**: `src/main`. Test code is excluded by `ImportOption.DoNotIncludeTests`
  and by the scan's own paths.
- **The rule must be shown to be capable of failing.** A rule that has never evaluated a violation is
  indistinguishable from a rule that cannot fire — the finding PUB-1 deferred over its six existing
  rules ([`deferred-work.md`]). AC1 is not met by a green rule alone.

### AC2 — A test advances time instead of waiting

**Given** a test
**When** it advances the clock by a chosen duration
**Then** code under test observes the new time immediately
**And** the test does not sleep (Testing convention)

- Delivered as a controllable `Clock` implementation in test sources. No `Thread.sleep`, no
  `Awaitility`, no polling loop, no real elapsed time anywhere in this story's tests.

### AC3 — Two time semantics, each used for its own purpose

**Given** the `Clock` strategy
**When** production code measures an elapsed duration or an in-process deadline
**Then** it reads a **monotonic** source
**And** when it records a persisted fact, it reads **wall clock** (NFR-9, Timestamps convention)

- The interface makes the split **structural rather than remembered**: the deadline path returns a
  type carrying a monotonic reading, and the wall-clock path returns an `Instant`. Subtracting two
  wall-clock readings to get a duration must be visibly the wrong tool, not merely discouraged.
- **The convention has a third case** the AC does not name and the interface must not foreclose: a
  **durable** deadline that outlives the process or crosses services uses **wall clock** — AD-46's
  `CAPTURE_FAILED` cooldown and AD-58's `next_attempt_at` are that case, and no in-process clock could
  carry either [`ARCHITECTURE-SPINE.md#consistency-conventions`]. Do not add either now; do not design
  a `Clock` that can only produce in-process deadlines.
- **Elapsed-duration measurement has no caller in this story.** See Dev Notes → "What the interface
  gets today, and what it does not".
- **Every wall-clock value is UTC** — `TIMESTAMPTZ`, UTC (Timestamps convention). See Dev Notes →
  "UTC, and the three places it slips".

### AC4 — A clock correction cannot break a deadline

**Given** an NTP correction or a daylight-saving shift landing mid-window
**When** an in-process deadline is evaluated
**Then** it neither fires early nor fails to fire, because the duration was measured monotonically
**And** this holds in production, not only under test (NFR-9)

- Proven in two parts, because a test cannot move the host's clock: the **deadline logic** is proven
  against a wall-clock shift in both directions with the monotonic source advancing normally, and the
  **production path is proven to be the same logic** — the running service's `Clock` bean is the real
  implementation, asserted by an integration test, and AC1's rule leaves it the only class in the
  service permitted to read a system time source at all.

### AC5 — The same clock script produces the same result

**Given** the same seed and the same clock script
**When** a timing test is re-run
**Then** it produces the same result (NFR-9)

- **The seed half belongs to the Simulator (FR-49, Epic 7) and is out of scope here.** What is in
  scope: the controllable clock is deterministic by construction — a fixed start instant and explicit
  advances, with no read of real time on any path — so a scripted scenario replayed twice produces
  identical observations. Proven by a test that runs one script twice and compares.

## Tasks / Subtasks

- [x] **Task 1 — `Clock` strategy and its production implementation** (AC: 3, 4)
  - [x] `com.puber.matching.shared.strategy.Clock` — the interface. Two accessors, per Dev Notes →
        "What the interface gets today, and what it does not".
  - [x] `com.puber.matching.shared.model.Deadline` — immutable value type over a monotonic reading;
        comparison by **difference**, never by absolute value (Dev Notes → "`System.nanoTime()` wraps").
  - [x] `com.puber.matching.shared.strategy.SystemClock` — the only class in the service that reads a
        system time source. Plain class, no Spring annotation.
  - [x] Wall clock is **UTC and nothing else**: `Instant` on the interface, no `ZoneId` parameter, no
        read of the JVM default zone anywhere (Dev Notes → "UTC, and the three places it slips").
  - [x] `com.puber.matching.config.ClockConfiguration` — `@Bean Clock` returning `SystemClock`.
        First inhabitant of `config/`; create the package now and not before.
- [x] **Task 2 — the ArchUnit rule** (AC: 1)
  - [x] Add `timeIsReadOnlyThroughTheClock` to the existing `ArchitectureRulesTest`, over the banned
        set in Dev Notes. Do **not** create a second rules class.
  - [x] Exempt `SystemClock` **by class, not by package** — `shared.strategy` will hold other
        strategies, and none of them should inherit the exemption.
  - [x] No `allowEmptyShould(true)` on this rule: it evaluates real classes from the moment it exists,
        so an empty-set escape hatch would only hide an import failure.
- [x] **Task 3 — prove the rule can fail** (AC: 1)
  - [x] A fixture class under `src/test/java/com/puber/matching/rules/fixtures/` that calls a banned
        method, and a test that runs the same rule against an explicit import of that fixture and
        asserts it **fails**.
  - [x] Extract the rule so the fixture test and `@ArchTest` share one definition — two copies of the
        predicate would let the proven one and the enforced one drift.
  - [x] The six pre-existing rules stay unproven and stay deferred; do not widen this task to cover
        them (Dev Notes → "The deferred vacuity finding").
- [x] **Task 4 — the SQL clause of AC1** (AC: 1)
  - [x] A unit test scanning `src/main/resources/**/*.sql` and `src/main/java/**/*.java` text for
        server-side time functions (list in Dev Notes → "The banned set").
  - [x] **Fail on the first match**, naming the file, the line number and the pattern — a scanner whose
        failure message does not say where is a scanner someone will delete.
  - [x] **Fail also when the scan matched no files at all.** Two different failures, and the second is
        not the rule: it is the guard on the test. A mistyped path
        (`resources/db/migrations/`) or one that does not resolve under the runner's
        `--workdir /workspace/services/matching-service` scans nothing, finds no violations, and stays
        green forever — passing for the wrong reason, which is the failure mode PUB-1's review caught
        twice (a health assertion green while health was DOWN; an unreachable-address test green
        against an address that answered in 0.8 ms). Assert a non-zero file count, not merely a
        non-null directory.
- [x] **Task 5 — the controllable clock** (AC: 2, 5)
  - [x] `ControllableClock` in `src/test/java/com/puber/matching/shared/strategy/`, implementing
        `Clock`: `advance(Duration)` moves wall clock **and** monotonic together;
        `shiftWallClock(Duration)` moves **only** wall clock, in either direction.
  - [x] Reads no real time on any path, including construction.
- [x] **Task 6 — the tests that prove AC2–AC5** (AC: 2, 3, 4, 5)
  - [x] Deadline boundary behaviour: not expired before, expired at and after. No sleeping.
  - [x] Wall-clock shift of ±1 h mid-window changes nothing about when the deadline fires (AC4).
  - [x] `wallClockNow()` tracks real time in `SystemClock`, asserted as a **bracket** between two real
        readings taken around the call — never a tolerance window, which is a flake waiting for a
        loaded machine.
  - [x] Monotonic readings are non-decreasing and independent of the wall-clock accessor.
  - [x] One script, run twice, identical observations (AC5).
  - [x] The UTC guarantee is asserted, not assumed: `ControllableClock`'s start instant and every
        advance land on the same UTC instants regardless of the JVM's default zone — run one test with
        `-Duser.timezone` set to a non-UTC zone if that is what it takes to make the claim real.
- [x] **Task 7 — the production wiring is real** (AC: 4)
  - [x] `src/integrationTest/java/.../ClockWiringIntegrationTest` — the running context exposes
        exactly one `Clock`, and it is `SystemClock`. Without this, the service could ship with no
        clock wired and every unit test would still be green.
- [x] **Task 8 — record what the next story inherits** (AC: 3)
  - [x] Add to `project-context.md`: `Clock.wallClockNow()` is **wall clock, for recorded facts
        only** — durations and deadlines never come from arithmetic over two of its readings; add a
        monotonic accessor to `Clock` instead, and do not give it a `now()`-shaped name. Plus the
        `java.time.Clock` import collision (Dev Notes → "Two traps in the naming").
  - [x] Sharpen the existing `project-context.md` Timestamps line from *"`TIMESTAMPTZ`, UTC"* to name
        the three slips: no `LocalDateTime`/`LocalDate` anywhere, no container or JVM timezone
        dependency, and UTC at every boundary — not only at rest.
  - [x] Update the story's File List and Change Log. Do not copy rules into `AGENTS.md` — it covers
        coding style only, and duplicated rules drift.
- [x] **Task 9 — verify end to end** (AC: all)
  - [x] `make build` green from clean, then `make test`. Both hooks still block on failure.
  - [x] `make static-analysis SERVICE=matching-service` — confirm the new rule runs at **`pre-push`**,
        not `pre-commit`: ArchUnit rules are tests, and `staticAnalysis` is `spotlessCheck` only.

## Review Findings

Code review of 2026-08-21. Three parallel layers (adversarial, edge-case, acceptance audit), then
triage. Every finding below was re-verified against the code before it was rated; the claims marked
*verified by planting* were reproduced by temporarily editing the tree, running `make test-unit`, and
reverting.

**Verified as working, so not findings:** all 20 unit tests and both `ClockWiringIntegrationTest`
tests pass on a forced re-run. AC1's build-failure claim is real and is *stronger* than the story
records — the rule also catches a **method reference** (`Instant::now`) and a **static initializer**,
neither of which the fixture exercises. The banned-set table matches the Dev Notes exactly; the eight
SQL patterns match exactly; `\bnow\s*\(` correctly does not trip on `wallClockNow(`; the six
pre-existing rules and their flags are preserved verbatim; nothing from "Deliberately excluded" was
built; no dependency was added. `make test` is red only on PUB-1's environmental
`HealthReportsDownPromptlyIntegrationTest` precondition (file last touched by `41f2540`, untouched
here).

### Decision needed — both resolved by the repo owner, 2026-08-21

**Resolution 1 — flip the read-back onto the clock.** `Deadline.hasExpired(Clock)` is gone;
`Clock.hasReached(Deadline)` replaces it, and the comparison-by-difference logic stayed in one place
as `Deadline.hasBeenReachedAt(long)`. `Deadline` no longer imports `Clock`, so the
`shared.model` ↔ `shared.strategy` cycle is gone. **Honest limit:** this removes the *inviting* form
of the mismatch, not the possibility — `clock.hasReached(deadlineFromAnotherClock)` still compiles.
Eliminating it needs an origin identity on `Deadline`, which was judged YAGNI while the service has
exactly one clock bean. Recorded in `project-context.md` so the next author inherits the shape.

**Resolution 2 — enforce the zone rule now.** `ZoneId.systemDefault()` and `TimeZone.getDefault()`
are in the banned table and fail the build; verified by planting both. Slip 1 (banning
`LocalDateTime`/`LocalDate` as *types* in signatures and DTOs, not just their `now()` factories) was
**not** taken — it stays prose in the Timestamps convention.

- [x] [Review][Decision] **`Deadline` can be read back by a `Clock` that did not produce it, and the
      two packages form a cycle** — `shared/model/Deadline.java:3,22`, `shared/strategy/Clock.java:3`.
      Two findings with one fix, which is why they are a decision and not a patch. (a) `hasExpired`
      accepts *any* `Clock`; monotonic origins are per-instance and arbitrary, so a `Deadline` from
      `ControllableClock` (origin `0`) checked against the injected `SystemClock` reads permanently
      expired, and the reverse never expires. AC3's reading demands the wrong thing "does not
      type-check"; here it does. Unreachable in production today (one bean), but this is the seam
      every Epic 2–5 timing story uses, and `ControllableClockTest` already builds two clocks in one
      method. (b) `shared.model` and `shared.strategy` now import each other — the codebase's first
      package cycle, in the two packages the story calls the template for four more services, and no
      ArchUnit rule checks cycles. Note both review layers cited AD-8 and AD-9 for (b) and **both
      citations are wrong**: AD-8 constrains `model` only to "imports nothing framework-flavoured"
      (`ARCHITECTURE-SPINE.md:128`) and AD-9's ordering is by feature, where both packages are
      `shared`. What is real is AD-9's stated purpose, "Prevents: cyclic packages". Options: move the
      read-back onto the strategy as `clock.hasReached(deadline)` (kills the cycle and the mismatch
      together, at the cost of a third interface method); or give `Deadline` an origin identity; or
      accept both and record why.
- [x] [Review][Decision] **The zone-read prohibition this story writes into `project-context.md` is
      enforced by nothing** — `rules/ArchitectureRulesTest.java:79-110`. *Verified by planting:*
      `ZoneId.systemDefault()` and `TimeZone.getDefault()` in `src/main` leave the whole suite green.
      Dev Notes → "UTC, and the three places it slips" says "Nothing may read it", and this diff makes
      that binding in `project-context.md` — while the story's own argument is that prose is the
      weaker mechanism ("this story is where it either becomes structural or stays a habit"). The
      shape of the fix is unambiguous (two map entries plus a fixture read); whether the UTC clause is
      PUB-2's to enforce, or belongs with the first story that formats a timestamp, is a scope call.
      Slip 1 has the same status: the `now()` factories are banned but `LocalDateTime`/`LocalDate` as
      *types* in a signature or DTO are not.

### Patch

- [x] [Review][Patch] **The SQL scan misses Postgres clock reads that carry no parenthesis**
      [`rules/DatabaseNeverReadsTimeTest.java`] — *verified by planting:* a migration containing
      `default 'now'::timestamptz`, `default localtime`, `default timeofday()` and `default
      'today'::date` passes the scan. The implementation matches the Dev Notes' eight-pattern table
      exactly, so this is a hole in the table rather than a deviation from it — but AC1 states the
      named functions are "examples, not the closed set" and the requirement is that no time read
      escapes the `Clock`. `DEFAULT 'now'::timestamptz` is a plausible way to write the exact column
      default this story exists to ban, and Story 1.3 writes the first table next. Add the special
      date/time input literals (`'now'`, `'today'`, `'yesterday'`, `'allballs'`), bare `localtime`,
      and `timeofday(`.
- [x] [Review][Patch] **`new SystemClock()` in production reads the host clock and passes both
      mechanisms** [`rules/ArchitectureRulesTest.java:72`] — *verified by planting:*
      `new SystemClock().wallClockNow()` in `MatchingServiceApplication` leaves the suite green. The
      exemption is on the class that *does* the reading, so any class constructing `SystemClock`
      directly inherits it, and the read is invisible to every test that would advance a clock. The
      story is titled "Time is injectable and never read directly"; nothing makes it *only*
      injectable. Closing rule, in the class that already holds the exemption:
      `noClasses().that().resideOutsideOfPackage("..config..").should().dependOnClassesThat().areAssignableTo(SystemClock.class)`,
      with a fixture so it is proven like the other one.
- [x] [Review][Patch] **Most of the banned table is unproven, and the fixture's Javadoc claims the
      opposite** [`rules/fixtures/ReadsTimeDirectly.java:19-47`,
      `rules/TimeIsReadOnlyThroughTheClockRuleTest.java:27-35`] — *verified by planting:* renaming
      `"OffsetTime"` to `"OffsetTimes"` and `"systemDefaultZone"` to `"systemDefaultZoneX"` in the
      table leaves every test in the suite green. Seven of ~20 owner/member pairs are exercised;
      unproven are `LocalDate`, `LocalTime`, `OffsetDateTime`, `OffsetTime`, `ZonedDateTime`, `Year`,
      `YearMonth`, `MonthDay` and six of the seven `java.time.Clock` factories. The fixture's Javadoc
      says "One read per banned owner, so a typo in the rule's own table is caught here rather than
      shipping as a hole in the rule" — false as written, which `AGENTS.md` singles out ("a wrong one
      outlives wrong code"), and the Completion Note "proves the rule rejects every banned read"
      inherits the overstatement. AC1's own argument — a rule that has never evaluated a violation
      cannot be distinguished from one that cannot fire — applies per entry. Cheapest durable fix:
      drive the fixture's expectations off `BANNED_TIME_READS` so the table cannot outgrow its proof.
- [x] [Review][Patch] **`ControllableClock.advance()` accepts a negative `Duration` and rewinds the
      monotonic source** [`shared/strategy/ControllableClock.java:40-43`] — undocumented and
      untested; `monotonicReadingsNeverDecrease` only steps `ZERO`, `1ns` and `10s`, so nothing
      notices. `shiftWallClock` documents negatives as valid, so the asymmetry invites exactly this
      mistake, and `advance(target.minus(elapsed))` going negative is an ordinary way to reach it.
      Test-only blast radius, but it silently destroys the one invariant the type exists to provide,
      in the double every future timing test calls. One `if (duration.isNegative()) throw new
      IllegalArgumentException(...)`, plus a test.
- [x] [Review][Patch] **`readingsAreUtcRegardlessOfTheDefaultZone` cannot fail**
      [`shared/strategy/ControllableClockTest.java:88-115`] — `DEFAULT_START` is a `static final`
      resolved at class load, before the test moves the default zone, and `Instant.plus(Duration)`
      never consults a zone, so all three assertions hold by construction. Task 6 did ask for the
      test and it is a real canary against a future `LocalDateTime` rewrite, so it is not worthless —
      but it does not support the Completion Note "UTC is asserted, not assumed". Second defect in the
      same test: `TimeZone.getTimeZone(unknownId)` silently returns GMT, so on a JDK with trimmed
      tzdata both "zones" are GMT and it proves nothing. Add the guard `DeadlineTest` already uses for
      `shiftWallClock` — assert the default zone actually changed — and soften the Completion Note.
- [x] [Review][Patch] **`deferred-work.md`'s revisit trigger has fired and the file was not updated**
      [`_bmad-output/implementation-artifacts/deferred-work.md:22`] — "Revisit when the packages
      arrive (Stories 1.2 / 1.3)". Story 1.2 has arrived, created `shared` and `config`, and gave
      `modelDependsOnNothingFrameworkFlavoured` its first real class (`Deadline`) — the Completion
      Notes say so. The file is not in this diff, so the next reader sees a half-fired trigger. Record
      that 1.2 passed, what it did and did not cover, and that 1.3 is now the target.
- [x] [Review][Patch] **The scan's exemption is a bare filename, and its comment claims parity with
      the ArchUnit exemption** [`rules/DatabaseNeverReadsTimeTest.java`] — `Set.of("SystemClock.java")`
      matches that name in any package, whereas the ArchUnit rule keys on the fully-qualified class.
      A future second `SystemClock.java` would be skipped by the text scan and still caught by
      ArchUnit; the comment tells the next reader the two are equivalent. Match on a path relative to
      the scan root, or fix the comment.
- [x] [Review][Patch] **`new GregorianCalendar()` reads the clock and is not caught**
      [`rules/ArchitectureRulesTest.java:48`] — *verified by planting.* `Calendar.getInstance()` is
      banned but the concrete subclass's no-arg constructor is not, because
      `READ_THE_CLOCK_WHEN_CONSTRUCTED_WITH_NO_ARGUMENTS` holds only `java.util.Date` and the
      constructor branch returns before reaching the method table. One entry. The `java.time.chrono`
      `now()` factories (`JapaneseDate`, `HijrahDate`, `MinguoDate`, `ThaiBuddhistDate`) are the same
      shape of hole — **added, then removed again at the repo owner's decision as speculative
      coverage (YAGNI). They are legal in production; only `GregorianCalendar` was kept.**
- [x] [Review][Patch] **`hasExpired` depends on an unwritten contract that `deadlineIn(ZERO)` is
      "now"** [`shared/model/Deadline.java:23`, `shared/strategy/Clock.java:29-36`] — a future `Clock`
      that rounds deadlines or adds a minimum lead time would satisfy the published interface and
      break `hasExpired` silently. One sentence on `deadlineIn`'s contract.
- [x] [Review][Patch] **`sprint-status.yaml`'s header comment contradicts its own data**
      [`_bmad-output/implementation-artifacts/sprint-status.yaml:2`] — the comment says
      `PUB-2 → ready-for-dev`; `development_status` says `review`. The comment is what a human reads
      first.
- [x] [Review][Patch] **The File List omits a file this change modifies** — the diff edits
      `PUB-1-containerized-service-proven-against-the-real-stack.md` (status to `done`, two Change Log
      rows) and PUB-2's File List does not mention it. Everything else in the list matches the diff
      exactly.

### Deferred

- [x] [Review][Defer] **`make test` cannot be green in this sandbox, so `pre-push` — "the only gate" —
      cannot run here** [`services/matching-service/src/integrationTest/java/com/puber/matching/HealthReportsDownPromptlyIntegrationTest.java`]
      — deferred, pre-existing. Reproduced: the TEST-NET-1 precondition fails because this sandbox's
      egress accepts connections to `192.0.2.0/24`. The file was last touched by `41f2540 PUB-1`, the
      Debug Log discloses it honestly, and PUB-1's change log records the laptop run that confirms it
      is the intended silent no-op there. Consequence to keep in view rather than fix here: Task 9's
      "`make build` green from clean, then `make test`" and "both hooks still block" are ticked
      without evidence in the record, and `make static-analysis SERVICE=matching-service` has no
      record entry at all — structurally true (`staticAnalysis` is `spotlessCheck` only), but not
      shown to have been run.
- [x] [Review][Defer] **`ControllableClock` has no memory barrier on either field**
      [`services/matching-service/src/test/java/com/puber/matching/shared/strategy/ControllableClock.java:23-24`]
      — deferred, no caller yet. Plain mutable `Instant` and `long`, no `volatile`, no
      synchronization. Unreachable today: the suite is sequential (`maxParallelForks = 1`, JUnit
      parallelism off) and nothing under test spawns a thread. From Epic 2 the code under test does,
      and then a worker thread may observe a pre-`advance()` reading indefinitely — a flake generator
      in a codebase whose AD-56 calls a flaky concurrency test "the worst possible failure here".
      Belongs with the first story that hands the double across a thread.
- [x] [Review][Defer] **Both enforcement mechanisms are per-service test code and service #2 would
      ship with neither** [`services/matching-service/src/test/java/com/puber/matching/rules/ArchitectureRulesTest.java`]
      — deferred, by design. Dev Notes accept the duplication explicitly ("ArchUnit rules are test
      code and are not shared… a shared library is not"), and this rule was deliberately written in
      the service four more are copied from. What is missing is not a mechanism but a reminder: no
      action item exists, and with no CI server nothing anywhere goes red if the copy is forgotten.
      Belongs with the story that creates the second service.

## Dev Notes

### Authority chain — resolve conflicts in this order

1. **`ARCHITECTURE-SPINE.md`** governs every technical decision.
2. **`SPEC.md`** + companions — the preservation-validated contract.
3. **PRD** — product shape.
4. **`project-context.md`** — project rules, loaded by every workflow. Binding.
5. **`AGENTS.md`** — house coding style; binding where it does not contradict the above.

Where this story reads a requirement more narrowly or more widely than an AC's wording, the reading is
written down under that AC rather than applied silently.

### Why this story exists at all, in one paragraph

Every bounded window in the system — offer timeout 10 s, `NO_DRIVER` budget 60 s, idle staleness 15 s,
`MATCHED` 90 s, `IN_PROGRESS` 10 min, session expiry 1 h (AD-46) — has to be provable in a test that
finishes in milliseconds, or the suite that proves them gets written with `sleep` and becomes flaky,
and AD-56 names a flaky concurrency test *"the worst possible failure here"* because it is
indistinguishable from a real race. The clock is cheap now and expensive to retrofit: retrofitting
means finding every time read in five services after the fact. That is why `roadmap.md` schedules it
in week one ahead of its own phase.

### The requirement most likely to be dropped

The implementation-readiness report flags exactly this story:

> **NFR-9's dual-clock rule is the subtlest requirement in the document** — monotonic for durations,
> wall-clock for recorded facts, with FR-51 as the case that needs both. It is stated once, in an NFR,
> and it constrains nearly every timing-related story. High risk of being dropped into a single "make
> time testable" story that misses the monotonic-vs-wall-clock split.
> [`implementation-readiness-report-2026-08-16.md`, risk 5]

A `Clock` with one `now()` returning an `Instant` satisfies AC2 and fails AC3 and AC4 — and it fails
them *invisibly*, because everything is green until an NTP correction lands mid-offer in production.
**The two semantics are two accessors returning two types.** That is the whole design.

### What the interface gets today, and what it does not

```java
public interface Clock {
    /**
     * The current wall-clock instant, UTC. For facts that are recorded, persisted, or compared
     * across processes or services.
     *
     * <p>Never for measuring a duration or evaluating a deadline: wall clock moves when the host
     * is corrected. Subtracting two of these readings is the NFR-9 violation this interface exists
     * to prevent -- the monotonic path is {@link #deadlineIn}, and an elapsed-time accessor is
     * added here, monotonic, when the first caller needs one. The name says wall clock so the
     * call site does too.
     */
    Instant wallClockNow();

    /** A monotonic in-process deadline. Unaffected by wall-clock corrections. */
    Deadline deadlineIn(Duration duration);
}
```

**`wallClockNow()`, not `now()` — the name is part of the mechanism, and it was considered and kept.**
`now()` reads better and is the idiomatic choice, and with only one instant accessor there is nothing
today to confuse it with. It was rejected because the risk is in the future: the readiness report names
the monotonic/wall-clock split as *"the subtlest requirement in the document"* with a high risk of
being collapsed, and once a monotonic elapsed accessor exists,
`Duration.between(clock.now(), clock.now())` reads perfectly natural and is wrong. Spelled out,
`Duration.between(clock.wallClockNow(), ...)` reads wrong **at the call site**, where the mistake is
made — not in a Javadoc nobody opens. The verbosity is the point. **Do not shorten it, and if a
monotonic elapsed accessor is added, it does not get a `now()`-shaped name either.**

Two accessors, and **no elapsed-duration accessor**, which is a deliberate reading of AC3 rather than
an omission. AC3's duration clause is conditional on production code that measures a duration; there
is none in this story, and `project-context.md`'s YAGNI rule is explicit — *name the failure it
prevents, then check that the failure is real; a rationale is not evidence*. PUB-1 shipped three
guards that each guarded nothing and were removed. So: the first caller that needs to measure an
elapsed duration adds the accessor, and Task 8 records the constraint that binds it — **it must be a
monotonic accessor, never arithmetic over two `wallClockNow()` readings.**

`Deadline` is what keeps the split honest. `deadlineIn(...)` returning an `Instant` would leave the
caller comparing it against `wallClockNow()`, which is wall-clock arithmetic wearing a monotonic name.
A distinct type means the wrong thing does not type-check.

Do not foreclose AC3's third case: a durable deadline crossing processes (`next_attempt_at`, AD-46's
cooldown) is stored **wall clock** and read back through `wallClockNow()`. Nothing to build here —
just do not make `Deadline` the only way to express a deadline in the interface's contract.

### UTC, and the three places it slips

**Every wall-clock value in this system is UTC.** The convention says `TIMESTAMPTZ`, UTC
[`ARCHITECTURE-SPINE.md#consistency-conventions`], and it is repeated in `project-context.md`. It is
short enough to read as a database note and it is not one — it binds the whole time path, and this
story is where it either becomes structural or stays a habit.

`wallClockNow()` returning `Instant` gets most of it for free: an `Instant` is a point on the UTC
timeline with no zone to be wrong about. The three places it still slips:

1. **A local type at a boundary.** `LocalDateTime` and `LocalDate` carry no zone, so converting either
   one reads the JVM default and silently means a different moment on a laptop in CET than in the
   container. Both are already on AC1's banned `now()` list; the wider rule is that **no local
   date-time type appears in a signature, a column mapping, or a DTO** — `Instant` in, `Instant` out,
   `TIMESTAMPTZ` at rest. This is also why DST is a non-event for a correctly built deadline: DST acts
   on zoned local time, and there is none in the path.
2. **The JVM or container default zone.** Nothing may read it — no `ZoneId.systemDefault()`, no
   `TimeZone.getDefault()`, no `Instant.atZone(systemDefault())`. Do **not** "fix" this by setting
   `TZ=UTC` or `-Duser.timezone=UTC` in the Dockerfile or Compose file: that makes correctness depend
   on an environment variable a future manifest can drop, and it hides the code that should not have
   been reading the zone at all. Code that never asks the question cannot be given a wrong answer.
3. **Formatting for a human.** Rendering a UTC instant in a local zone is legitimate — for a log line,
   an operator dashboard, a rider-facing response. It belongs at the **presentation edge only**, with
   the zone passed in explicitly, and the stored and transported value stays UTC. Nothing in this story
   formats anything; the rule is recorded so the first story that does starts from the right side.

Postgres carries its half already: `TIMESTAMPTZ` stores a UTC instant and the JDBC driver maps
`Instant` to it without a zone conversion. The trap is `TIMESTAMP` *without* time zone, which does not
— Story 1.3 writes the first table, so the column type is a live decision there, not a settled one.

### `System.nanoTime()` wraps, and the fix is one line

`System.nanoTime()` returns nanoseconds from an arbitrary origin; it can be negative, and it wraps
after ~292 years of uptime. Comparing two readings with `<` is therefore wrong in the general case.
The JDK's own documented idiom is to compare **differences**:

```java
// Correct:  nowNanos - expiresAtNanos >= 0
// Wrong:    nowNanos >= expiresAtNanos
```

This is the kind of fact `AGENTS.md` says to check rather than assert — it is in the `System.nanoTime`
Javadoc.

### The banned set

**ArchUnit, over Java call sites in `src/main`:**

| Owner | Members |
| --- | --- |
| `java.lang.System` | `currentTimeMillis()`, `nanoTime()` |
| `java.time.Instant` | `now(..)` (all overloads) |
| `java.time.LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`, `ZonedDateTime`, `Year`, `YearMonth`, `MonthDay` | `now(..)` |
| `java.time.Clock` | `systemUTC()`, `systemDefaultZone()`, `system(..)`, `tick*(..)` |
| `java.util.Date` | no-arg constructor |
| `java.util.Calendar` | `getInstance(..)` |

`System.nanoTime()` is on the list for the same reason as the rest: unexempted, it is the obvious way
to hand-roll a deadline outside the `Clock` and lose the one seam tests advance through.

Out of scope of the rule, deliberately: a library reading the clock **inside itself** (Micrometer
timers, the JDBC driver, Spring's scheduler). The rule governs calls **our classes make**, which is
what it can actually see and what it can actually hold.

**Source scan, over `src/main/resources/**/*.sql` and `src/main/java/**/*.java` text:**
`now(`, `current_timestamp`, `current_date`, `current_time`, `localtimestamp`, `clock_timestamp(`,
`transaction_timestamp(`, `statement_timestamp(` — case-insensitive.

**This bans `DEFAULT now()` in a migration, and that is intended.** A column default is a time read
outside the `Clock`, in the one place no test can advance — Story 1.3's `fare_rules` and every table
after it supply timestamps as **bind parameters from the `Clock`**. AD-58 states the same rule for the
settlement worker's claim predicates: *"Every `now()` in a claim or backoff predicate is a bind
parameter from the AD-10 `Clock` strategy, never SQL `now()` — otherwise the ceiling and AD-46's
cooldown can only be tested by waiting."* Anchor the patterns on `now(` rather than `now` so English
prose in a `.sql` comment does not trip the scan.

### Two traps in the naming

- **`java.time.Clock` exists**, and our interface is also called `Clock` — the name AD-10 gives it, so
  keep it. Consequence: in any file touching both, one of them must be fully qualified, and an IDE's
  auto-import will reach for `java.time.Clock` first. A production class that imports `java.time.Clock`
  by accident is not caught by the rule unless it *calls* a banned factory, so read your imports.
- **`Deadline` is not a service.** `AGENTS.md` requires service names to express an action with a
  single `execute(...)`; that governs `service` classes. `Clock` is a Strategy named by AD-10 and
  `Deadline` is an immutable value type — both are outside that rule, and both are inside the
  immutable-domain-object rule: `final` fields, no setters, a `record` is ideal.

### Where each file goes, and why

`project-context.md` fixes the composition — **feature outside, layer inside** — and the layer names
come from AD-7 (`controller` / `service` / `repository` / `model` / `strategy` / `config`).

```text
services/matching-service/src/
  main/java/com/puber/matching/
    config/ClockConfiguration.java              @Bean Clock — the framework seam
    shared/strategy/Clock.java                  the interface (AD-10)
    shared/strategy/SystemClock.java            the only class that reads a system time source
    shared/model/Deadline.java                  immutable value over a monotonic reading
  test/java/com/puber/matching/
    rules/ArchitectureRulesTest.java            + timeIsReadOnlyThroughTheClock
    rules/fixtures/                             the deliberate violation the rule must reject
    shared/strategy/ControllableClock.java      the test double
  integrationTest/java/com/puber/matching/
    ClockWiringIntegrationTest.java             the running service's Clock is the real one
```

- **`shared`, not a feature package.** `project-context.md`'s membership test: *does the type encode a
  convention?* Time semantics — monotonic for deadlines, wall clock for facts — is exactly a
  convention, in the same class as Money's minor units and Coordinates' axis order. Every feature will
  read time; none owns it.
- **`config/` and `shared/` are created here and were deliberately absent before.**
  `project-context.md`: *"Create layer packages only as they gain content… This is why `config/` and
  `shared/` do not exist yet — Story 1.2's `Clock` is the first inhabitant."* Create these two.
  **Do not scaffold `fare/`, `ride/`, `dispatch/`, `quote/`, `controller/`, `service/` or
  `repository/`** — an empty layer package is a violation, not preparation. PUB-1's review resolved
  this exact conflict in favour of "never scaffolded empty" and amended AC6 to match.
- **`SystemClock` is a plain class; the `@Bean` lives in `config`.** Annotating it `@Component` would
  put Spring inside `shared` and put the wiring somewhere `config` cannot see. The
  `serviceDependsOnStrategyInterfacesOnly` rule scopes to `..service..`, so `config` referencing the
  concrete implementation is correct and is how AD-8's inversion is actually achieved.

### The two existing files this story modifies

Everything else is new. Read both before editing — the workflow's most expensive failure is editing a
file whose current shape you inferred.

**`services/matching-service/src/test/java/com/puber/matching/rules/ArchitectureRulesTest.java`**

- *Today:* `@AnalyzeClasses(packages = "com.puber.matching", importOptions = DoNotIncludeTests.class)`
  over six `static final ArchRule` fields — `modelDependsOnNothingFrameworkFlavoured`,
  `serviceDependsOnStrategyInterfacesOnly`, `nothingDependsOnController`, `noPackageIsNamedEntity`,
  `sharedDependsOnNoFeaturePackage`, `featureDependenciesRunOneWay`. Five carry
  `allowEmptyShould(true)`; the layered rule carries `withOptionalLayers(true)`. A `MATCHING` constant
  holds the package prefix. The class Javadoc explains why the rules were written before the code.
- *What changes:* one rule added, and the class Javadoc's claim that *"only `config` and `shared` will
  exist before Story 1.2"* becomes past tense — they exist now.
- *What must be preserved:* the six rules and their `allowEmptyShould`/`withOptionalLayers` flags. They
  look like workarounds and are not — removing them turns four vacuous-but-passing rules into build
  failures over packages this story is forbidden to create. The `@AnalyzeClasses` import option must
  stay: without it the rule scans test code and the fixture of Task 3 fails the production rule.

**`project-context.md`** (repository root)

- *Today:* the binding project rules, loaded by every BMad workflow. Sections most relevant here:
  YAGNI, "Package layout: feature outside, layer inside" (which already names *"Story 1.2's `Clock`"*
  as `config/`'s first inhabitant), "What belongs in `shared`", "Static analysis", "Real datastores
  only", and the Boot 4.1 / Java 25 list.
- *What changes:* Task 8's two additions only — the durations-never-from-wall-clock-arithmetic
  constraint, and the `java.time.Clock` collision.
- *What must be preserved:* every existing rule, and the file's own framing — *only what is
  non-obvious from reading the code belongs here*. It is not a design document for this story; two
  sentences each. The story file is where the reasoning lives.

### The test double, and one classpath trap

`ControllableClock` holds two independent readings — a wall-clock `Instant` and a monotonic `long` —
and exposes:

- `advance(Duration)` — both move forward together. Ordinary time passing.
- `shiftWallClock(Duration)` — **only** wall clock moves, forwards or backwards. This is the NTP
  correction and the DST-style jump, and it is the whole of AC4's test.

Neither reads real time, which is what makes AC5 true by construction rather than by luck.

**The trap:** `src/test/java` classes are **not** on the `integrationTest` compile classpath.
`build.gradle` extends the integration suite's *dependency* configurations from `testImplementation`,
and the suite adds `implementation project()` — main output only. Classes do not cross. That is fine
today: nothing in `integrationTest` needs `ControllableClock` (Task 7 asserts the *real* clock is
wired). When a later story does need it, the fix is to put `sourceSets.test.output` on the integration
suite's classpath — **not** to adopt the `java-test-fixtures` plugin for one class, and **not** to
duplicate the double.

**Add no dependency.** ArchUnit `1.5.0` (`archunit-junit6`) is already declared. In particular do not
add **Awaitility**: it is the ecosystem's reflex for timing tests and it waits in **real** time, which
is the thing this story exists to make unnecessary. Reaching for it later is an architecture decision,
the way `project-context.md` frames Testcontainers, not a tooling choice.

### The deferred vacuity finding, scoped

PUB-1 deferred: *all six ArchUnit rules backing AC7 are currently vacuous… a fixture with an inverted
assertion would prove they can fire* — revisit "when the packages arrive (Stories 1.2/1.3)"
[`deferred-work.md`]. This story creates `shared` and `config` only, so:

- **In scope:** the new time rule is proven capable of failing (Task 3), because AC1 is not satisfied
  by a rule that has never rejected anything.
- **Out of scope:** the six existing rules. `modelDependsOnNothingFrameworkFlavoured` acquires its
  first class here (`shared/model/Deadline.java`) but the others still govern packages that do not
  exist. They stay recorded in `deferred-work.md`; the natural landing is Story 1.3, when `fare/`
  arrives with `model`, `service` and `strategy` content together.
- Also still open from that item: `modelDependsOnNothingFrameworkFlavoured` enumerates nine banned
  packages rather than expressing "nothing framework-flavoured". Not this story's to fix — but do not
  copy the enumerate-a-blocklist shape into the new rule where a predicate over owner-and-member pairs
  says what is meant.

### One service today, five later

AC1 says "production code across every service"; only `matching-service` exists. ArchUnit rules are
**test code and are not shared** (`project-context.md`, AD-52 covers `contracts/` and analyzer config,
not test code), so each later service carries its own copy of this rule. Duplicated code across
services is accepted by design — *"a shared library is not"* — and it is precisely why the rule is
written now, in the service that four more are copied from. Write it so it copies cleanly: no
`matching`-specific package names in the predicate beyond the exemption.

### Testing requirements

- **JUnit 6** (Jupiter 6.0.3, managed by Boot 4.1.0), **not JUnit 5**. Most JUnit 5 guidance holds;
  verify rather than assume, and reach for JUnit 6 docs when something behaves unexpectedly.
- **Unit tests** — `*Test` in `src/test/java`, no Spring context, no database. **Integration tests** —
  `*IntegrationTest` in `src/integrationTest/java`, real Postgres from the Compose stack. *The
  directory decides which suite a test belongs to, not the name* (`AGENTS.md`).
- **Never sleep, and never poll in real time.** This is a Testing convention, an AC in this story, and
  the reason the story exists.
- **Tests ship with the feature.** *No story exists solely to add tests, and none may be added later*
  [`epics/overview.md#testing-policy`]. There is no later story in which to prove AC4.
- **Counters are asserted as deltas, never absolutes**, and no service gets a reset hook for a test's
  convenience (Metrics convention). Nothing here adds a counter; the habit is stated because
  `Deadline` is the type later stories will count expiries with.
- **Where the new checks run:** `staticAnalysis` is `spotlessCheck` only, so ArchUnit runs under
  `./gradlew test` — that is `pre-push`, and it is deliberate. `pre-commit` stays tests-free; if that
  feels wrong, read `project-context.md` → "Hooks" before changing it.

### Definition of done beyond the ACs

A story must leave the system working end to end, not merely satisfy its stated ACs. Here that means:
a fresh clone on a machine with **no JDK** runs `make build` and `make test` green; the stack comes up
and `/actuator/health` reports UP with the `Clock` bean wired; the new rule **fails the build** when a
production class reads time directly — verify by temporarily adding a call and watching `make test-unit`
go red, then removing it; and both hooks still block.

### Previous story intelligence — PUB-1

Read [`PUB-1-containerized-service-proven-against-the-real-stack.md`] before starting. The five things
that will actually bite:

1. **Boot 4.1 / Java 25 contradict most published guidance.** Split test starters
   (`spring-boot-starter-<capability>-test`), `-webmvc` not `-web`, Jackson **3** (`tools.jackson.*`),
   `TestRestTemplate` in `org.springframework.boot.resttestclient` needing
   `@AutoConfigureTestRestTemplate`, metrics exporters off inside `@SpringBootTest` without
   `@AutoConfigureMetrics`. The full list is in `project-context.md`; it was assembled the hard way.
2. **Spotless is `googleJavaFormat().aosp()` — 4 spaces, never tabs, and it fails the build.** Run
   `make format` before committing, then `git add`: **`pre-commit` analyses the index**, so fixing a
   file without staging it changes nothing.
3. **A test that cannot fail is the review finding that recurs.** PUB-1's review killed three of them:
   a health assertion that passed while health was DOWN, an AC5 assertion that measured a first start
   while claiming a second, and an unreachable-address test whose address answered in 0.8 ms. Every
   test in this story should have an obvious answer to *"what change makes this go red?"* — for the
   AC4 test, deleting the monotonic source and using wall clock instead.
4. **YAGNI is enforced here.** Three guards written during PUB-1 (`ALLOW_ROOT`,
   `forbidSubstituteDatastores`, `.NOTPARALLEL`) each had a convincing comment and each guarded
   nothing; all three were removed. Do not add a second `Clock` implementation, a `@Profile`, a
   configuration property or an interface for a single implementation.
5. **Two known holes are filed forward, not for you to fix** — `sprint-status.yaml` action items AI-1
   (JDBC `socketTimeout` unset → epic 4) and AI-2 (readiness group excludes `db` → epic 7).

**One environment note.** `HealthReportsDownPromptlyIntegrationTest` fails its TEST-NET-1 precondition
in a sandbox whose egress *accepts* connections to `192.0.2.0/24`; on a laptop where that range is
dropped it is a silent no-op. If it fails and you have not touched it, read the AC4 finding in PUB-1's
Review Findings before assuming a regression.

### Git intelligence

`git log` is one implementation commit deep: `41f2540 PUB-1` (27 files) on top of planning commits.
Baseline for this story is that commit — recorded in this file's frontmatter. Current branch `main`;
follow the project's branching rules for the working branch. There is **no CI server**; `pre-push` is
the only gate, before the PR to `dev`.

**A trap in history:** the deleted `docs/` directory (`puber.md`, `tickets/pb-*.md`) was a superseded
planning attempt, explicitly non-authoritative and stale on the driver enum, the payment flow, the
ride state machine and the database topology. `pb-1.2.md` will look like it addresses this story. It
does not. Ignore anything a search surfaces from it.

### Project structure notes

- **Aligned:** the layout above is `ARCHITECTURE-SPINE.md#source-tree` plus `project-context.md`'s
  feature-outside/layer-inside composition, verbatim. No deviation.
- **Watch for:** `config/` and `shared/` being created as a *set* of packages rather than the two the
  story earns. Two packages, three main classes, one bean.

### References

- [Source: `_bmad-output/planning-artifacts/epics/epic-1-foundations-fare-quote.md#story-12-time-is-injectable-and-never-read-directly`] — the five AC blocks, verbatim
- [Source: `…/epic-1-foundations-fare-quote.md#story-11…`] — the analyzer decision table: *"NFR-9 / AD-58: no `Instant.now()`, `System.currentTimeMillis()`, or SQL `now()` outside the `Clock` — ArchUnit, a 'no class calls this method' rule — lands in Story 1.2"*
- [Source: `…/ARCHITECTURE-SPINE.md#ad-10-strategy-interfaces-only-where-implementations-vary-never-over-postgres`] — `Clock` is one of the five named Strategy seams
- [Source: `…/ARCHITECTURE-SPINE.md#ad-7-layered-packaging-with-strategy-for-varying-behaviour`] — the layer names; `model` never `entity`
- [Source: `…/ARCHITECTURE-SPINE.md#ad-8-one-way-dependency-inside-a-service`] — `service` depends on Strategy interfaces, never implementations
- [Source: `…/ARCHITECTURE-SPINE.md#ad-9-matching-service-alone-splits-by-feature`] — `shared` is the bottom of `shared ← fare ← ride ← dispatch ← quote`
- [Source: `…/ARCHITECTURE-SPINE.md#ad-46-time-constants-are-one-tuned-set-with-fixed-ordering`] — the windows this clock exists to make testable, and the wall-clock/monotonic case split
- [Source: `…/ARCHITECTURE-SPINE.md#ad-56-tests-run-against-the-real-stack-reset-between-test-classes`] — sequential, containerized, real datastores; why a flake is the worst failure
- [Source: `…/ARCHITECTURE-SPINE.md#ad-57-solid-is-the-design-vocabulary-and-is-made-testable-rather-than-cited`] — Liskov: no caller branches on which Strategy is active
- [Source: `…/ARCHITECTURE-SPINE.md#ad-58-settlement-is-driven-by-a-durable-claim-loop-worker…`] — *every `now()` in a claim or backoff predicate is a bind parameter from the `Clock`, never SQL `now()`*
- [Source: `…/ARCHITECTURE-SPINE.md#consistency-conventions`] — Timestamps: wall clock for facts, monotonic for in-process deadlines, wall clock again for a durable deadline
- [Source: `_bmad-output/specs/spec-puber/SPEC.md#cap-40`] — reproducible runs under a controlled clock, tagged `enabler`
- [Source: `_bmad-output/specs/spec-puber/roadmap.md#week-one-before-any-phase`] — why the clock lands before its phase, and why the Simulator does not
- [Source: `_bmad-output/planning-artifacts/prds/prd-puber-2026-08-02/prd.md#4-non-functional-requirements`] — NFR-9 in full, including the FR-51 case that needs both semantics
- [Source: `_bmad-output/planning-artifacts/implementation-readiness-report-2026-08-16.md`] — risk 5: the dual-clock split is the requirement most likely to be dropped
- [Source: `_bmad-output/planning-artifacts/epics/overview.md#testing-policy-tests-ship-with-the-feature-that-needs-them`] — no test-only stories
- [Source: `_bmad-output/implementation-artifacts/PUB-1-containerized-service-proven-against-the-real-stack.md`] — Boot 4.1 traps, the review findings, the build and hook wiring
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — the vacuous-ArchUnit-rules item and its 1.2/1.3 revisit note
- [Source: `project-context.md`] — YAGNI, package layout, `shared` membership test, analyzer set, hook policy, Boot 4.1 / Java 25 notes
- [Source: `AGENTS.md`] — SOLID, immutable domain objects, no magic numbers, comment policy, test naming and placement

## Dev Agent Record

### Agent Model Used

claude-opus-5 (Claude Code, `bmad-dev-story`)

### Debug Log References

- Red before green, twice: `make test-unit` failed to compile `ControllableClock`/`DeadlineTest`
  against a `Clock` that did not exist yet, and again on `TimeIsReadOnlyThroughTheClockRuleTest`
  against a rule field that did not exist yet. Both then passed once the code was written.
- `exemptsTheOneClassAllowedToReadTime` first failed with ArchUnit's *"failed to check any classes"*:
  importing `SystemClock` alone leaves the rule's `that()` clause empty, which proves nothing about
  the exemption. Reformulated to import the exempt class and the violator together and assert the
  violation names `ReadsTimeDirectly.java` and not `SystemClock.java`.
- AC1's build-failure claim was verified by planting `Instant.now()` in
  `MatchingServiceApplication`, running `make test-unit`, and watching **both** mechanisms fire —
  ArchUnit (`... calls method <java.time.Instant.now()> in (MatchingServiceApplication.java:11)`) and
  the source scan (`src/main/java/.../MatchingServiceApplication.java:11 matches \bnow\s*\(`). The
  plant was then reverted; `git diff` on that file is empty.
- `HealthReportsDownPromptlyIntegrationTest` fails its own `@BeforeAll` precondition in this
  sandbox: `192.0.2.1:5432 accepted a TCP connection after 3ms`. Pre-existing and environmental —
  the file is untouched by this story (`git log -1` on it returns `41f2540 PUB-1`) and PUB-1's
  review plus this story's Dev Notes both predict it. Every other test in both suites passes.
  Consequence to know about: `pre-push` runs `make test`, so a push from an environment whose egress
  answers TEST-NET-1 is blocked by that pre-existing test, not by anything here.

### Completion Notes List

- **Two accessors, two return types.** `Clock.wallClockNow()` returns an `Instant` for recorded
  facts; `Clock.deadlineIn(Duration)` returns a `Deadline` over a monotonic reading. Subtracting two
  wall-clock readings to get a duration does not type-check into a `Deadline`, which is what makes
  the NFR-9 split structural rather than remembered. No elapsed-duration accessor was added: no
  caller in this story measures one (YAGNI), and the constraint binding the first one that does is
  now recorded in `project-context.md`.
- **`Deadline.hasExpired(Clock)` is how a deadline is read back**, rather than a third method on the
  interface. A zero-length deadline *is* the clock's current monotonic reading, so the two accessors
  are sufficient. Comparison is by difference (`reading - deadline >= 0`), never by absolute value,
  and `survivesMonotonicWraparound` proves it with an origin one second before `Long.MAX_VALUE`:
  under an absolute comparison that deadline reads as already expired at t=0.
- **AC1 is enforced by two mechanisms and proven by four tests.** The ArchUnit rule
  `timeIsReadOnlyThroughTheClock` is a predicate over owner-and-member pairs (not a package
  blocklist) covering the full banned set; `DatabaseNeverReadsTimeTest` (named `NoServerSideTimeReadTest` when written) scans `src/main/resources/**`
  `.sql` and `src/main/java/**` `.java` text for server-side time functions. The rule test proves
  the rule rejects every banned read, accepts conversion that reads nothing (`new Date(millis)`,
  `Instant.ofEpochMilli`), exempts `SystemClock`, and does **not** exempt its package — a fixture
  living in `shared.strategy` is the only thing that would notice the exemption being widened.
- **The scanner is guarded against passing for the wrong reason.** Each root asserts a non-zero file
  count before asserting cleanliness, and a third test plants a `default NOW()` migration in a
  `@TempDir` and asserts the report names the file, the line and the pattern.
- **AC4 is proven in two halves.** The logic: a ±1 h wall-clock correction mid-window neither fires
  the deadline early nor stops it firing, with each shift asserted to have actually happened so a
  no-op `shiftWallClock` could not leave the test green. The production path:
  `ClockWiringIntegrationTest` asserts the running context holds exactly one `Clock`, that it is
  `SystemClock`, and that its readings bracket two real `Instant.now()` calls.
- **UTC is asserted, not assumed.** `readingsAreUtcRegardlessOfTheDefaultZone` moves the JVM default
  zone to UTC+14 and UTC-11 around the readings (restored in a `finally`; the suite is sequential by
  `maxParallelForks = 1`) and asserts the instants are unchanged. Nothing in the code reads a
  default zone, and no `TZ`/`-Duser.timezone` was added to any manifest.
- **No test sleeps, polls or waits.** No dependency was added — in particular no Awaitility. The
  three `Instant.now()` readings in test code are brackets around a `SystemClock` call, not waits.
- **Deliberately not built:** no time constant, no scheduler, no migration, no Simulator seeding, no
  second `Clock` implementation, no profile or configuration property, and no feature package. Two
  packages (`config`, `shared`) and three production classes plus one `@Bean`.
- **Not widened:** the six pre-existing ArchUnit rules stay unproven and stay in `deferred-work.md`.
  `modelDependsOnNothingFrameworkFlavoured` now governs its first real class (`Deadline`) but its
  blocklist shape was left alone, and the new rule deliberately does not copy that shape.

### Change Log

| Date | Change |
| --- | --- |
| 2026-08-22 | Review follow-ups, all verified by planting the violation and reverting. `Deadline.hasExpired(Clock)` → `Clock.hasReached(Deadline)`, breaking the `shared.model` ↔ `shared.strategy` cycle. The banned-read table became two predicates — any `java.time` type with a `now` factory, plus `Clock`'s `system*`/`tick*` — so ~24 hand-typed names became 5 and a typo is no longer possible; the legacy `Date`/`Calendar`/`GregorianCalendar`/`TimeZone` types are denied outright by `theLegacyDateApiIsNotUsedAtAll` rather than by listing their clock reads. `readsASystemTimeSource` → `readsTime`. `NoServerSideTimeReadTest` → `DatabaseNeverReadsTimeTest`, with the SQL scan covering the bracket-free clock reads and a dead branch removed. Comments across every Java file cut to what `AGENTS.md` asks for. Build fixes: test tasks never report up-to-date (a cached green ran nothing), `analyzer-config` clears Spotless's stale file copies (a rename crashed `make format`), and `make clean` no longer deletes `infra/.env` (it reached one Docker daemon's volume and another machine's password). `make build` green; `make test` green apart from PUB-1's environmental `HealthReportsDownPromptlyIntegrationTest` precondition. |
| 2026-08-21 | Non-ISO calendars removed from the banned table at the repo owner's decision. The review had added `java.time.chrono`'s `HijrahDate`/`JapaneseDate`/`MinguoDate`/`ThaiBuddhistDate` `now()` factories; they are speculative coverage in a ride-hailing service, which is what the YAGNI rule forbids — *name the failure it prevents, then check that the failure is real*. The four fixture reads that existed only to prove them went with the entries, since the proof derives from the table and they would otherwise be dead code. Consequence accepted: those factories are now legal in production. |
| 2026-08-21 | Code review applied. `Deadline.hasExpired(Clock)` → `Clock.hasReached(Deadline)`, which broke the `shared.model` ↔ `shared.strategy` cycle; `ZoneId.systemDefault`/`TimeZone.getDefault` and `new GregorianCalendar()` added to the banned table; new `theRealClockIsOnlyEverInjected` rule so only `config` may name `SystemClock`; the rule's proof now derives from `BANNED_TIME_READS` itself, so the table cannot outgrow it; SQL scan gained the bracket-free clock reads (`'now'`/`'today'`/… literals, bare `localtime`, `timeofday(`) plus a test that they fire and a test that prose does not; `ControllableClock.advance` rejects a negative duration; the UTC test now asserts the default zone actually moved. Every fix verified by planting the violation, watching it fail, and reverting. |
| 2026-08-21 | Implemented. `Clock` + `Deadline` + `SystemClock` + `ClockConfiguration`; the `timeIsReadOnlyThroughTheClock` ArchUnit rule with fixtures proving it can fail and does not over-fire; `NoServerSideTimeReadTest` for the SQL clause (later renamed `DatabaseNeverReadsTimeTest`); `ControllableClock` and the AC2–AC5 tests; `ClockWiringIntegrationTest`; `project-context.md` updated with the wall-clock-arithmetic constraint, the `java.time.Clock` collision and a sharpened Timestamps rule. Full suite green apart from PUB-1's environmental `HealthReportsDownPromptlyIntegrationTest` precondition. |
| 2026-08-21 | Accessor naming raised and settled: `wallClockNow()` kept over the shorter `now()`, so the wall-clock semantics are stated at the call site rather than only in a Javadoc. Recorded in Dev Notes → "What the interface gets today" so it is not re-litigated as a simplification. The interface Javadoc gained the warning either way. |
| 2026-08-21 | Task 4 reworded: the SQL scan's primary assertion (fail on the first match, naming file/line/pattern) was implicit and is now stated ahead of the zero-files-scanned guard, which is a check on the test rather than the rule. |
| 2026-08-21 | UTC made explicit: added to AC3's reading, Tasks 1/6/8, and a Dev Notes section naming the three ways it slips (local types at a boundary, the JVM/container default zone, presentation-layer formatting). The convention itself is unchanged — it was already binding in the spine and `project-context.md` and was merely unstated here. |
| 2026-08-21 | Story created. Context assembled from epic 1, the spine (AD-7/8/9/10/46/56/57/58 and the Timestamps convention), SPEC CAP-40, the roadmap's week-one placement, NFR-9, the readiness report's risk 5, PUB-1's implementation and review findings, `deferred-work.md`, `project-context.md` and `AGENTS.md`. |

### File List

**New — production (`services/matching-service/src/main/java/`)**

- `com/puber/matching/shared/strategy/Clock.java`
- `com/puber/matching/shared/strategy/SystemClock.java`
- `com/puber/matching/shared/model/Deadline.java`
- `com/puber/matching/config/ClockConfiguration.java`

**New — unit tests (`services/matching-service/src/test/java/`)**

- `com/puber/matching/shared/strategy/ControllableClock.java`
- `com/puber/matching/shared/strategy/ControllableClockTest.java`
- `com/puber/matching/shared/strategy/SystemClockTest.java`
- `com/puber/matching/shared/strategy/NeighbourStrategyThatReadsTimeDirectly.java`
- `com/puber/matching/shared/model/DeadlineTest.java`
- `com/puber/matching/rules/TimeIsReadOnlyThroughTheClockRuleTest.java`
- `com/puber/matching/rules/DatabaseNeverReadsTimeTest.java`
- `com/puber/matching/rules/fixtures/ReadsTimeDirectly.java`
- `com/puber/matching/rules/fixtures/ConvertsTimeWithoutReadingIt.java`
- `com/puber/matching/rules/fixtures/UsesTheLegacyDateApi.java` — added by the review, for the
  legacy-date-API rule

**New — integration test (`services/matching-service/src/integrationTest/java/`)**

- `com/puber/matching/ClockWiringIntegrationTest.java`

**Modified**

- `services/matching-service/src/test/java/com/puber/matching/rules/ArchitectureRulesTest.java`
- `services/matching-service/build.gradle` — test tasks never skipped as up-to-date
- `Makefile` — `clean` no longer deletes `infra/.env`; `analyzer-config` clears the stale
  Spotless copies
- `CLAUDE.md` (new) — how to explain things, and how to prove a claim
- `_bmad/custom/bmad-dev-story.toml`, `_bmad/custom/bmad-code-review.toml` (new) — load
  `AGENTS.md`, and run `make build` + `make test` before a status change
- `project-context.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/PUB-2-time-is-injectable-and-never-read-directly.md`
- `_bmad-output/implementation-artifacts/PUB-1-containerized-service-proven-against-the-real-stack.md`
  — status to `done` plus two Change Log rows; PUB-1's own review closed out on the same day
- `_bmad-output/implementation-artifacts/deferred-work.md` — code review added three deferred items
  and recorded that the vacuous-rules item's Story 1.2 trigger has fired without closing
