# Independent Review — Technology Currency and Fit

**Target:** `ARCHITECTURE-SPINE.md` (Puber, `architecture-puber-2026-08-03`)
**Review date:** 2026-08-13
**Reviewer scope:** version currency, cross-version compatibility, product viability/fit, and factual accuracy of technology-specific claims in the AD blocks.
**Method:** every verdict below was checked against a live upstream source (vendor release page, GitHub release API, Maven Central metadata, or official docs) on 2026-08-13. No verdict rests on recall.

---

## Verdict

**Conditional pass with six blocking corrections.** The architectural reasoning is sound and every
technology-specific *claim* in the AD blocks checked out as factually true — that part of the
document is genuinely reality-checked. The **Stack table is not**. Six of seventeen rows are stale,
end-of-life, or actively incompatible with the very Spring Boot version the same table pins. The
note "Versions verified current at authoring" is therefore only partly earned: the *core* platform
rows (Java, Spring Boot, Spring gRPC, Gradle, PostgreSQL, Kafka, Redis, Prometheus) are accurate
and mutually compatible; the *peripheral* rows (Resilience4j, Stripe, Testcontainers, Flyway,
Argo CD, ClickHouse) look like they were carried over from an older draft and never re-checked.

One of these is a hard build break rather than a staleness nit: **Resilience4j 2.3.x has no Spring
Boot 4 module at all.**

---

## 1. Stack table — per-row verdicts

Legend: **OK** = real, current, supported. **AMBER** = real and supported but trailing or about to
expire. **RED** = out of date to the point of being broken, unsupported, or incompatible with
another pinned row.

| # | Name | Pinned | Reality as of 2026-08-13 | Verdict | Verified from |
|---|---|---|---|---|---|
| 1 | Java (Temurin) | 25 | Temurin 25 is a real LTS, released 2025-09-22 (25.0.0+36-LTS); Adoptium commits to ≥4 years of LTS support. Within Spring Boot 4.1's supported range (17–26). | **OK** | https://adoptium.net/news/2025/09/eclipse-temurin-25-available · https://adoptium.net/support |
| 2 | Spring Boot | 4.1.x | 4.1.0 GA 2026-06-10; current supported line; OSS support to 2027-07-31. Requires Spring Framework 7.0.8+. | **OK** | https://spring.io/blog/2026/06/10/spring-boot-4/ · https://docs.spring.io/spring-boot/system-requirements.html |
| 3 | Spring gRPC | 1.1.x | 1.1.0 is real and is *exactly* the version Spring Boot 4.1.0 manages (`spring-grpc.version=1.1.0`), backed by grpc-java 1.80.0. 1.1's headline change is the migration of autoconfiguration onto Boot 4.1. | **OK** | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.0/spring-boot-dependencies-4.1.0.pom · https://www.infoq.com/news/2026/06/spring-boot-4-1/ |
| 4 | Gradle Wrapper | 9.x | Current Gradle is 9.7.0 (built 2026-08-06). Spring Boot 4.1 supports Gradle 8.14+ and 9.x. Gradle 9.x runs on JDK 17–26, so JDK 25 is fine. | **OK** | https://services.gradle.org/versions/current · https://docs.spring.io/spring-boot/system-requirements.html |
| 5 | PostgreSQL | 18.6 | 18.6 is real — it is the **current** minor of PG 18, released **2026-08-13** (today). PG 18 is supported until 2030-11-14. A rare case of a pin being exactly right. | **OK** | https://www.postgresql.org/support/versioning/ |
| 6 | Flyway | 11.x | Flyway is on **13.3.0** (released 2026-08-13); the 12 line ran to 12.11.0 and 13.0.0 landed 2026-07-20. More pointedly, **Spring Boot 4.1.0's BOM manages `flyway.version=12.4.0`** — pinning 11.x means overriding the managed version with a two-major-versions-older release. | **RED** | https://api.github.com/repos/flyway/flyway/releases · Boot 4.1.0 BOM (link in row 3) |
| 7 | Apache Kafka (KRaft) | 4.3.1 | 4.3.1 is a real, current release tag (latest GA on the 4.3 line). Broker-side pin is good. Note the Boot 4.1 BOM manages **client** `kafka.version=4.2.1` — compatible (4.2 clients against a 4.3 broker), but the table should not be read as pinning the client too. | **OK** (note) | https://api.github.com/repos/apache/kafka/tags · Boot 4.1.0 BOM |
| 8 | Redis | 8.x | Redis 8 is the current major; 8.10.0 released 2026-07-29 with active support. 8.x is a sound pin, though "8.x" spans lines that are already security-only (8.4, 8.6, 8.8) — 8.10 is the one with full maintenance. | **OK** (tighten) | https://api.github.com/repos/redis/redis/releases · https://endoflife.date/redis |
| 9 | ClickHouse | 25.x | Current stable is **26.7**; current LTS is **26.3**. In the entire 25 line only **25.8 LTS** is still supported, and its end-of-support is **2026-08-29 — sixteen days from now**. Every other 25.x is already EOL. | **RED** | https://api.github.com/repos/ClickHouse/ClickHouse/releases · https://clickhouse.com/docs/faq/operations/production |
| 10 | HAProxy | 3.2.x | 3.2 is a real LTS (released 2025-05-28) maintained to 2030-Q2. Supported and defensible. The newest LTS is 3.4 (2026-06-03); 3.2 is simply the previous LTS, which is a legitimate conservative choice. | **OK** | https://www.haproxy.org/ |
| 11 | Prometheus | 3.x | Current: 3.13.2 (2026-07-30), with 3.14.0-rc.0 in flight. 3.x is the live major. | **OK** | https://api.github.com/repos/prometheus/prometheus/releases |
| 12 | Grafana | 12.x | Grafana 13 shipped 2026-04-14; current is 13.1.3. Of the 12 line only **12.4** still receives fixes (12.4.8, 2026-08-07) — 12.3 goes end-of-support 2026-08-19. So "12.x" is true only if it means 12.4 specifically, and it is a whole major behind. | **AMBER** | https://api.github.com/repos/grafana/grafana/releases · https://grafana.com/docs/grafana/latest/upgrade-guide/when-to-upgrade/ |
| 13 | Resilience4j | 2.3.x | **Broken against the pinned Spring Boot.** 2.3.0 was released 2025-01-03 — before Spring Boot 4 existed. Maven Central shows `resilience4j-spring-boot4` exists at **2.4.0 and only 2.4.0**; 2.3.x ships `resilience4j-spring-boot2` / `-spring-boot3` only. Latest release is 2.4.0 (2026-03-14). | **RED** | https://repo1.maven.org/maven2/io/github/resilience4j/resilience4j-spring-boot4/maven-metadata.xml · https://api.github.com/repos/resilience4j/resilience4j/releases |
| 14 | Stripe Java SDK | 29.x | Current is **33.3.0** (2026-08-10) — four majors ahead. v29 is pinned to Stripe API version `2025-03-31.basil`. Stripe's own policy: fixes and features land only on the latest major; older majors "won't receive any additional updates". | **RED** | https://api.github.com/repos/stripe/stripe-java/releases · https://docs.stripe.com/sdks/versioning |
| 15 | Testcontainers | 1.21.x | Testcontainers Java is on **2.x** (2.0.5, 2026-04-20), and **Spring Boot 4.1.0's BOM manages `testcontainers.version=2.0.5`**. Pinning 1.21.x means fighting the BOM on a dependency whose Boot integration (`spring-boot-testcontainers`) is compiled against 2.x. | **RED** | Boot 4.1.0 BOM · https://api.github.com/repos/testcontainers/testcontainers-java/releases |
| 16 | Kubernetes (kind) | 1.34.x | 1.34 is still supported but enters **maintenance mode 2026-08-27** and is **EOL 2026-10-27**; current is 1.36 (1.36.3). kind v0.32.0 (2026-06-02) **defaults to node image v1.36.1** — so 1.34 now requires explicitly pinning an older node-image digest. | **AMBER** | https://kubernetes.io/releases/ · https://api.github.com/repos/kubernetes-sigs/kind/releases/tags/v0.32.0 |
| 17 | Argo CD | 3.2.x | **End of life.** Argo CD supports only the three most recent minors; those are 3.3, 3.4, 3.5. The 2026-08-12 patch batch shipped 3.3.14 / 3.4.7 / 3.5.1 and **skipped 3.2** — whose last patch was 3.2.12 on 2026-05-13. 3.2 receives no further bug or security fixes. | **RED** | https://api.github.com/repos/argoproj/argo-cd/releases · https://argo-cd.readthedocs.io/en/stable/developer-guide/release-process-and-cadence/ |

**Score: 9 OK, 2 AMBER, 6 RED.** No version in the table is *invented* — every number corresponds
to a real release. The failures are staleness and incompatibility, not fabrication.

---

## 2. Compatibility analysis

### 2.1 Java 25 + Spring Boot 4.1.x + Spring gRPC 1.1.x — **compatible, verified**

- Spring Boot 4.1.0 system requirements state **Java 17 minimum, compatible up to and including
  Java 26**, and require Spring Framework 7.0.8+. Java 25 sits comfortably inside that window.
  (https://docs.spring.io/spring-boot/system-requirements.html)
- Spring Boot 4.1.0's own `gradle.properties` pins `springFrameworkVersion=7.0.8` and
  `graalVersion=25`, corroborating a Java-25-era baseline.
  (https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.0/gradle.properties)
- Spring gRPC 1.1.0 is the version **managed by the Boot 4.1.0 BOM**, and 1.1.0's stated purpose is
  migrating its autoconfiguration to Boot 4.1.0. Boot 4.1 additionally ships first-class
  `spring-boot-grpc-server` / `-client` / `-test` modules over grpc-java 1.80.0.

  This is worth calling out as a **positive** finding: the spine picked the one gRPC integration
  that Spring itself now blesses, at the exact version Boot manages. That is the strongest-evidenced
  row in the table.

  *Actionable nuance:* because Boot 4.1 now provides gRPC starters directly, the build should
  consume `spring-boot-starter-grpc-server` / `-client` and let the BOM supply Spring gRPC 1.1.0
  rather than declaring `spring-grpc` coordinates with an explicit `1.1.x`. A migration guide exists
  for anyone coming from Spring gRPC 1.0 + Boot 4.0.

- Gradle 9.x runs on JDK 17–26 and is supported by Boot 4.1. No conflict.

### 2.2 Spring Boot 4.1.x + the listed client libraries — **three conflicts**

Verified directly against the published BOM
(`spring-boot-dependencies-4.1.0.pom`, https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.0/spring-boot-dependencies-4.1.0.pom):

| Managed property | Boot 4.1.0 value | Spine pins | Conflict? |
|---|---|---|---|
| `flyway.version` | **12.4.0** | 11.x | **Yes** — two majors older than managed |
| `testcontainers.version` | **2.0.5** | 1.21.x | **Yes** — one major older than managed |
| `kafka.version` (clients) | 4.2.1 | 4.3.1 (broker) | No — broker/client skew is fine and intended |
| `spring-kafka.version` | 4.1.0 | (unpinned) | No |
| `spring-grpc.version` | 1.1.0 | 1.1.x | No — exact match |
| `grpc-java.version` | 1.80.0 | (unpinned) | No |
| `lettuce.version` | 7.5.2.RELEASE | (unpinned, Redis 8.x server) | No |
| `postgresql.version` (JDBC) | 42.7.11 | (unpinned, PG 18.6 server) | No |
| `jacksonVersion` | 3.1.4 (with `jackson2Version=2.21.4`) | (unpinned) | See §2.4 |

**Conflict A — Resilience4j 2.3.x cannot autoconfigure on Spring Boot 4.** This is the single
blocking finding. Resilience4j 2.3.0 predates Spring Boot 4 by roughly a year. Maven Central
metadata shows:

```
resilience4j-spring-boot3  → versions include 2.3.0, 2.4.0 (latest 2.4.0)
resilience4j-spring-boot4  → 2.4.0 ONLY
resilience4j-spring7       → does not exist
```

The `resilience4j-spring-boot4` 2.4.0 POM depends on `spring-boot-autoconfigure:4.0.0` (and on
`resilience4j-spring6:2.4.0`, which despite the name is the Spring Framework 6/7 core module).
Pinning 2.3.x therefore leaves AD-6's bulkheads and AD-34's circuit breaker with **no working
starter** — the developer either hand-wires everything or discovers the problem on first build.

Additional context worth surfacing to the architect: there are still open upstream issues about
Spring Boot 4 artifacts and BOM coverage (e.g. resilience4j#2427 "Create a new release to include
Spring Boot 4 compatibility in BOM", open since 2026-03-27), so the Boot 4 story here is *new*, not
mature. That is a fit risk for AD-6/AD-34, not just a version-number nit.

**Conflict B — Flyway 11.x vs managed 12.4.0.** Overriding downward across two majors is exactly
the kind of thing that produces a surprise at first migration. Flyway 12 and 13 both changed
enough to warrant reading the notes; there is no reason for a greenfield project to start on 11.

**Conflict C — Testcontainers 1.21.x vs managed 2.0.5.** AD-10 and the Testing convention make
Testcontainers load-bearing for the entire correctness story ("race-safety *is* Postgres's
behaviour... tests run against real Postgres via Testcontainers"). Starting that on a superseded
major, against a BOM that manages 2.0.5, undermines the one testing decision the architecture leans
hardest on. Note also that pre-2.x Testcontainers has known friction with newer Docker client API
versions.

### 2.3 Deployment-layer compatibility — **workable, but aging out**

- Argo CD 3.2 is EOL; Argo CD 3.5 is tested against Kubernetes v1.36/1.35/1.34/1.33 and 3.4/3.3
  against v1.35–1.32, so upgrading Argo CD to 3.4 or 3.5 keeps 1.34 in the tested matrix.
  (https://argo-cd.readthedocs.io/en/stable/operator-manual/installation/)
- kind v0.32.0 defaults to Kubernetes 1.36.1 and warns that node images are not guaranteed
  compatible across kind releases. Running 1.34 on kind v0.32 means pinning an older node-image
  digest by hand. Since AD-49 only needs *a* local cluster, taking kind's default (1.36) removes a
  moving part rather than adding one.
- kind v0.32 also replaced HAProxy with Envoy as the internal load balancer for multi-control-plane
  clusters. Irrelevant to AD-5 (which uses HAProxy as the *application* gateway), but worth knowing
  so nobody conflates the two.

### 2.4 One unflagged compatibility trap in AD-33

AD-33 says: *"note a hand-built Jackson `ObjectMapper` fails on them by default, which is exactly
where Kafka consumer configs build their own."* The claim about `FAIL_ON_UNKNOWN_PROPERTIES`
defaulting to on for a bare `new ObjectMapper()` is correct, and the observation is a good one.

However, Spring Boot 4.1 manages **Jackson 3.1.4** alongside a Jackson 2 fallback
(`jacksonVersion=3.1.4`, `jackson2Version=2.21.4`), and Boot 4.1 reworked its Jackson
auto-configuration properties. Jackson 3 changed package coordinates and several defaults. AD-33's
guidance stands, but the AD should name *which* Jackson it means, or the "hand-built ObjectMapper"
advice will be applied against a different default surface than the author had in mind. **Minor —
clarify, don't rewrite.**

---

## 3. Product viability and fit

Every named technology still exists, is actively developed, and is a sensible fit for its stated
role. No abandonware, no renamed-and-forgotten projects, no obvious successor being ignored.

| Technology | Role in spine | Still viable? | Notes |
|---|---|---|---|
| Temurin / Java 25 | runtime | Yes | Live LTS |
| Spring Boot 4.1 | app framework | Yes | Current line |
| Spring gRPC | AD-37 internal transport | Yes — *strengthened* | Boot 4.1 promoted gRPC to a first-class starter; the spine is on the right side of this |
| Gradle | build | Yes | Per-service wrappers align with the "no root build" decision |
| PostgreSQL | AD-1 per-service store | Yes | 18 is current; all AD-14/15/20/29 features are core PG |
| Flyway | migrations | Yes | Product healthy; only the pin is stale |
| Kafka (KRaft) | AD-28/36 backbone | Yes | KRaft is now the only mode — see §4.3 |
| Redis | AD-26/27 geo + position | Yes | 8.x is the live major |
| ClickHouse | AD-48 Tier-3 analytics | Yes | Product healthy; the 25.x pin is what expires |
| HAProxy | AD-5/6 edge + shedding | Yes | 3.2 LTS to 2030 |
| Prometheus / Grafana | Tier-3 observability | Yes | Both current products |
| Resilience4j | AD-6 bulkheads, AD-34 breaker | Yes, **with a caveat** | Still maintained (2.4.0, Mar 2026) but its Boot 4 support is brand new and only in 2.4.0. Boot 4 / Spring Framework 7 also now ship core retry support, which narrows what Resilience4j is needed for — worth a conscious re-justification rather than an assumption |
| Stripe Java SDK | AD-43 provider strategy | Yes | SDK actively released (33.3.0 on 2026-08-10, alphas weekly); only the pin is stale |
| Testcontainers | AD-10 testing substrate | Yes | 2.x is the live major |
| Kubernetes / kind | AD-49 target | Yes | kind actively released (v0.32.0, Jun 2026) |
| Argo CD | AD-49 GitOps reconciler | Yes | Product very healthy (3.5.1 on 2026-08-12); the 3.2 pin is what is dead |

**No deprecated or superseded technology choices found.** The one place a successor deserves a
mention is Resilience4j vs. Spring Framework 7's built-in retry/resilience support — not a
replacement for bulkheads or circuit breakers, but it does reduce the surface Resilience4j is
carrying, and AD-6/AD-34 would be stronger for saying so explicitly.

---

## 4. Technology-specific claims in the AD blocks

This is where the spine performs best. **Every substantive product claim I tested is true.**

### 4.1 AD-26/AD-27 — Redis sorted sets have no per-member TTL — **TRUE**

Redis has no native per-member expiration for sets or sorted sets; only the whole key can carry a
TTL. (Field-level TTL exists for *hashes* via `HEXPIRE`, added in 7.4 — but not for sorted sets, and
the spine does not claim otherwise.) The canonical workaround is exactly what the spine describes:
score-as-timestamp plus removal on encounter. AD-26's "Stale geo members are removed lazily on
encounter" and the Deferred entry "Eager staleness sweep" are both correct consequences of this
limitation, not hand-waves.
*Verified:* https://redis.io/docs/latest/commands/geosearch/ · https://groups.google.com/g/redis-db/c/44NWMLanGdI

### 4.2 AD-26 — GEOSEARCH behaves as described — **TRUE**

Verified against the command reference:
- Operates on "the key that holds the geospatial index (**a sorted set**)" — matching the spine's
  framing of the geo set as a Redis sorted set. ✓
- `FROMLONLAT longitude latitude` — **longitude before latitude**, which independently confirms the
  Coordinates convention line in Consistency Conventions. ✓
- `BYRADIUS radius M|KM|FT|MI` supports the 5 km matching radius of AD-25. ✓
- `ASC` sorts nearest-first, giving nearest-driver directly. ✓
- `COUNT n [ANY]` bounds the candidate set; the docs warn that `ANY` returns unsorted results, which
  matters if the implementation ever reaches for it to cut latency — nearest-driver would silently
  stop being nearest. **Worth a one-line note in AD-26: use COUNT without ANY.**
- `WITHCOORD` / `WITHDIST` supply the coordinates and distance AD-40's position endpoint needs from
  "one Redis read". ✓
- Command flags include `readonly`, so AD-27's "read replicas are the first scaling lever" is
  mechanically sound — GEOSEARCH can be served by a replica. ✓
- Since 6.2.0, and it explicitly supersedes the deprecated `GEORADIUS` / `GEORADIUSBYMEMBER`. The
  spine picked the non-deprecated command. ✓

*Verified:* https://redis.io/docs/latest/commands/geosearch/

### 4.3 Kafka 4.x is KRaft-only — **TRUE**

Kafka 4.0 (2025-03-18) was the first major release to run entirely without ZooKeeper; KRaft is the
only supported mode and ZooKeeper mode was fully removed. The spine's "(KRaft)" annotation is
accurate and not merely aspirational. Related true constraint the spine implicitly satisfies:
Kafka 4.x brokers require Java 17+ (the project is on 25).
*Verified:* https://kafka.apache.org/blog/2025/03/18/apache-kafka-4.0.0-release-announcement/

### 4.4 AD-14 — PostgreSQL partial unique index — **TRUE**

PostgreSQL documents this exact pattern (Example 11.3): a `CREATE UNIQUE INDEX ... WHERE <cond>`
that enforces uniqueness only over the qualifying subset. AD-14's index — unique on `rider_id` where
`status IN (...active...)` — is a textbook instance, and AD-14's claim that "the index holds only
active rides, so it stays small regardless of total volume" is precisely the efficiency property the
PostgreSQL docs cite for partial indexes.
*Verified:* https://www.postgresql.org/docs/18/indexes-partial.html

### 4.5 AD-20 — `FOR UPDATE SKIP LOCKED` — **TRUE**

Documented and supported: rows that cannot be immediately locked are skipped rather than waited on.
The docs name the use case explicitly — "avoiding lock contention with multiple consumers accessing
a queue-like table" — which is exactly AD-20's claim-loop worker pool and AD-34's outbox relay. The
docs' caveat that it gives "an inconsistent view of the data" is *by design* here and does not
undermine the AD.
*Verified:* https://www.postgresql.org/docs/18/sql-select.html

### 4.6 AD-29 — `GENERATED ALWAYS AS IDENTITY` — **TRUE with an over-claim**

What is correct:
- It is real syntax, backed by an implicit sequence. ✓
- `GENERATED ALWAYS` (vs `BY DEFAULT`) rejects user-supplied values without
  `OVERRIDING SYSTEM VALUE` — so the "one authority" framing is right. ✓
- Preferring it over timestamp ordering is sound reasoning; the clock-skew argument holds.

Where the wording over-reaches: AD-29 says the key is *"monotonic because it comes from one
authority."* Sequence values are **assigned** monotonically, but they are not **commit-ordered** —
transaction A can take id 100, transaction B take 101, and B commit first. The identity column also
carries no gap-free guarantee (the docs are explicit that gaps occur and that uniqueness itself
needs a PK/UNIQUE constraint, which the AD does supply by making it the primary key).

**Is this a real problem for Puber? Mostly no** — precisely because AD-34 deletes rows on publish
rather than tracking a high-water mark. A relay that remembered "last id published" *would* skip
rows committed out of order; a relay that claims-and-deletes cannot. So the design is safe, but for
an accidental reason the AD does not state.

**Recommended fix:** one clause added to AD-29 — "assignment is monotonic; commit visibility is not,
which is why the relay claims and deletes rather than tracking a high-water mark." That converts a
lucky property into a stated invariant, which is the entire point of a spine.
*Verified:* https://www.postgresql.org/docs/18/ddl-identity-columns.html

### 4.7 AD-38 — RFC 9457 is current, and Spring supports it — **TRUE**

- RFC 9457 "Problem Details for HTTP APIs" (July 2023), standards track, **explicitly obsoletes
  RFC 7807**. It is the current standard. ✓
- Spring Framework documents support for it directly: `ProblemDetail` is "representation for an RFC
  9457 problem detail", `ErrorResponse` is the contract for rendering one, and
  `ResponseEntityExceptionHandler` plus `spring.mvc.problemdetails.enabled=true` wire it up. Boot
  4.x inherits this. ✓
- The `ProblemDetail` type has been present since Spring Framework 6.0, so there is no
  bleeding-edge risk here.

*Verified:* https://www.rfc-editor.org/rfc/rfc9457.html · https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html

### 4.8 AD-38 — gRPC status codes map as described — **codes real; semantics right; HTTP mapping is a deliberate deviation**

All six named codes exist with the semantics the spine assigns:

| Spine's use | gRPC canonical meaning | Semantic fit |
|---|---|---|
| wrong state → `FAILED_PRECONDITION` | "the system is not in a state required for the operation's execution" | ✓ exact |
| one-active-ride → `ALREADY_EXISTS` | "the entity that a client attempted to create already exists" | ✓ exact |
| identity mismatch or absent → `NOT_FOUND` | "some requested entity was not found" | ✓ |
| lost race → `ABORTED` | "aborted, typically due to a concurrency issue such as a sequencer check failure" | ✓ exact — and the docs say ABORTED is the code where "clients should retry at a higher level", which is precisely AD-38's "retried internally and never surfaced" |
| malformed → `INVALID_ARGUMENT` | "the client specified an invalid argument", regardless of system state | ✓ |
| shed → `UNAVAILABLE` | "transient... can be corrected by retrying with a backoff" | ✓ |

The FAILED_PRECONDITION / ABORTED distinction — external fix needed vs. client-side retry — is the
subtle one, and the spine gets it the right way round.

**One deviation to declare, not to fix:** the canonical Google/gRPC-gateway HTTP mapping renders
`FAILED_PRECONDITION` as **400**, not 409, and `ABORTED` as 409. AD-38 maps FAILED_PRECONDITION →
409 and swallows ABORTED entirely. Both choices are defensible for this domain (a ride in the wrong
state genuinely is a conflict, not a malformed request), and AD-38 owns the mapping at the façade by
design. But since the spine's whole purpose is to prevent five services inventing five answers, the
AD should say *"this deliberately diverges from the canonical gRPC→HTTP mapping"* — otherwise the
first developer who checks the gRPC docs will assume the spine is wrong and "fix" it.

### 4.9 Other claims spot-checked

- **Coordinates `DECIMAL(10,8)` / `DECIMAL(11,8)`** — correct precision split: latitude needs 2
  integer digits (±90), longitude needs 3 (±180). ✓
- **`declined_by UUID[]` with `NOT (driver_id = ANY(declined_by))`** (AD-17) — valid PostgreSQL
  array containment; standard and index-free by design, fine at the scale AD-17 implies. ✓
- **`READ COMMITTED` as the isolation level** (Conventions) — correct as PostgreSQL's default, and
  consistent with AD-15's "correctness comes from guarded conditional updates". ✓
- **AD-5 / AD-6 HAProxy as the bounding queue** — HAProxy 3.2 supports the connection/queue limits
  and 503-on-overflow behaviour AD-6 depends on. ✓

---

## 5. Required corrections

Ordered by severity. Items 1–6 should block sign-off of the Stack table; 7–10 are quality
improvements to the AD prose.

1. **Resilience4j 2.3.x → 2.4.0** *(blocking — build break)*. 2.3.x has no `resilience4j-spring-boot4`
   artifact; only 2.4.0 does. Also re-justify AD-6/AD-34 briefly given Spring Framework 7's built-in
   retry support and the immaturity of Resilience4j's Boot 4 integration.
2. **Stripe Java SDK 29.x → 33.x** *(blocking)*. Four majors behind; v29 targets API
   `2025-03-31.basil` and receives no further updates per Stripe's published policy.
3. **Argo CD 3.2.x → 3.4.x or 3.5.x** *(blocking — EOL)*. 3.2 is outside the three-most-recent-minors
   support window and was skipped by the 2026-08-12 security batch.
4. **ClickHouse 25.x → 26.3 LTS** *(blocking — expires in 16 days)*. Only 25.8 LTS survives in the 25
   line, EOS 2026-08-29.
5. **Testcontainers 1.21.x → 2.0.5** *(blocking — BOM conflict)*. Boot 4.1 manages 2.0.5; the whole
   AD-10 testing argument rests on this dependency.
6. **Flyway 11.x → 12.4.0** *(blocking — BOM conflict)*, or 13.x with a deliberate override. Do not
   silently downgrade two majors below the managed version.
7. **Kubernetes 1.34.x → 1.36.x** *(recommended)*. 1.34 enters maintenance mode 2026-08-27 and is EOL
   2026-10-27; kind v0.32.0 already defaults to 1.36.1.
8. **Grafana 12.x → 13.1.x** *(recommended)*, or narrow the pin to 12.4 — it is the only 12 line
   still receiving fixes.
9. **AD-29** — add that identity assignment is monotonic but commit visibility is not, and that this
   is why the relay claims-and-deletes rather than tracking a high-water mark.
10. **AD-38** — state that the gRPC→HTTP mapping deliberately diverges from the canonical mapping
    (FAILED_PRECONDITION is canonically 400; ABORTED is canonically 409).

Minor, optional:
- **AD-26** — note "COUNT without ANY", since `ANY` returns unsorted results and would silently break
  nearest-driver.
- **AD-33** — name which Jackson major the unknown-properties advice applies to (Boot 4.1 manages
  Jackson 3.1.4 with a Jackson 2 fallback).
- **Stack table** — consider splitting Kafka into broker version (4.3.1) and client version (managed
  by the BOM at 4.2.1), and prefer consuming Spring gRPC through Boot 4.1's
  `spring-boot-starter-grpc-*` rather than pinning coordinates directly.
- **Redis "8.x"** — tighten to 8.10, the only 8 line with full maintenance.

---

## 6. Note on the table's closing sentence

> *"Versions verified current at authoring. Once the code exists it owns these; this table is
> cold-start seed, not a register to maintain."*

The framing is right — a spine should not become a dependency register. But "verified current at
authoring" is a claim the artifact makes about its own rigour, and six of seventeen rows do not
support it. Two of those six (Resilience4j, Testcontainers) are not merely stale but contradict the
Spring Boot version pinned three rows above them, which is the specific failure mode a cold-start
seed exists to prevent: the developer takes the table at face value and the build fails, or worse,
quietly resolves to something the architect never intended.

Recommendation: fix the six rows, then keep the sentence. It will then be true.

---

*All verdicts in this review were established from the cited live sources on 2026-08-13.*
