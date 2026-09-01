# RFC 0002: Docker-Compatible Surface

**Status:**
- proposed: 2026-09-01
- accepted: 2026-09-01 (level 1 as the shape of the CLI; levels 2 and 3 deferred indefinitely)
- rejected: -

**Proposal:** Werkdock's user-facing surface follows Docker wherever the filesystem-only contract allows: level 1 is a docker-compatible CLI (verbs, flags, exit codes), level 2 is pulling OCI images from registries, level 3 is a daemon offering the Docker Engine REST API subset that Testcontainers needs.
Level 1 is built in session B; levels 2 and 3 are designed for but deferred.

## Context and Problem Statement

The requirement (2026-09-01): the CLI — and a daemon API, if one is needed — shall be docker-compatible as far as possible, also to enable integrating Testcontainers later.

Docker compatibility is not one thing; it comes in three separable levels, and Testcontainers forces a position on each:

1. **CLI compatibility** — `werkdock run` takes the flags a docker user already knows. Cheap, pure design discipline, and it makes every docker tutorial partially applicable.
2. **Image compatibility** — a werkdock image today is a self-built rootfs archive; docker images are OCI images from registries. Pulling and flattening OCI images makes the world's images usable.
3. **API compatibility** — Testcontainers never invokes the CLI; it speaks the Docker Engine REST API over a unix socket (`DOCKER_HOST`). Podman achieves Testcontainers support exactly this way (`podman system service`). Without this level there is no Testcontainers, regardless of the CLI.

## What Testcontainers Actually Needs

From observing docker-java/testcontainers-java against real daemons:

- `/version` and `/info` handshakes; then image pull (level 2 is a prerequisite), container create/start/inspect/logs/wait/remove.
- Port mapping: create requests an exposed container port with an empty host port, inspect must answer with the mapped ephemeral host port (`NetworkSettings.Ports`).
- The Ryuk reaper container (disableable via `TESTCONTAINERS_RYUK_DISABLED=true`).

The port mapping is the crux for Werkdock: with filesystem-only isolation there is no network namespace, the payload binds host ports directly.
Two consequences:

- "Mapping" degenerates to identity — inspect reports the port the service actually bound. Workable for sequential CI use.
- Two containers wanting the same fixed port collide, exactly as with docker's `--network=host`.

The honest way out, if Testcontainers support ever becomes serious: unprivileged network namespaces are available inside a user namespace (rootless podman does networking this way, via a userspace stack — pasta/slirp4netns).
That would be a deliberate, opt-in extension of the filesystem-only contract, decided in its own RFC — not implied by this one.

## Considered Options

* Docker-compatible from the start on all three levels — rejected: level 3 without a consumer is speculation, and the Ryuk/port semantics need real Testcontainers runs to validate against.
* Own CLI idioms (`werkdock run <instance> -- <cmd>` as sketched in plan step 21), compatibility later — rejected: retrofitting docker semantics onto a shipped CLI breaks users; the compatibility must shape the surface from day one.
* Docker-compatible CLI now, API-ready architecture, levels 2 and 3 deferred — chosen.

## Concrete Proposal

### Level 1 — CLI (session B)

Verbs and flags follow docker; unsupported docker flags fail loudly with a reason, never silently no-op:

| Werkdock | Docker equivalent | Notes |
|---|---|---|
| `werkdock run [flags] IMAGE [CMD...]` | `docker run` | creates an instance from the image, runs CMD |
| `werkdock create` / `start` / `stop` / `rm` | same | instance lifecycle |
| `werkdock ps [-a]` | same | running/all instances |
| `werkdock images` / `rmi` | same | local image store |
| `werkdock load -i FILE` | `docker load` | imports a rootfs archive as an image |
| `werkdock exec INSTANCE CMD...` | `docker exec` | additional process in a running sandbox |
| `werkdock logs [-f] INSTANCE` | `docker logs` | |
| `werkdock inspect NAME` | `docker inspect` | JSON, docker-shaped where fields apply |
| `werkdock doctor` | *(none)* | host capability and quota check; `info` aliases the summary |

Supported `run` flags from the start: `-v/--volume` (bind mounts), `-e/--env`, `-w/--workdir`, `--rm`, `--name`, `-d/--detach`, `--entrypoint`.
Refused with explanation: everything that promises isolation Werkdock does not provide (`-p/--publish`, `--network`, `--memory`, `--cpus`, `--user` beyond the fixed uid-0 mapping).

Semantic shift against the step-21 sketch: `run` takes an **image** (docker semantics), not a pre-unpacked instance; instances are created per run and correspond to docker containers.
`--rm` deletes the instance tree afterwards; without it, `ps -a`/`start` see it again.

### Level 2 — OCI images (deferred, designed for)

`werkdock pull IMAGE[:TAG]` fetches from an OCI registry (Docker Hub et al.) and flattens the layers into a rootfs.
This is HTTP + JSON + tar with whiteout handling — implementable within the stdlib-only policy (RFC 0001), but a substantial work package (registry auth token dance included).
Until then, `werkdock load` and the self-built rootfs archives carry the image store.

### Level 3 — daemon API (deferred, designed for)

`werkdock daemon` serves the Docker Engine API subset from "What Testcontainers Actually Needs" on a unix socket; consumers set `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/werkdock.sock`.
Architecture consequence now: the CLI must not own the lifecycle logic — verbs are thin frontends over the same internal service the daemon would expose, and instance state lives on disk in a format both can read.
Ryuk stays disabled in documentation until proven.

## Consequences

- Plan step 21 session B and the README change their CLI sketch to the docker-shaped surface above.
- The engine interface from RFC 0001 is unaffected — compatibility shapes the surface, engines stay swappable behind it.
- Testcontainers remains a stated goal, not a claim: it is validated the day level 3 exists, and the port-collision limitation is documented until a network-namespace RFC changes it.

## Decision Outcome

Decided 2026-09-01: level 1 shapes the CLI — verbs and flags follow docker, unsupported flags fail loudly.
Levels 2 and 3 (OCI pull, daemon API, Testcontainers) are deferred indefinitely; nothing in the code may make them harder, nothing is built for them now.
The immediate goal is narrower than level 1's full verb list: `doctor`, `load`, and `run` — enough for the sandbox builds of Werkator, Werkbaum, and Werkdock itself; the remaining verbs follow with need.
