# Werkator Rewrite Plan

This directory contains the step-by-step plan for rewriting the legacy bash script as the Kotlin/Spring application in this repository.
Each step file is self-contained and sized for one focused Claude Code session.

## How to Execute a Step

Start a fresh Claude Code session and prompt, for example: "Execute docs/plan/01-build-state-domain.md".
The executing session should:

1. Read this file, `00-legacy-analysis.md`, and the step file.
2. Read the referenced parts of the legacy bash script only if the step file says so.
   It was removed from the tree with the rename to Werkator; its last state is `git show 7f55068^:legacy/gitTally`, and `00-legacy-analysis.md` condenses it.
3. Implement with tests, following `CLAUDE.md` conventions.
4. Run `./gradlew ktlintFormat` and then `./gradlew build` until green.
5. Update the step's checkbox below and note deviations inside the step file.

## Guiding Principles

- The legacy script defines intended behavior, but it is buggy — treat it as a reference, not a spec.
- Fix the two known legacy defects by design, not by patching:
  - Build status must be observable while a build runs (event-driven status transitions, async build execution).
  - The web UI must never get stuck loading (JSON status endpoints with explicit error states instead of regex-rewritten HTML).
- Do not port orphaned or half-implemented legacy config options (see `00-legacy-analysis.md`).
- Every step leaves the build green and the application runnable.
- Config keys added by a step must be updated in three places: `WerkatorConfig`, the `InitCommand` templates, and `docs/configuration.md`.

## Proposed Architecture Decisions

These are proposals baked into the steps.
Revisit them in an ADR if a step uncovers problems.

- Build results are persisted as a JSON file under `.git/werkator/`, behind a `BuildResultRepository` interface (no database, but replaceable).
- Builds run concurrently up to `builds.maxConcurrent` (default 1), but never more than one build per branch at a time.
  Each branch builds in its own reusable git worktree under `.git/werkator/worktrees/`, checked out detached at the requested commit — never in the primary checkout.
  A later step must decide (possibly per config) whether a new commit on a branch cancels that branch's running build or waits for it; for now new builds queue behind the running one.
- Artifacts stay on the filesystem, served by the Spring server.
- The web UI is server-rendered HTML plus small JavaScript polling JSON endpoints (no SPA framework).
- The watcher is a Spring-managed scheduled component, decoupled from the build executor via the result repository and events.
- nginx/Let's Encrypt container management is NOT ported; deployment behind an existing reverse proxy is documented instead.
  Revised after step 12 (ADR 0005): an opt-in managed nginx/TLS container is required for Hostsharing container hosts — see step 13.

## Steps

Foundation:

- [x] `01-build-state-domain.md` — build result domain model and persistent repository
- [x] `02-git-gateway.md` — full git access layer (fetch, branches, commits, checkout)
- [x] `03-gitea-client.md` — Gitea API client for commit statuses

Core engine:

- [x] `04-build-executor.md` — async build execution with logs, cancellation, status transitions
- [x] `05-artifact-store.md` — artifact persistence, naming, retention
- [x] `06-watcher.md` — branch watching, scheduling, auto-builds

Server and UI:

- [x] `07-server-mode.md` — `server` subcommand, REST/JSON endpoints, artifact serving
- [x] `08-web-ui.md` — HTML views with robust live updates
- [x] `09-system-metrics.md` — system resource monitoring page

Completion:

- [x] `10-cli-commands.md` — CLI build/status commands
- [x] `11-docker-build-runtime.md` — optional Docker build execution
- [x] `12-deployment.md` — systemd service, migration from legacy, docs

Added after the initial plan (ADR 0005):

- [x] `13-nginx-tls.md` — opt-in managed nginx/TLS container for hosts without a reverse proxy

Added after the 2026-08-10 overhead measurements on vm2176:

- [ ] `14-build-phase-timing-and-overhead.md` — per-build phase timing, overhead budget warning, ownership/metrics fixes — deferred until after step 15; revisit relevance on vm4006

Added for the vm2176 → vm4006 migration (2026-08-10):

- [x] `15-runtime-bundle-distribution.md` — self-contained runtime bundle (jlink JRE + jar) for hosts without a Java runtime
- [x] `16-git-in-docker-builds.md` — read-only git metadata inside Docker build containers, with `.git/werkator/` masked

Added after v0.9.19 replaced the per-branch settings with build definitions (2026-08-29):

- [ ] `18-remove-branches-section.md` — delete the legacy `branches` section and its `autoBuild` schedule; run around 2026-09-05, after the precondition check in the step file

Added after a silent 57-minute fetch outage on vm4006 (2026-08-30):

- [x] `19-watcher-health-in-ui.md` — show an unreachable origin in the web UI instead of only in the journal

Added for running Werkator on Hostsharing Managed Webspaces (2026-08-10):

- [ ] `17-bwrap-build-runtime.md` — Werkator on a Managed Webspace: bubblewrap user-namespace build sandbox with a prepared rootfs (precondition check first — see the step file), plus web access under a domain via the platform's Apache proxy and Let's Encrypt

Added to correct the bwrap prototype's drift toward self-building on the webspace (2026-09-01):

- [ ] `21-werkdock-extraction-and-webspace-install.md` — roadmap in four sessions: close step 17's open ends, grow the sandbox tooling into **Werkdock** (a docker-like filesystem-only sandbox CLI, developed in the `werkdock/` subdirectory, extracted into [its own repository](https://git.javagil.de/mi/werkdock) in session E), let Werkator consume it, and replace the webspace self-build with the local-build-plus-install path of ADR 0006

Added after step 21 session D exposed that `tools/remote` re-implements configuration Werkator owns (2026-09-01):

- [ ] `23-init-owns-the-files.md` — Werkator becomes the executing app, `tools/remote` a thin wrapper: the wrapper takes a transport env file (`remote --env .env.mih34 werkator …`), init takes a YAML fragment in the real config schema (`werkator init --apply mih34.yml`, deep-merged idempotently — no mapping table, no heredocs), `werkator control-token` and `werkdock doctor` replace the bash duplications

Added for surfacing build time as a trend (2026-08-31):

- [ ] `20-build-duration-tracking.md` — a per-name duration trend over the existing history, derived on read in the History view: series, window average/min/max, and a visible marker when the latest build is slower than its window average (grouped by the history's own `name`, so branch builds and named jobs stay separate — complements Step 14, which owns phase timing)

Steps 01–03 are independent of each other.
Steps 04–06 depend on 01–03.
Steps 07–09 depend on 04–06.
Steps 11 and 12 are optional/deferrable; 10 only needs 04–06.
Step 13 depends on 07, 11, and 12.
Step 15 depends on 12 and 13 and revises the containerized-runtime sketch in `docs/bootstrapping.md` (ADR 0006 is written as part of the step; GraalVM native image was evaluated and rejected there).
Step 17 depends on 11, 15, and 16, and starts with a hard precondition check on the target webspace (ADR 0008 is written as part of the step; the number 0007 announced in the step file was already taken).
Step 18 depends on nothing in code but on the watched repository having migrated — its precondition check is a hard gate, not a formality.
Step 19 depends on nothing; `WatcherState` and `/api/watcher` already carry everything it needs to render.
Step 20 depends on nothing; the duration is already recorded, and the trend is derived read-only from `repository.history()`.
Step 21 depends on 17; its sessions B and C grow Werkdock in the `werkdock/` subdirectory, session D supersedes the self-build prototype in `tools/remote`, and session E moves Werkdock into its own repository.
Step 23 depends on 21 session D; its per-instance file convention (transport env + init fragment) also feeds step 22's instance setup and should land before Werkbaum rolls out.
