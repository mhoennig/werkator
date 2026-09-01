---
name: architecture
description: Detailed Werkator subsystem architecture — CLI wiring and exit codes, server mode, web UI, configuration system, git access, build execution (native, Docker, and bwrap), watcher poll cycle, and system metrics. Use when designing or modifying code in the commands, config, git, gitea, build, artifacts, watcher, metrics, or server packages, or when a question goes beyond the overview in AGENTS.md.
---

# Werkator Architecture

Werkator is a lightweight, declarative CI/CD build system.
It is a dual-mode application: **CLI** (interactive, status, config) and **Server** (HTTP, persistent).

## Entry Point and CLI Wiring

Spring Boot starts via `WerkatorApplication`. A separate `CliRunner` component (in the same file) implements both `CommandLineRunner` (runs picocli) and `ExitCodeGenerator` (returns the exit code). `exitProcess` is called only from `main()` via `SpringApplication.exit()` — **never** inside `run()`. This keeps the Spring context alive during tests.

Picocli commands are Spring `@Component` beans. The root command (`WerkatorCommand`) declares subcommands as class references in `@Command(subcommands = [...])`. Picocli resolves them from the Spring context via the auto-configured `IFactory` bean.

```
WerkatorApplication   ← @SpringBootApplication
CliRunner             ← CommandLineRunner + ExitCodeGenerator
WerkatorCommand       ← root @Command, delegates to subcommands
commands/
  InitCommand         ← "init [--systemd]"
  ServerCommand       ← "server"
  StatusCommand       ← "status [--history]"
  BuildCommand        ← "build [<branch>]"
  RetryCommand        ← "retry"
  ConfigPrintCommand  ← "config:print [--full]"
```

`status`, `build`, and `retry` implement `Callable<Int>` for their exit codes (0 success, 1 build failure, 2 usage/config errors).
`build` and `retry` run builds through the async `BuildExecutor` but block until completion via `ConsoleBuildRunner`, which streams the live log to stdout and waits for the artifact persist before the JVM exits.
Branch arguments resolve legacy-style name fragments (`BranchNameResolution`); the CLI reuses `UiFormats` so console and web UI display the same formats.

The web application type is set to `none` in `application.yml`, so plain CLI runs never start a web server. The `server` subcommand launches a **second** `SpringApplication` with `WebApplicationType.SERVLET` and the `server` profile, then blocks until shutdown. `application-server.yml` switches the web type (`spring.main.*` properties beat programmatic builder settings), `CliRunner` is `@Profile("!server")` so the second context does not run picocli again, and the watcher poll loop starts only in the `server` profile (`ServerWatcherLifecycle`). The JSON API, artifact serving, and the web UI live in the `server` package; mutating endpoints are guarded by a generated control token under `.git/werkator/control-token`.

## Web UI

The UI is server-rendered Thymeleaf (`UiController`, templates under `src/main/resources/templates/`) plus one hand-written JavaScript file (`static/werkator.js`) — no SPA framework, no frontend build pipeline. Pages render the full state server-side; the script then polls the JSON API and re-renders table bodies from data. Every fetch has a timeout and failures flip an explicit error badge — never re-fetch and diff whole HTML pages, and never leave a spinner without an error path (the legacy defect). Polling pauses while the tab is hidden. `UiFormats`/`werkator.js` must produce the same display formats (timestamps, durations).
Two independent staleness signals, never merged: the `live-indicator` badge says whether *this browser* reaches the server, and the `watcher-banner` (fed from `/api/watcher`, in the shared `nav` fragment) says whether the *server* reaches origin — a watcher that cannot fetch leaves the server perfectly reachable and every row stale.

## Configuration System

Werkator is configured by two YAML files, deep-merged by `ConfigLoader` (later wins):

1. `.werkator.yml` at the repo root — committed, shared team settings.
2. `.git/werkator/.werkator.yml` — not committed; machine-specific overrides and secrets (`git.account`, `git.token`).

Every lookup falls back to the pre-rename name (`ConfigFiles`): `.gittally.yml`, and `.git/gittally/.gittally.yml` for the machine layer. Current name first, and where both exist the old one is ignored rather than merged — a missing config is not an error, so an un-renamed installation would otherwise start on defaults without a single failure.

On top of those comes the **branch layer**: the `.werkator.yml` committed on a branch, applied by `loadWithBranchLayer` (the watcher passes the content read via `git show`, `loadForWorktree` the file in the build worktree). A branch describes its own CI and wins over both layers — the whole `builds` section — so a configuration can be tried out on a branch without touching other branches' builds. `stripPinned` removes what is not a description of this branch's build: `git`, `server`, `gitea`, `executor`, `watcher`, and — inside every `builds` definition as well as every legacy `branches` entry — `requirePullRequest`, `statusContext`, and `docker.enabled`/`docker.network`.

Each file is version-checked before merging (`werkator.version.since`/`below`, `ConfigVersions.verdict`), so the message can name the file to fix: `since` is hard in both directions — too old a Werkator, or a file written before `ConfigVersions.FORMAT_BROKE_IN` and read after it — while `below` only warns. There is no format version (`apiVersion`) on purpose: only one configuration generation is supported, and the declared version exists to make the incompatibility nameable.

After merging, `resolveBuildSections` decides which section describes the builds: `builds` or the legacy `branches`, never both. With no real build definition (`builds.maxConcurrent` is not one, `dropNonDefinitionBuilds` already drops it) the legacy path runs and `branches.default` is merged into every other branch entry; otherwise `branches` is dropped with a warning and `mergeBuildDefaults` applies `builds.default` as the base of every other definition — its settings only, never its `trigger` block (`TRIGGER_KEYS`, a single key so that a selector added to `TriggerConfig` later is non-inheritable by construction). `checkTriggerBlocks` refuses a definition still writing `onPush`/`atTimes`/`branches`/`activeWithin` flat, per file and scoped like the version check. Deciding this on the merged map is deliberate: a build defined on a branch and unknown to the host still inherits the host's `builds.default`, sandbox policy included, which is what keeps the pinned keys effective for it. The result is bound to the `WerkatorConfig` data classes (`config/WerkatorConfig.kt`), which define the schema and all defaults; `WerkatorConfig.buildSettings(branch, build)` is the single answer to "what does this build run".

Three places must stay in sync when config keys change: the `WerkatorConfig` data classes, the commented templates generated by `InitCommand`, and the reference in `docs/configuration.md`.

## Git Access

`GitService` shells out to the `git` CLI via `GitCommandRunner` (a thin `ProcessBuilder` wrapper; no JGit). Commands that need repo information take it as a constructor dependency so tests can mock it. HTTPS fetches authenticate via a temporary, secret-free `GIT_ASKPASS` script (`GitAskPass`) with credentials from config passed through environment variables.

## Build Execution

`BuildExecutor` runs builds asynchronously: up to `executor.maxConcurrent` branches concurrently (default 1), but never more than one build per branch at a time. Each branch builds in its own reusable git worktree at `.git/werkator/worktrees/<branchKey>` (`BranchWorkspaces`), checked out detached at the requested commit — the primary checkout is never used for builds. Status transitions are persisted via `BuildResultRepository` (JSON file under `.git/werkator/`), published to Gitea non-fatally, and emitted as `BuildStatusChangedEvent`s. Every run belongs to a named build definition (job, ADR 0007): the YAML `builds` section defines triggers (`onPush`, `atTimes`), branch selectors (`branches` globs, `activeWithin`), and build-setting overrides applied last over the merged branch config; the implicit `default` build (`onPush`, all branches) preserves the job-less behavior. Definitions are part of the branch layer — a branch may add and override its own, and they apply to that branch alone (its selectors are evaluated for it only) — while `executor.maxConcurrent` stays pinned. `BuildResult.build` records the job; restart, retry, and startup recovery re-run by that name, resolving settings from the *current* config. `BuildResult.name` — the pool, `<branch>@<build>` for non-default builds — keys everything display- and retention-side (repository grouping via `latestPerName`, retention pools, branches-view rows, permanent latest-green links), while `BuildResult.branch` keys everything git-side: origin lookups, gone-from-origin pruning, worktrees (every build runs in its branch's worktree, serialized per branch), and Gitea links/statuses. `branches.*.autoBuild` survives as a deprecated alias for a scheduled default-pool rebuild. Cancellation addresses a build by artifact key and terminates the whole process tree. Future code (watcher, server, UI) must not assume a single running build.

On context close (e.g. systemd SIGTERM), a `ContextClosedEvent` listener in `BuildExecutor` terminates the process trees of all executing builds and waits (bounded) until their results are persisted as INTERRUPTED — a shutdown is never recorded as FAILED. Builds still queued stay PENDING and start no process. Both are re-enqueued by the watcher's startup recovery; INTERRUPTED therefore publishes as Gitea state `pending`, not `failure` (`GiteaStateMapping`).

The runtime is selected per build behind the `BuildRunner` interface: `DispatchingBuildRunner` (`@Primary`) routes to native `ProcessBuildRunner` (the default), to `DockerBuildRunner` when `docker.enabled`, or to `BwrapBuildRunner` when `bwrap.enabled` — docker and bwrap are mutually exclusive per build and rejected in `buildSettings`, never picked silently. The Docker runner shells out to the `docker` CLI (no SDK): it (re)builds the configured image when the Dockerfile inputs changed (tracked via the `org.werkator.build-inputs-sha256` image label), maintains a per-repo Gradle cache volume, mounts the worktree and the Docker socket into a labelled (`org.hoennig.werkator`) `--rm --init` container, and repairs workspace ownership in-container after each command (under a rootless daemon the container runs as root, which is the host user, and the repair degenerates to `0:0`). Git works inside the container: the primary `.git` is mounted read-only with `.git/werkator/` masked by an empty tmpfs (credential isolation) and the worktree's admin dir mounted read-write (`gitMetadataMounts`). The returned `Process` is the attached `docker run` client, so log streaming and termination work exactly like native builds.

`BwrapBuildRunner` (ADR 0008) is the third runtime, for hosts without root and without Docker — Hostsharing Managed Webspaces. It shells out to the `bwrap` CLI (no library): a prepared rootfs archive (`bwrap.rootfs`, built by `tools/build-bwrap-rootfs.sh`) is unpacked on demand into `.git/werkator/buildenv/<envKey>/rootfs` and bound read-only at `/`, with uid 0 inside mapped to the calling user; isolation is filesystem-only — network, uid, `/proc`, `/dev` are the host's by contract. It reuses the Docker runner's `gitMetadataMounts`; mount order matters (repo dir read-write before the metadata mounts and the workspace), and bind mountpoints missing from the rootfs are pre-created there, since the rootfs is a plain host directory while bwrap cannot mkdir against the read-only sandbox root. `bwrap.enabled`/`bwrap.rootfs` are pinned like the docker sandbox policy. The returned `Process` is the attached `bwrap` process, so streaming and cancellation are unchanged. Plan step 21 will extract the generic sandbox machinery into the standalone tool Werkdock (grown in `werkdock/`); the runner then delegates to the `werkdock` CLI.

## Watcher

`Watcher` replaces the legacy blocking main loop with a non-blocking fixed-delay poll cycle: fetch origin, enqueue due branches (changed local, recent new origin, due auto-build slots) via `BuildExecutor`, then prune results, artifacts, and stale worktrees. Branches whose build has `requirePullRequest` are enqueued only while their head commit matches a pull-request head, detected without an API token by listing `refs/pull/*/head` via `git ls-remote` (lazily, at most once per poll cycle); manual `build` commands bypass this gate, and `watcher.pullRequestGate: false` disables it globally for plain-git origins without pull-request refs. Which builds are due is decided per branch from that branch's own definitions (`definitionsFor`): the primary config with the branch's committed `.werkator.yml` merged on top, cached per branch by its head commit *and* the primary config it was merged with, so the `git show` runs only when the branch moved while an edited machine or project config still takes effect on the next poll, and falling back to the primary definitions when that config is unreadable. Nothing is scheduled until `Watcher.start()` is called explicitly (server/watch mode) — CLI commands and tests never start the loop. "Already built" is tracked via the result repository, not by moving local branch refs.
After the enqueue decision — and only after it, because a local ref lagging behind origin *is* the change signal — the cycle fast-forwards the primary checkout's local branch refs to their origin counterparts (`watcher.fastForwardLocalRefs`, `GitService.fastForwardLocalBranches`), so build tools reading the shared `.git` from a worktree see the refs they expect; diverged or ahead branches are never touched. Auto-build slot state lives in `.git/werkator/auto-builds.json`; watcher health is exposed via `Watcher.state()`.

## System Metrics

`SystemMetricsCollector` samples CPU (`/proc/stat` deltas), RAM (`/proc/meminfo`), disk, and repository size every 60s, but only after `ServerMetricsLifecycle` (server profile) calls `start()` — like the watcher, nothing is scheduled in CLI runs or tests. Min/max/avg aggregation state persists as JSON in the artifact root (`ArtifactStore.rootDir()`), so restarts continue the series. Unavailable sources (e.g. no `/proc` outside Linux) yield null metrics served as HTTP 200 by `GET /api/system` — the `/system` page shows `n/a`, never an error.
