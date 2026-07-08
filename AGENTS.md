# GitTally — Agent Instructions

This file holds the shared, tool-agnostic instructions for all AI coding agents.
Claude Code imports it from `CLAUDE.md` via `@AGENTS.md`; Claude-Code-specific instructions belong in `CLAUDE.md`, everything else here.
Detailed guides live as Agent Skills under `.claude/skills/` ([SKILL.md format](https://agentskills.io)); they load on demand — see [Skills](#skills).

## Build and Test Commands

```bash
./gradlew build          # compile + ktlintCheck + test
./gradlew ktlintFormat   # auto-format before committing
./gradlew test           # run all tests (can be slow, prefer single test)
./gradlew test --tests "de.hoennig.gittally.ApplicationContextTest"  # example for running a single test class
```

Run the JAR directly:

```bash
java -jar build/libs/gittally.jar --help
java -jar build/libs/gittally.jar init
```

`ktlintFormat` must be run before `build` passes — the formatter is enforced as part of the `check` lifecycle.

## Architecture Overview

GitTally is a lightweight, declarative CI/CD build system: git-centric, one instance per repository, builds native or in Docker, statuses reported to Gitea.
It is a dual-mode application: **CLI** (interactive, status, config) and **Server** (HTTP, persistent web UI + JSON API).
IMPORTANT: Before designing or modifying code in any production package, load the [architecture skill](.claude/skills/architecture/SKILL.md) — it holds the subsystem details (CLI wiring, server mode, web UI, config system, git access, build execution, watcher, metrics).

### Package Structure

All production code lives under `de.hoennig.gittally`, with sub-packages `commands` (picocli subcommands), `config` (YAML config loading and schema), `git` (git CLI access), `gitea` (Gitea commit-status API client), `build` (build execution, results, workspaces), `artifacts` (filesystem artifact store), `watcher` (branch polling, auto-builds, startup recovery), `metrics` (system resource sampling and aggregation), and `server` (JSON API controllers, Thymeleaf UI, artifact serving, control token, watcher and metrics lifecycles). Tests mirror this structure under `src/test/kotlin`.

### Hard Invariants

- `exitProcess` is called only from `main()` — never inside `CliRunner.run()`; this keeps the Spring context alive during tests.
- Nothing is scheduled during CLI runs or tests: the watcher poll loop and metrics sampling start only via an explicit `start()` in the `server` profile.
- Builds run detached in worktrees under `.git/gittally/worktrees/<branchKey>`; the primary checkout is never used for builds; never assume a single running build.
- When config keys change, three places must stay in sync: the `GitTallyConfig` data classes, the `InitCommand` templates, and `docs/configuration.md`.
- Web UI: server-rendered Thymeleaf plus one hand-written `static/gittally.js` — no SPA framework, no frontend build pipeline; every fetch has a timeout and an explicit error badge; `UiFormats` and `gittally.js` must produce identical display formats.
- Git and Docker access shells out to the CLIs (`GitCommandRunner`, `docker`) — no JGit, no Docker SDK.

## Testing

Tests use Kotest `FunSpec` with MockK; `SpringExtension` is registered globally in `io.kotest.provided.ProjectConfig` — do not add it per-spec.
IMPORTANT: Before writing or changing tests, load the [writing-tests skill](.claude/skills/writing-tests/SKILL.md) — it holds the spec structure and the Spring-slice mocking patterns.

## File-Formatting

### Markdown

Write documentation in English in Markdown files.
In Markdown, use a single line per sentence.
Keep sentences short.

## Documentation

- `docs/GitTally-Konzept.md` — product concept and target architecture (in German): git-centric CI, builds in Docker, one instance per repository, status reported back to Gitea.
- `docs/configuration.md` — configuration reference; keep in sync with `GitTallyConfig` and the `init` templates.
- `docs/bootstrapping.md` — how `init` prepares a repository.
- `docs/deployment.md` — running GitTally as a systemd user service behind an existing reverse proxy (`init --systemd` generates the unit).
- `docs/migration-from-legacy.md` — legacy env vars → YAML keys mapping and the manual migration steps; `legacy/gitTally` is deprecated.
- `docs/plan/` — the step-by-step rewrite plan; `docs/plan/README.md` explains how to execute a step, `docs/plan/00-legacy-analysis.md` summarizes the legacy bash script.
- `docs/prs/` — one document per pull request; every PR needs one. IMPORTANT: Before opening or finishing a pull request, load the [pr-doc skill](.claude/skills/pr-doc/SKILL.md) and write the PR-doc.

## Key Architectural Decisions

All major decisions are in `docs/adrs/`. Run `adr-status` (after `source .envrc`) for a one-line summary of each. Decisions in force:

- **Test framework**: Kotest + MockK + WireMock + Testcontainers (ADR 0001)
- **Gradle**: 8.14.5 (ADR 0002)
- **Spring Boot**: 4.0.6 (ADR 0003)
- **Rewrite architecture**: JSON-file persistence behind a repository interface, server-rendered UI with JSON polling, no managed nginx — systemd unit behind the host's reverse proxy (ADR 0004)
- **Managed nginx/TLS**: revises ADR 0004 — an opt-in nginx+certbot container for hosts without a reverse proxy (e.g. Hostsharing), planned as `docs/plan/13-nginx-tls.md` (ADR 0005)

## Skills

On-demand guides in the cross-tool [SKILL.md format](https://agentskills.io); agents with skill support load them automatically by description, all others should read the linked files when the topic comes up:

- [architecture](.claude/skills/architecture/SKILL.md) — subsystem details: CLI wiring, server mode, web UI, config, git access, build execution, watcher, metrics.
- [writing-tests](.claude/skills/writing-tests/SKILL.md) — Kotest/MockK conventions and Spring slice-test mocking patterns.
- [pr-doc](.claude/skills/pr-doc/SKILL.md) — how to write the mandatory per-PR documentation in `docs/prs/`.
