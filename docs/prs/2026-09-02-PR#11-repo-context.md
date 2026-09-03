> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

ADR 0009 (PR #10) decided that one Werkator instance serves a set of repositories, but the code assumes a single one everywhere: the executor, the watcher, the commands, and the controllers resolve results, artifacts, worktrees, and git access through an implicit working directory, and the result repository and artifact store are context-wide beans.
A registry of repositories cannot be threaded through that — every code path would have to learn a `workingDir` parameter it does not have and a results file it cannot pick.
Step 22 session B is the behavior-preserving refactor that gives those paths one explicit handle to a repository, so that sessions C and D only have to open more of them and put a name on the routes.

## Non-Goals

- The registry, the home `~/.werkator.yml`, and N repositories (session C).
- Repository-scoped routes, API paths, or UI grouping (session D) — every route, template, and JSON shape is unchanged.
- Any configuration change; `docs/configuration.md` is untouched.

## The Scenarios

### Feature: one explicit handle per repository

#### Background

- A `RepoContext` bundles what is repository-scoped: the primary checkout, the repository's results file, its artifact store, and a short name (the directory basename by default) meant for display and, later, routes.
- The context object is the identity: executor pools and the watcher's memory are keyed by it, so exactly one is opened per repository.

#### Scenario#11.01: Builds are serialized per repository and branch under one global cap

So that two repositories in one instance never build the same branch name in each other's worktree, while the instance-level `executor.maxConcurrent` stays the only concurrency limit.

- **Given** the executor and a `RepoContext`
- **When** `startBuild(repo, branch, commit, build)` is called
- **Then** the PENDING result is written to that context's results and the artifacts persist to that context's store
  - **and** a second build of the same branch in the same context waits for the first, while other branches run concurrently up to the global cap
  - **and** a duplicate is only detected within the same context.

##### Verified by

- [BuildExecutorTest](../../src/test/kotlin/de/hoennig/werkator/build/BuildExecutorTest.kt) (the existing serialization, concurrency, and duplicate tests, now over a context)
- [BuildExecutorArtifactIntegrationTest](../../src/test/kotlin/de/hoennig/werkator/artifacts/BuildExecutorArtifactIntegrationTest.kt)

#### Scenario#11.02: The watcher polls a repository context and keeps its memory per repository

So that the next session can iterate contexts in one cycle without one repository's fetch outage silencing another's log or cache.

- **Given** the watcher and a `RepoContext`
- **When** `start(repo)`, `poll(repo)`, or `recoverOnStartup(repo)` runs
- **Then** results, artifacts, auto-build slots, and worktrees are those of the context
  - **and** the logged fetch error, the `autoBuild` deprecation warning, and the cached branch definitions are remembered per context.

##### Verified by

- [WatcherTest](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt) (every existing poll, recovery, and prune test, now over a context)
- [ServerModeApplicationTest](../../src/test/kotlin/de/hoennig/werkator/ServerModeApplicationTest.kt) (the server profile starts the watcher over the served repository)

#### Scenario#11.03: A single-repository installation behaves exactly as before

So that no route, file location, or display changes for existing installations.

- **Given** no registry (there is none yet)
- **When** the CLI or the server starts in a repository
- **Then** the current working directory is the one context, named after its directory
  - **and** the result and artifact-store beans are that context's members, so `status`, the JSON API, and the UI read the same files as before.

##### Verified by

- [RepoContextsTest](../../src/test/kotlin/de/hoennig/werkator/repo/RepoContextsTest.kt)
- the unchanged controller, command, and integration tests of the full suite

## The Solution

`RepoContext` (`repo` package) is a plain class with `name`, `workingDir`, `results`, and `artifactStore`; `RepoContexts.open(dir)` builds one over `.git/werkator/build-results.json` and a `FileArtifactStore` keyed by the path, and `RepoConfiguration` provides the current directory as the single bean.
`BuildExecutor.startBuild` takes the context first, keeps its per-branch serial workers in a map keyed by `(context, branch)`, and writes results and artifacts through the build's own context; the semaphore stays one per executor, since the cap is instance-level per ADR 0009.
`Watcher.start/poll/recoverOnStartup` take the context, and the three mutable per-repository fields moved into a `RepoWatch` keyed by context; the observable `WatcherState` is untouched.
`ConsoleBuildRunner`, `BuildCommand`, `RetryCommand`, `BuildsApiController`, `UiController`, and `BranchListing` lost their settable `workingDir` in favor of the injected context.
Git access and config loading stay path-based services taking `repo.workingDir`: the home `defaults:` layer of session C is the point where config loading needs the context, and it was not built ahead of that need.
The open `artifactKey` question is decided against a repository prefix: the results file and the artifact store are per repository, so the key only has to be unique within one, and the repo dimension will enter through the route segment.

## Open Questions

- `RunningBuild` carries no repository, so `currentBuilds()` and the watcher's worktree pruning cannot tell repositories apart yet — harmless with one context, listed for session C in the plan.
- `StateDirMigration`, the metrics collector's repository size, and `ServerCommand`'s config still read the current directory — instance-level or per-registry-entry concerns, deferred to session C.

## Additional Changes

- Architecture skill: new "Repository Context" section; the executor and watcher paragraphs describe the context-based signatures.
- AGENTS.md: `repo` in the package list and a hard invariant that repository-scoped state goes through a `RepoContext`.
- `docs/plan/22-multi-repo.md`: session B ticked with the carry-overs to session C, the `artifactKey` question decided.

## Prerequisite PRs

- PR #10 (ADR 0009 and the step 22 roadmap).

## Follow-up PRs

- Session C: the registry and N repositories, watcher multiplexing.
- Session D: server/API/UI repo scoping.
- Session E: rollout on mih34 with Werkbaum.
