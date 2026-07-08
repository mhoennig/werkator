# GitTally Rewrite Plan

This directory contains the step-by-step plan for rewriting `legacy/gitTally` (bash) as the Kotlin/Spring application in this repository.
Each step file is self-contained and sized for one focused Claude Code session.

## How to Execute a Step

Start a fresh Claude Code session and prompt, for example: "Execute docs/plan/01-build-state-domain.md".
The executing session should:

1. Read this file, `00-legacy-analysis.md`, and the step file.
2. Read the referenced parts of `legacy/gitTally` only if the step file says so.
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
- Config keys added by a step must be updated in three places: `GitTallyConfig`, the `InitCommand` templates, and `docs/configuration.md`.

## Proposed Architecture Decisions

These are proposals baked into the steps.
Revisit them in an ADR if a step uncovers problems.

- Build results are persisted as a JSON file under `.git/gittally/`, behind a `BuildResultRepository` interface (no database, but replaceable).
- Builds run concurrently up to `builds.maxConcurrent` (default 1), but never more than one build per branch at a time.
  Each branch builds in its own reusable git worktree under `.git/gittally/worktrees/`, checked out detached at the requested commit — never in the primary checkout.
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

Steps 01–03 are independent of each other.
Steps 04–06 depend on 01–03.
Steps 07–09 depend on 04–06.
Steps 11 and 12 are optional/deferrable; 10 only needs 04–06.
Step 13 depends on 07, 11, and 12.
