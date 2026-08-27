---
baseline_commit: 051f0a212065dcb5428b96aa7940363f25d95300
depends_on: PUB-4
---

# Story 1.5: Every package declares its nullness

Ticket: **PUB-60**
Status: ready-for-dev

**Do this after PUB-4 is `done`** — i.e. after all three of PUB-4-1, PUB-4-2 and PUB-4-3. It then
covers **both** services, `matching-service` and the `rider-service` PUB-4-2 creates. Sequencing was
the repo owner's call on 2026-08-26; going first would have had `rider-service` born annotated instead
of retrofitted, and the difference is a handful of files.

## Story

As an engineer,
I want every package to declare that its types are non-null unless annotated otherwise,
so that "can this be null?" is answered by the signature rather than by reading the implementation,
and so no package can be added later that quietly answers nothing.

### What this story actually does, in plain words

Right now nothing in this codebase says whether a value can be null. There are **zero nulls in
production code today** — 12 classes, not one mention of `null` — so nothing is broken. What is
missing is the *vocabulary*, and the moment it starts mattering is close: `project-context.md` makes
migrations *"additive, **nullable**, no backfill"*, so from Epic 3 every new column arrives nullable
and every field read from one can be null.

Two things get added:

1. **A declaration, per package** — one tiny `package-info.java` saying `@NullMarked`: *in this
   package, nothing is null unless it says so.* Where something genuinely can be null, it is marked
   `@Nullable` at the point it happens, in the signature.
2. **A guard that the declaration cannot rot** — an ArchUnit rule that fails the build when a package
   holds classes but never declared its nullness. Without it, this is a list of files someone has to
   remember to keep adding to, which `project-context.md` warns about directly: *"A list that can grow
   past what tests it eventually will."*

**Be clear about what this does not do.** It does not make the build reject `return null` from a
non-nullable method. That needs a nullness *checker* — see D5 — which is a separate decision this
story deliberately does not take. What you get is: your IDE flags violations as you type, the contract
lives in the signature instead of a comment, coverage cannot silently lapse, and a checker becomes a
switch to flip rather than a project to run.

**No production behaviour changes.** No class gains or loses a field, no query changes, no endpoint
moves. If a test's expectations change, something is wrong.

---

## Acceptance Criteria

**AC1 — every production package declares its nullness**

**Given** a service's production source tree
**When** it is inspected
**Then** every package holding at least one class carries `@NullMarked` on a `package-info.java`
**And** this holds for every service the repository builds
[Source: epics/epic-1-foundations-fare-quote.md#Story 1.5; ARCHITECTURE-SPINE.md#Consistency
Conventions — this story adds the Nullness row]

**AC2 — the declaration cannot lapse**

**Given** a new package added to production code with no `package-info.java`
**When** the suite runs
**Then** the build fails, naming the package
**And** the rule is proven by planting that violation, not by inspection
[Source: project-context.md#Static analysis — *"Prefer a fix that makes the proof structural over one
that adds another hand-maintained list"*; CLAUDE.md#Prove it, don't reason about it]

**AC3 — nullability is expressed in the signature, never in prose**

**Given** a value that genuinely can be absent
**When** it crosses a method boundary
**Then** it is annotated `@Nullable` at that point
**And** no comment or javadoc is the sole statement that something may be null
[Source: AGENTS.md#Comments — *"nothing tests a comment, so a wrong one outlives wrong code"*]

**AC4 — nothing about the build's behaviour changes**

**Given** the full gate
**When** `make build` and `make test` run
**Then** the results are identical to the pre-change baseline apart from the new rule
**And** no test expectation is edited to accommodate this story
[Source: project-context.md#Before you call it done]

**Standing criteria that also apply here** (epics/overview.md#Standing acceptance criteria): any
container this project builds runs non-root. Nothing in this story touches a container definition, so
this is a check rather than work.

---

## Story-local decisions you must implement as written

**Six things this story needs have no source anywhere in the planning artifacts** — no document
mentions nullness at all before this one. They are pinned here so the implementation is deterministic.
Every version and API below was verified against upstream artefacts on 2026-08-26; nothing is asserted
from memory.

### D1 — `org.jspecify:jspecify:1.0.1`, declared per service, at `implementation` scope

**Why JSpecify rather than any of the alternatives.** It is the annotation standard the ecosystem
converged on — Google, JetBrains, Uber and the Checker Framework all participate — which matters
because the *checker* is a separate, later choice (D5) and JSpecify is what every candidate reads.
`javax.annotation.Nullable` (JSR-305) is dormant and was never standardised;
`org.springframework.lang.Nullable` is Spring-internal and ties the domain model to the framework,
which `modelDependsOnNothingFrameworkFlavoured` would fail the build on.

Verified by inspecting the jar: **3 064 bytes, four annotations, zero dependencies.**

```
org/jspecify/annotations/NullMarked.class      RUNTIME retention; MODULE, PACKAGE, TYPE, METHOD, CONSTRUCTOR
org/jspecify/annotations/Nullable.class        RUNTIME retention; TYPE_USE
org/jspecify/annotations/NonNull.class         RUNTIME retention
org/jspecify/annotations/NullUnmarked.class    RUNTIME retention
```

**`implementation`, not `compileOnly`.** `compileOnly` would also work — the JVM silently omits an
annotation whose class it cannot resolve rather than throwing — but it needs a second
`testImplementation` line so ArchUnit can resolve `NullMarked.class` for D3's rule, and it leaves a
partial state that is confusing to reason about the first time something reflects over a class. Three
kilobytes with no transitive dependencies is not worth the subtlety.

**Declared in each service's own `build.gradle`**, duplicated across services. That is AD-52: no root
build, no shared plugin, no `buildSrc`. Two lines of duplication is the accepted cost.

### D2 — one `package-info.java` per package, because `@NullMarked` does not cascade

```java
@NullMarked
package com.puber.matching.fare.model;

import org.jspecify.annotations.NullMarked;
```

**JSpecify's scope is the annotated element and what it *encloses*, and a package does not enclose its
subpackages.** So `@NullMarked` on `com.puber.matching` says nothing about
`com.puber.matching.fare.model`. Every package that holds a class needs its own file.

**Do not try to prove or exploit the cascade rule.** With no checker in this repository (D5), cascade
behaviour is unobservable here — it shows up only in the IDE and in a future checker. Annotating every
package is correct under the spec either way, costs one four-line file per package, and is what makes
D3's rule a simple uniform check rather than a tree-walk with exceptions.

**`@NullMarked` also targets `MODULE`**, so a single annotation would cover a whole service if these
were JPMS modules. They are not, and adding JPMS to a Spring Boot application is a far larger change
than these files. Do not open that.

**Scope: production code only.** `src/test` and `src/integrationTest` are deliberately excluded — see
D6.

### D3 — the guard: an ArchUnit rule, in `ArchitectureRulesTest`

`ArchitectureRulesTest` is declared `DoNotIncludeTests` and governs production code, so it is the
correct home; `TestNamingRulesTest` is `OnlyIncludeTests` and a rule placed there would scan nothing
and pass forever (project-context.md → Static analysis).

**ArchUnit 1.5.0 supports this first-class** — verified against the jar in the Gradle cache:

```
JavaPackage implements HasAnnotations<JavaPackage>
  boolean          isAnnotatedWith(Class<? extends Annotation>)
  Optional<...>    tryGetPackageInfo()
  Set<JavaClass>   getClasses()
  Set<JavaPackage> getSubpackagesInTree()
```

And the annotation genuinely reaches the bytecode ArchUnit reads — proven on 2026-08-26 by compiling a
`package-info.java` and reading the class file back:

```
RuntimeVisibleAnnotations:
  0: #8()
    org.jspecify.annotations.NullMarked
```

The rule, in shape:

```java
@ArchTest
static final ArchRule everyPackageDeclaresItsNullness = ...
        // for each package under com.puber.<service> that has getClasses().isEmpty() == false,
        // require isAnnotatedWith(NullMarked.class)
```

**Two things it must get right, and both are ways this rule fails silently:**

- **Only packages that hold classes.** `com`, `com.puber` and intermediate packages such as
  `com.puber.matching.fare` hold no classes of their own; requiring `package-info.java` in them is
  scaffolding an empty declaration to satisfy a diagram, which project-context.md forbids. In
  `matching-service` today that is **7 real packages out of 11 directories** — check the split rather
  than counting directories.
- **It must not pass vacuously.** Assert that the rule actually saw packages before checking them, the
  same guard `TestNamingRulesIntegrationTest` carries and for the same reason. A rule whose input set
  is empty passes forever.

### D4 — where a `@Nullable` is allowed, and the order to try first

There is nothing to annotate today: **zero nulls in production code**, verified by grep over all 12
classes. So this story adds `@NullMarked` and, most likely, not a single `@Nullable`.

When one is needed later, **try these in order and only fall through when the one above genuinely does
not fit:**

1. **Throw instead.** `FareRuleRepository.priceList()` already does this — AD-62: *"A missing row is a
   failure, never a default. The read throws; it does not return an empty result a caller can ignore
   into a free ride."*
2. **Make absence a type.** A value type that either holds a value or does not exist. That is what
   `Money`, `Distance` and `Deadline` already are.
3. **`Optional<T>` as a return type.** Legitimate for a lookup that genuinely may find nothing — a
   ride by id. Never as a field or a parameter.
4. **`@Nullable`.** The honest last resort, for a nullable column read straight into a record, or an
   optional field on an inbound message.

**`@Nullable` is TYPE_USE, so it goes on the type and not the member:** `@Nullable String name`, and
for arrays and generics the position carries meaning — `String @Nullable []` is a nullable array of
non-null strings. If a signature needs that distinction explained in a comment, prefer 1–3 instead.

### D5 — this story adds no checker, and that boundary is the point

**No NullAway. No Error Prone. No Checker Framework.** `project-context.md` → *"Static analysis:
ArchUnit + Spotless, nothing else… do not add Checkstyle, PMD, SpotBugs, Error Prone, NullAway or
Sonar without a decision."* This story amends that rule to admit **JSpecify as an annotation
dependency**, and nothing else.

The reasoning, recorded so the question is not reopened casually. NullAway `0.14.0` is the natural
checker — it declares `org.jspecify:jspecify:1.0.1` as a direct dependency and ships a `generics`
package for JSpecify's generic nullness, so the annotations this story adds are exactly what it reads.
But it runs as an Error Prone plugin, and Error Prone:

- was rejected by the Epic 1 static-analysis decision on grounds that still hold — *"Error Prone
  additionally couples to javac internals, which is the worst bet on a JDK this new"*;
- ships `error_prone_core:2.50.0` with `Build-Jdk-Spec: 21`, and **its Java 25 support is unverified**;
- requires `--add-exports` flags into `jdk.compiler`, where `project-context.md` currently says
  *"Never add `--add-exports` flags."* That sentence is written about ArchUnit and Spotless, which
  genuinely do not need them, so admitting Error Prone would mean amending it — a decision, not an
  edit.

**If you want the checker, it is a separate ticket and its first deliverable is evidence**, not code:
does Error Prone compile this project on Java 25 at all? Task 6.3 records that question where it will
be found.

### D6 — production code only; test sources are out of scope

The rule and the `package-info.java` files cover `src/main/java` in every service. `src/test/java` and
`src/integrationTest/java` are excluded, for three reasons:

- **`ArchitectureRulesTest` cannot see test code** — it is `DoNotIncludeTests`, so a rule covering test
  sources would have to live elsewhere and would scan nothing where it sits.
- **The `rules/fixtures/` classes are deliberate rule-violators** that exist to be scanned *as if they
  were production code*. Some of them would need `@NullUnmarked` for no reason other than to satisfy a
  rule about them, which is the tail wagging the dog.
- **Nullness in a test is caught by the test failing.** The value of a declared contract is for
  callers you cannot see; a test has none.

Say so in the completion notes rather than leaving it to be re-litigated as an oversight.

---

## Tasks / Subtasks

### Task 1 — the dependency (AC1, D1)

- [ ] **1.1** Add `implementation 'org.jspecify:jspecify:1.0.1'` to **every** service's `build.gradle`.
      Two services exist after PUB-4; write neither as a special case.
- [ ] **1.2** Confirm it resolves and adds nothing transitively:
      `./gradlew dependencies --configuration runtimeClasspath` through the container should show
      `org.jspecify:jspecify:1.0.1` as a leaf. If anything appears beneath it, stop — the artefact
      inspected on 2026-08-26 had zero dependencies, so a subtree means the wrong coordinate.
- [ ] **1.3** `make build` green before writing any `package-info.java`. A dependency that does not
      resolve is a different failure from a rule that does not fire; separate them.

### Task 2 — declare it, per package (AC1, D2)

- [ ] **2.1** For every package under `src/main/java` in **every** service that holds at least one
      class, add a `package-info.java` exactly as D2 shows. In `matching-service` after PUB-4 that is
      the `config`, `shared/model`, `shared/strategy`, `fare/model`, `fare/repository`,
      `fare/service`, `quote/model`, `quote/service`, `quote/controller` packages and the root
      `com.puber.matching` — **enumerate them from the tree rather than from this list**, which is
      accurate as of writing and will not be for long.
- [ ] **2.2** Do **not** add one to a package that holds no classes of its own — `com`, `com.puber`,
      `com.puber.matching.fare`, `com.puber.matching.shared`. An empty declaration to satisfy a
      diagram is what project-context.md forbids in "Create layer packages only as they gain content".
- [ ] **2.3** Spotless formats `src/*/java/**/*.java`, which includes `package-info.java`. Run
      `make format` and expect it to touch these files; commit the formatted form.

### Task 3 — the guard (AC2, D3)

- [ ] **3.1** Add `everyPackageDeclaresItsNullness` to each service's `ArchitectureRulesTest`. Rule
      bodies are copied per service, not shared — they are test code, and no service depends on
      another's (project-context.md → Static analysis; PUB-4-2's D6 table).
- [ ] **3.2** Restrict it to packages with `getClasses().isEmpty() == false`, per D3.
- [ ] **3.3** Add the **non-vacuity assertion**: the rule must have seen packages before judging them.
      Model it on `TestNamingRulesIntegrationTest`, which carries this guard because a rule that scans
      nothing passes forever.
- [ ] **3.4** **Prove it fires.** Plant a new production package holding one class and no
      `package-info.java`, run `make test`, capture the failure and confirm the message **names the
      package**, revert, confirm green. Then plant the inverse — a `package-info.java` with the
      annotation removed — and confirm that fails too. Record both in the Debug Log. CLAUDE.md → *"plant
      the violation, run the suite, capture the failure, then revert and show me the tree is clean."*
- [ ] **3.5** **Prove it cannot pass vacuously.** Break the package prefix the rule scans — point it at
      a package that does not exist — and confirm 3.3's assertion turns the suite red rather than green.
      This is the exact failure PUB-2's review found twice.

### Task 4 — no `@Nullable` yet, and that is the expected outcome (AC3, D4)

- [ ] **4.1** Verify by grep that production code still contains no `null` at all, so no `@Nullable` is
      added by this story. If the grep finds one, it arrived in PUB-4 — annotate it per D4's ordering
      and **say so in the completion notes**, because it means the design question was skipped.
- [ ] **4.2** Do **not** annotate anything speculatively, and do not introduce `@NullUnmarked`
      anywhere. Both would be guards against a caller that does not exist.

### Task 5 — nothing else changed (AC4)

- [ ] **5.1** No test expectation is edited. If one needs editing, this story changed behaviour and
      something is wrong — stop and say so rather than adjusting the test.
- [ ] **5.2** No `@Nullable`, `@NonNull` or `@NullUnmarked` in any signature that existed before this
      story, unless 4.1 found a real null.
- [ ] **5.3** `git diff --stat` should be: two `build.gradle` files, N `package-info.java` files, two
      `ArchitectureRulesTest` files, and the documents in Task 6. Anything else needs an explanation.

### Task 6 — record the decisions this story takes (D1, D5)

- [ ] **6.1** `project-context.md` → "Static analysis: ArchUnit + Spotless, nothing else": amend it to
      admit **JSpecify as an annotation-only dependency**, and state plainly that **no nullness checker
      is installed**, so nullness is IDE-checked and coverage-guarded but not build-enforced. Keep
      Error Prone and NullAway in the forbidden list. **A reader must not come away thinking the build
      rejects a null**, which is the one way this story could mislead.
- [ ] **6.2** `ARCHITECTURE-SPINE.md` → Consistency Conventions: add a **Nullness** row —
      `@NullMarked` per package, `@Nullable` in the signature where absence is real, and the D4
      ordering that says throwing or a value type comes first. The spine is where a convention binding
      every service lives; do not restate it in `project-context.md` (CLAUDE.md forbids the copy).
- [ ] **6.3** `deferred-work.md` **and** the epic file: record the checker question — *"does Error Prone
      run on Java 25?"* — with the evidence gathered on 2026-08-26 (`Build-Jdk-Spec: 21`, the
      `--add-exports` requirement, the Epic 1 rejection). Route it to an epic that will actually read
      it; `deferred-work.md` alone is an audit trail nothing reads.
- [ ] **6.4** `AGENTS.md`: add D4's ordering as style guidance — throw, then a value type, then
      `Optional` as a return type, then `@Nullable` — beside "Immutable Domain Objects". This is the
      one part of this story that is coding style rather than a project rule, so it is the one part
      that belongs in `AGENTS.md`.

### Task 7 — the gate

- [ ] **7.1** `make build`, then `make test`, **in that order, from a clean tree**, and read the output.
      Not `make test-unit`: it runs neither Spotless nor the integration suite, which is how PUB-2's
      review left the build red while reporting the suite green. Spotless matters unusually much here —
      this story adds a dozen new files it will want to reformat.
- [ ] **7.2** Report what you saw, including the known environmental red —
      `HealthReportsDownPromptlyIntegrationTest`'s precondition — named rather than omitted, and never
      quietly counted as green.
- [ ] **7.3** Set this story and `sprint-status.yaml` to `review` only then.
- [ ] **7.4** Leave everything **unstaged**. The repo owner reviews the unstaged diff.

---

## Dev Notes

### Why this story exists at all, given there are no nulls to fix

Worth reading before you decide any of it is ceremony. **The codebase has zero nulls: 12 production
classes, not one mention of `null`.** `FareRuleRepository` throws rather than returning null;
`Coordinates`, `Distance`, `Money`, `FareRule` and `Deadline` either hold a value or do not exist.

So this story does not fix a bug. It is the same move PUB-1 made when it wrote architecture rules that
were vacuously true — `ArchitectureRulesTest`'s own javadoc says it, and it is the reason
`allowEmptyShould(true)` is *"deliberate, not a workaround"*:

> These are the rules four more services inherit, and a rule added after the code it governs is a rule
> that gets negotiated against existing violations rather than enforced.

**Adopting at zero nulls costs a dozen four-line files and has nothing to migrate.** Adopting once
nulls exist means auditing each one and arguing about it.

**And the nulls are coming on a known schedule.** `project-context.md` → *"Migrations are expand-only:
additive, **nullable**, no backfill in the same migration."* Every new column arrives nullable, so from
Epic 3 every record field read from a fresh column can be null. Add repository lookups that may find
nothing (`find ride by id`, Story 3.4), Redis cache misses (Story 4.7), and optional event fields
(Epic 4).

**Unlike PUB-1's vacuous rules, this story's rule is not vacuous.** It fires the first time somebody
adds a package without a declaration — which will happen in Epic 2.

### What is actually enforced, and what is not — read this before writing the completion notes

| Concern | Enforced by | Fails the build? |
| --- | --- | --- |
| Every package declares its nullness | this story's ArchUnit rule | **yes** |
| A `@Nullable` value is dereferenced without a check | nothing here | **no** |
| A non-nullable method returns null | nothing here | **no** |
| Either of the two above, while you type | IntelliJ, which reads JSpecify natively | n/a — IDE only |

**The middle two rows are the honest limit of this story.** State them in the completion notes in these
words or close to them. Claiming this story makes nullness safe would be the exact failure CLAUDE.md
warns about: *"A rule that cannot fire is indistinguishable from a rule that passes, and nothing else
will catch it."*

The IDE row is real value rather than a consolation — it is where the mistake gets made and therefore
where the feedback is cheapest — but it is **not a gate**. `project-context.md` → NFR-7 is explicit that
analysis runs in containers requiring no IDE plugin, so nothing in this repository's gate depends on
IntelliJ and nothing should be written as if it does.

### Verified facts, and how they were verified

Everything technical in this story was checked against upstream artefacts on 2026-08-26 rather than
recalled:

| Claim | How it was checked |
| --- | --- |
| `jspecify 1.0.1` is the current release | Maven Central metadata: `0.2.0 … 1.0.0, 1.0.1` |
| It is 3 KB, four annotations, zero dependencies | downloaded and listed the jar; its POM has no `<dependencies>` block |
| All four annotations are `RUNTIME` retention | `javap -v` on each class |
| `@NullMarked` targets `MODULE, PACKAGE, TYPE, METHOD, CONSTRUCTOR`; `@Nullable` is `TYPE_USE` | same |
| A `package-info.java` carries the annotation into bytecode | compiled one and read `RuntimeVisibleAnnotations: org.jspecify.annotations.NullMarked` back |
| ArchUnit 1.5.0 can read a package annotation | `javap` on `JavaPackage`: `isAnnotatedWith(Class)`, `tryGetPackageInfo()`, `getClasses()`, `getSubpackagesInTree()` |
| NullAway is the JSpecify-aware checker | its POM declares `org.jspecify:jspecify:1.0.1` directly; it ships a `generics` package |
| Error Prone's Java 25 status | **not verified — this is the open question.** `error_prone_core:2.50.0` ships `Build-Jdk-Spec: 21` |

The last row is the only unverified claim in the story, and it is why D5 defers the checker instead of
adopting it.

### Traps in this repository that this story will meet

- **Two ArchUnit rule classes, and they cannot be merged.** `ArchitectureRulesTest` is
  `DoNotIncludeTests` (production); `TestNamingRulesTest` is `OnlyIncludeTests` (tests). The import
  options are mutually exclusive, so a rule in the wrong class scans nothing and passes forever.
  This rule belongs in the first.
- **Rule bodies are copied per service, never shared.** PUB-4-2 established this for the time and layer
  rules. Two copies now; if a third service arrives, three.
- **Spotless formats `package-info.java`** — it matches `src/*/java/**/*.java`. Run `make format`.
- **A rename can crash `make format`.** Spotless's `build/spotless-clean` copies stop resolving;
  `make analyzer-config` clears them. You are adding a dozen files, so expect it.
- **Do not run `./gradlew` directly inside a service.** It bypasses the analyzer-config and contract
  copies and `build.gradle` throws telling you so.
- **`./gradlew check --warning-mode all` reports zero deprecations.** Keep it that way.
- **`make build` runs with `--no-deps`, so no datastore is running.** Nothing in this story needs one;
  if you find yourself wanting an integration test here, ask what it would prove that the ArchUnit rule
  does not.

### Honest limits of this story

**It buys vocabulary and coverage, not safety.** The table above is the whole of it. Anyone reading only
the title will assume more.

**`@Nullable` will almost certainly not appear.** With zero nulls, this story is `@NullMarked` files and
a rule. That can read as a story that did nothing; the completion notes should say what it *enables*
rather than what it changed.

**The IDE half is unverified in this environment.** IntelliJ is documented as understanding JSpecify and
the repo has an `.idea/` directory, but nothing here proves the inspection fires on your setup. It is
worth thirty seconds to confirm — write `return null` in a `@NullMarked` package and see whether it
goes yellow — and worth recording the answer, because it is most of the story's practical value.

**Nothing prevents `@NullUnmarked` being used later to silence an inconvenient case.** No rule bans it,
and banning it now would be a guard with no reproduced failure. If it starts appearing, that is the
signal to revisit the checker question rather than to add another rule.

### Scope boundaries — what is deliberately not here

| Not in this story | Where it lands |
| --- | --- |
| NullAway, Error Prone, the Checker Framework, any dataflow analysis | A separate ticket, gated on Task 6.3's evidence |
| `@Nullable` on anything | The story that introduces the first genuinely nullable value — Epic 3's first nullable column, most likely |
| `src/test` and `src/integrationTest` coverage | Nowhere, deliberately — D6 |
| JPMS modules, so one annotation could cover a service | Nowhere — a far larger change than the files it saves |
| Kotlin interop, which is JSpecify's other headline benefit | Nowhere — there is no Kotlin in this project |
| `Optional` as a field or parameter type | **Never** — D4 permits it as a return type only |
| Any change to a query, an endpoint, a migration or a container | Nowhere — AC4 forbids it |

### Previous story intelligence

1. **A test that cannot fail is the finding that recurs in every review so far.** PUB-2 planted two
   typos into a banned-method list and the whole suite stayed green. PUB-3 replaced `CalculateFare`'s
   body with a zero distance and the suite stayed green. Tasks 3.4 and 3.5 are written as
   plant-run-capture-revert because of it, and 3.5 targets the vacuity case specifically.
2. **A rationale is not evidence.** Three guards written during PUB-1 each had a convincing comment and
   each guarded nothing. Task 4.2 forbids speculative annotation for the same reason.
3. **Read `AGENTS.md` before writing code.** Task 6.4 edits it, which is not a substitute for reading
   it. Its Comments section governs what goes into a `package-info.java`: the annotation, the package
   declaration, the import. **No explanatory comment** — if a reader needs the convention explained,
   the spine's Nullness row is where it lives.
4. **PUB-3 left `AGENTS.md` modified but unstaged**, shipping enforcement without the rule authorising
   it. This story edits four documents; `pre-commit` analyses the index, so check what you staged.
5. **The File List is routinely incomplete.** PUB-3's review found ten touched files missing. This story
   adds a dozen near-identical files, which is exactly the shape that gets summarised instead of listed.

### Pinned versions — do not move any of these

`org.jspecify:jspecify` **1.0.1** · ArchUnit **`archunit-junit6:1.5.0`** · Spotless **8.10.0** ·
Java/Temurin **25** · Spring Boot **4.1.0** · Gradle wrapper **9.5.1**

**One new dependency is the entire budget**, and it is annotations only. Any checker, plugin or
analyzer is out of scope by D5 — adding one is an architecture decision, not a tooling choice.

### Project Structure Notes

Target shape after this story. Illustrative for `matching-service`; **enumerate the real packages from
the tree**, since PUB-4 will have added to them:

```
services/matching-service/
  build.gradle                                       (edited -- one line)
  src/main/java/com/puber/matching/
    package-info.java                                (new)
    config/package-info.java                         (new)
    shared/model/package-info.java                   (new)
    shared/strategy/package-info.java                (new)
    fare/model/package-info.java                     (new)
    fare/repository/package-info.java                (new)
    fare/service/package-info.java                   (new)
    quote/model/package-info.java                    (new -- exists after PUB-4-1)
    quote/service/package-info.java                  (new -- after PUB-4-1)
    quote/controller/package-info.java               (new -- after PUB-4-1)
    -- and NOT in shared/ or fare/, which hold no classes of their own
  src/test/java/com/puber/matching/rules/
    ArchitectureRulesTest.java                       (edited -- the new rule)

services/rider-service/                              (same treatment -- exists after PUB-4-2)
  build.gradle                                       (edited)
  src/main/java/com/puber/rider/{,config,controller,service,model}/package-info.java   (new)
  src/test/java/com/puber/rider/rules/ArchitectureRulesTest.java                        (edited)

ARCHITECTURE-SPINE.md                                (edited -- Nullness convention, Task 6.2)
project-context.md                                   (edited -- Task 6.1)
AGENTS.md                                            (edited -- Task 6.4)
_bmad-output/implementation-artifacts/deferred-work.md  (edited -- Task 6.3)
```

Java packages stay suffix-free (`com.puber.matching`); directories keep the `-service` suffix (AD-12).
The domain package is `model`, never `entity`.

### References

- `_bmad-output/planning-artifacts/epics/epic-1-foundations-fare-quote.md#Story 1.5: Every package declares its nullness` — this story's criteria
- `_bmad-output/planning-artifacts/epics/overview.md#Standing acceptance criteria` — apply whether restated or not
- `ARCHITECTURE-SPINE.md#Consistency Conventions` — where Task 6.2 adds the Nullness row; `#AD-7`, `#AD-8` (layers, one-way), `#AD-52` (no root build, per-service declarations), `#AD-62` (a missing row throws — D4's first option), `#Stack`
- `project-context.md#Static analysis: ArchUnit + Spotless, nothing else` — the rule Task 6.1 amends; `#YAGNI`; `#Conventions easy to violate silently` (expand-only migrations are nullable — why this story matters from Epic 3)
- `AGENTS.md#Immutable Domain Objects` — where Task 6.4's ordering goes; `#Comments` — what may go in a `package-info.java`
- `CLAUDE.md#Prove it, don't reason about it` — Tasks 3.4 and 3.5
- `_bmad-output/implementation-artifacts/PUB-4-1-*.md`, `PUB-4-2-*.md` — the two services this story covers, and PUB-4-2's D6 table for how rule copies are handled per service
- `_bmad-output/implementation-artifacts/PUB-1-*.md` — the vacuously-true-rules-written-early precedent this story follows

---

## Questions for the repo owner

None of these blocks implementation.

1. **This story deliberately stops short of enforcement (D5).** You asked for "everything non-null by
   default, annotate what is nullable", and this delivers the declaration and the coverage guard but
   **not** a build failure on a nullness mistake. The checker that would give you that is NullAway,
   which needs Error Prone, whose Java 25 support is unproven and which Epic 1 rejected. Task 6.3
   records the question. If you would rather find out now, that spike is a small ticket whose
   deliverable is an answer rather than code.
2. **`implementation` rather than `compileOnly` (D1).** Ships 3 KB of annotations into the runtime
   image for no runtime use, in exchange for one dependency line instead of two and no reasoning about
   partially-resolvable annotations. Easy to reverse either way.
3. **Test sources are excluded (D6).** Defensible — `ArchitectureRulesTest` cannot see them and the
   `rules/fixtures/` violators would need `@NullUnmarked` purely to satisfy a rule about themselves.
   But it does mean "every package" in AC1 means "every production package".

---

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change | By |
| --- | --- | --- |
| 2026-08-26 | Story created at the repo owner's request, sequenced after PUB-4 | bmad-create-story |
