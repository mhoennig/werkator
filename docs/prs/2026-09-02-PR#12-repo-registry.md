> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

PR #11 gave every repository-scoped code path an explicit `RepoContext`, but exactly one exists: the current working directory.
ADR 0009 wants one instance to serve a *set* of repositories from a registry in the instance configuration, with the instance-level settings (server, global concurrency, poll interval) owned by that file and optional defaults shared by every repository — and the watcher must poll every repository in a way that one unreachable origin cannot starve or silence the others.
Step 22 session C is that: the registry, N contexts, the multiplexed watcher, and a `--repo` selector for the CLI.

## Non-Goals

- Repository-scoped routes, API paths, and UI grouping (session D): the controllers still serve the current repository only.
- The rollout on mih34 with Werkbaum and the deployment documentation of the registry (session E).
- A per-repository concurrency cap below the global one, and round-robin fairness across repositories (decided FIFO, see The Solution).

## The Scenarios

### Feature: the instance configuration `~/.werkator.yml`

#### Background

- The file lives in the home directory of the user running Werkator — one instance per OS user; `WERKATOR_HOME` overrides the directory.
- It carries the registry (`repositories`), the instance-level keys (`server`, `executor.maxConcurrent`, `watcher.pollInterval`), and an optional `defaults` block in the repository config schema.

#### Scenario#12.01: The defaults block sits below every repository's own layers

So that one `git.account`/`git.token` or one `gitea.baseUrl` can be written once for every repository of the same forge, while a repository's own value always wins.

- **Given** a home config with `defaults`
- **When** a repository's effective config is loaded
- **Then** every key the repository's layers do not set comes from `defaults`, and every key they do set stays the repository's.

##### Verified by

- [the home config carries the registry, and its defaults sit below every repository layer](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)

#### Scenario#12.02: Instance-level keys come from the home file alone

So that a `server.port` left in a repository's machine config can never silently compete with the instance's.

- **Given** a home config and a repository file still carrying `server`, `executor`, or `watcher.pollInterval`
- **When** the repository's effective config is loaded
- **Then** the whole `server` section, `executor` and `watcher.pollInterval` are the home file's
  - **and** the repository's copies are dropped with one warning naming both files
  - **and** the other `watcher` keys (the gates) stay the repository's.

##### Verified by

- [with a home config the instance keys come from it alone, and a repository's copies are ignored](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)
- [the home config is version-checked like every other file](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)

### Feature: the registry

#### Scenario#12.03: Every entry becomes a context, the start fails loudly on what cannot be served

So that an instance serving the wrong set never comes up looking healthy.

- **Given** a home config with `repositories`
- **When** the registry is opened
- **Then** each entry yields a `RepoContext` named after its directory unless the entry names it
  - **and** an entry that is not a git repository or two entries resolving to the same name abort the start with a message naming the home file
  - **and** a repository whose configuration this Werkator must not read is skipped with an error while the others are served
  - **and** without a home config the registry is the current directory alone, exactly as before.

##### Verified by

- [RepoRegistryTest](../../src/test/kotlin/de/hoennig/werkator/repo/RepoRegistryTest.kt) (all five tests)

### Feature: the watcher over N repositories

#### Scenario#12.04: One repository's failure neither stops nor silences the others

So that a wrong token in one repository cannot stall the builds of every other one.

- **Given** two registered repositories, one with an unreachable origin
- **When** a poll cycle runs
- **Then** the other repository's due branches are enqueued
  - **and** the state reports the failure under the failing repository's name, and per repository in `repositories`
  - **and** a repository whose poll crashes reports it the same way and the cycle continues.

##### Verified by

- [one repository's unreachable origin neither stops nor silences the other](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)
- [a repository whose poll crashes reports it by name and the cycle goes on](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)
- the existing single-repository `WatcherTest` tests, proving the top-level state fields read as before

### Feature: the CLI selects a repository

#### Scenario#12.05: `--repo <name>` selects a registered repository; without it a command means the current directory

So that `werkator status` inside a repository behaves as it always did, and a registered repository can be addressed from anywhere.

- **Given** a registry with two repositories
- **When** `werkator status --repo second` runs
- **Then** the second repository's results are printed
  - **and** an unknown name is a usage error (exit code 2) naming the registered repositories.

##### Verified by

- [--repo selects a registered repository; an unknown name is a usage error naming the registered ones](../../src/test/kotlin/de/hoennig/werkator/commands/StatusCommandTest.kt)
- [BuildCommandTest](../../src/test/kotlin/de/hoennig/werkator/commands/BuildCommandTest.kt), [RetryCommandTest](../../src/test/kotlin/de/hoennig/werkator/commands/RetryCommandTest.kt) (the default: the registry's current repository)

## The Solution

`InstanceConfig` binds the home file; `ConfigLoader` gained `homeDir`, `instanceFile()`, and `loadInstance()`.
The instance keys and the `defaults` block are folded in by `ConfigLoader.loadRaw` itself — `defaults` below the repository layers, `server`/`executor`/`watcher.pollInterval` overlaid on top and stripped from the repository files with one warning — so every existing consumer of `load(dir)` (the server command, the executor's slot count, the watcher's interval) sees the instance values without knowing the file exists.
`RepoRegistry` opens the contexts lazily on first use; `RepoConfiguration` provides `registry.current()` as the `RepoContext` bean the still-unscoped controllers use.
`Watcher.start(repos)` recovers each repository in its own guard, and `pollAll(repos)` polls each in its own guard, aggregating the per-repository reports into `WatcherState` (new `repositories` list; the top-level fields unchanged with one repository, name-prefixed with several, so the health banner needs no change).
`RepoOption` is a picocli mixin shared by `build`, `retry`, and `status`.
The pre-rename state-dir migration moved from the CLI runner into `RepoContexts.open`, so it runs per served repository; the metrics collector sums the registered repositories' sizes.
Fairness across repositories is decided FIFO: the executor's slot semaphore is already fair, and the watcher enqueues in registry order — a fixed, inspectable bias instead of a scheduler; round-robin only when a real queue shows starvation.

## Open Questions

- `RunningBuild` still carries no repository, so the current-builds view and the worktree pruning cannot tell repositories apart — session D, with the routes.
- With several repositories the `queuedBranches` list mixes their branch names unprefixed; session D scopes it with the UI.

## Additional Changes

- `docs/configuration.md`: the instance configuration documented, the layer table gained the home file.
- Architecture skill and AGENTS.md: registry, instance config folding, watcher multiplexing.
- `docs/plan/22-multi-repo.md`: session C ticked, fairness decided, carry-overs to session D.

## Prerequisite PRs

- PR #11 (`RepoContext` refactor).

## Follow-up PRs

- Session D: server/API/UI repo scoping.
- Session E: rollout on mih34 with Werkbaum, registry setup in `docs/deployment.md`.
