# `contracts/`

The cross-service contracts, in one versioned place.

## Where it goes

Nothing in here is compiled where it sits. `make` copies `contracts/proto/` into
`services/<svc>/build/contracts/proto/` before that service's Gradle wrapper runs, and the protobuf
plugin generates from the copy. So every service gets its **own** generated stubs and no service
depends on another's code — the only shared artefact is the text in this directory.

Three files have to agree for that copy to survive into an image, and a mismatch fails somewhere
that looks unrelated: the `Makefile` target that copies, the service's `.dockerignore`
(`!build/contracts/`), and its `Dockerfile` (`COPY build/contracts build/contracts`).

**Never hand-edit a copy under `build/`.** It is regenerated on the next `make`.

## Editing a contract

AD-52 makes this directory the single source; AD-33 makes every edit additive. Read both before
changing anything here — they are deliberately not restated. Both live in
`_bmad-output/planning-artifacts/architecture/architecture-puber-2026-08-03/ARCHITECTURE-SPINE.md`
(the dated directory moves with each architecture revision; search for `AD-52` if this path is stale).

The one mechanical consequence worth having in front of you while you type: **field numbers are
never reused.** Deleting a field means `reserved`-ing its number, not freeing it.

## Who serves what

The path organises contracts by domain, not by owning service, so it does not tell you who to call.
This table does. Ownership can change; a proto package cannot — it is the gRPC wire name, and
renaming it is a breaking change under AD-33.

| Contract | Served by | Called by |
| --- | --- | --- |
| `puber/quote/v1/quote.proto` | `matching-service` (AD-3) | `rider-service` |
