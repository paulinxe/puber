# puber

A ride-hailing backend, built as a set of small services. Right now it is one service and the
scaffolding around it.

## What you need

Docker, with Compose v2. That is all.

You do not need Java, Gradle, or Postgres installed. Every command runs inside a container, including
the build, the tests, the code checks, and the git hooks. If something only works because you happen
to have Java on your machine, that is a bug.

## Getting it running

```sh
make build   # build everything
make run     # start the stack
make test    # run all the tests
make stop    # stop the stack
make format  # reformat the code
```

`make help` lists the rest.

Once it is up:

- http://localhost:8080/actuator/health — should say `UP`
- http://localhost:8080/actuator/prometheus — metrics, in the format Prometheus reads

`make run` waits until the service really answers, so you can call it straight away without waiting
or retrying.

`make test` starts the database it needs on its own. You do not have to run `make run` first.

## What is in here

| Folder | What it holds |
| --- | --- |
| `services/` | One folder per service. Each builds on its own. |
| `infra/` | The Docker Compose stack you develop and test against. |
| `deploy/` | Kubernetes files. Empty for now; it gets filled in later. |
| `static-analyzers/` | The code-check settings, kept in one place and copied into each service at build time. |
| `.githooks/` | The git hooks (see below). |
| `_bmad-output/` | Planning and story documents. Not code. |

There is no build file at the root, on purpose. Each service has its own Gradle wrapper and builds as
if it lived in its own repository. The `Makefile` calls each one; it does not join them into a single
big build. This keeps the services from getting tangled up in each other as more are added.

## The service: `matching-service`

Java 25, Spring Boot 4.1, Gradle 9.5. It has its own Postgres 18.6 that nothing else touches.

So far it does not do any ride matching. What it does have is the base every later feature needs:

- it starts in Docker and reports whether it is healthy
- it only reports healthy once its database is reachable, and reports unhealthy quickly when it is
  not, so it can be used by a real health check later
- it serves metrics
- its database schema is versioned, so migrations can be added one at a time from here on
- its package structure is checked by tests, so the layers cannot quietly start depending on each
  other the wrong way round

The container does not run as root, and neither do the build and test containers. Those two run as
your own user, so nothing they write into the folder ends up owned by root and stuck.

## Tests

Tests run in a container that joins the Compose network and talks to the real Postgres.

There is no in-memory database, no fake database, and no test library that starts its own containers.
The reason is simple: things like row locking and transaction behaviour are Postgres behaviour. If the
tests ran against a stand-in, later claims like "two drivers can never be given the same ride" would
only be true about the stand-in.

Tests run one at a time against one shared database. That is deliberate. A test that fails now and
then looks exactly like a real bug, and the tests that matter most here are about two things happening
at once.

Two commands, because the hooks need them apart:

```sh
make test-unit          # fast, no database
make test-integration   # against the real stack
```

`make test` is just both of those.

## Git hooks

`make build` installs them. The scripts live in `.githooks/` and are committed, so a fresh clone plus
one build puts them back.

**`pre-commit`** runs the code checks for whichever services you touched. No tests. It is meant to be
quick enough that you never want to skip it. If it fails, the commit does not happen.

Most failures are formatting. Run `make format` to fix them, then **`git add` the result** — the hook
checks what you have staged, not what is on disk, so fixing the files without staging them changes
nothing and the next commit fails the same way.

**`pre-push`** runs everything: all tests for all services, including the ones against the database,
no matter what you changed. If it fails, the push does not happen.

There is no CI server, and that is a decision rather than something we forgot. `pre-push` is the last
check before code is shared, so it is the one that has to hold. Two things follow from that. A broken
commit can sit in your local history and only show up when you push. And if `pre-push` ever gets too
slow to live with, the fix is faster tests, not moving the check somewhere easier.

You can skip a hook with `--no-verify`. Please do not. A check people skip is worse than no check,
because it still reports success.

## Rules worth reading before you write code

- **[`project-context.md`](project-context.md)** — how things are built and why. Package layout, the
  build and test commands, the hook policy, the container rules, and a list of things in Spring Boot
  4.1 and Postgres 18 that behave differently from what most guides online still say. That last part
  will save you time.
- **[`AGENTS.md`](AGENTS.md)** — coding style. SOLID, immutable domain objects, no magic numbers, how
  to name things, and how to write comments.

The two files do not repeat each other, so there is nothing to keep in sync.

## Where we are

Epic 1, first story done. One service, the build, the stack, the tests and the hooks.

Still to come: fare quotes, drivers and their locations, the ride flow itself, an event backbone,
payments, an audit trail, and running the whole thing on Kubernetes. The other services
(`rider-service`, `driver-service`, `payment-service`, `audit-service`) arrive with the stories that
need them.
