> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

PR #12 gave the instance a registry of repositories, but the server still served exactly one of them.
Every route — API, pages, artifact files — worked on `registry.current()`, so a second registered repository was built and polled, yet invisible and unreachable.
Two consequences went beyond "not browsable": the current-builds views listed the running builds of *all* repositories while looking their status up in *one* repository's results, and `cancel` addressed a build by key across the whole instance.
Step 22 session D is the repository dimension in the server: routes, links, and the UI.

## Non-Goals

- The rollout on the instance and the deployment documentation of the registry (session E).
- Merging several repositories into one table: the pages stay per repository (see The Solution).
- A per-repository control token or per-repository metrics: one instance, one token, one metrics page (ADR 0009).

## The Scenarios

### Feature: every route carries the repository

#### Background

- The instance serves a registry of repositories (ADR 0009); the *served* repository is `RepoRegistry.current()` — the current working directory when it is served, else the first entry.
- The prefix is `/repos/<name>` for the pages and `/api/repos/<name>` for the API, where `<name>` is the registry entry's short name.

#### Scenario#13.01: The repository-scoped API answers for the named repository

So that a second registered repository is reachable at all.

- **Given** an instance serving a repository named `test`
- **When** `GET /api/repos/test/builds/latest` is requested
- **Then** the answer holds that repository's builds
  - **and** `GET /api/builds/latest` still answers the same, because the unscoped form means the served repository

##### Verified by

- [BuildsApiControllerTest — "the repository-scoped routes answer for the named repository and 404 for an unknown name"](../../src/test/kotlin/de/hoennig/werkator/server/BuildsApiControllerTest.kt)

#### Scenario#13.02: A name the instance does not serve is a miss, not an error

So that a typo in a URL reads like every other miss of this API.

- **Given** an instance that serves no repository named `no-such-repo`
- **When** `GET /api/repos/no-such-repo/builds/latest` is requested
- **Then** the answer is 404 with `{"error": "no repository named 'no-such-repo'"}`
  - **and** the page `/repos/no-such-repo` answers 404 as well

##### Verified by

- [BuildsApiControllerTest — "the repository-scoped routes answer for the named repository and 404 for an unknown name"](../../src/test/kotlin/de/hoennig/werkator/server/BuildsApiControllerTest.kt)
- [UiControllerTest — "a page of a repository this instance does not serve answers 404"](../../src/test/kotlin/de/hoennig/werkator/server/UiControllerTest.kt)

#### Scenario#13.03: A single-repository installation keeps its existing URLs

So that no bookmark, no posted Gitea link, and no operator habit breaks on an installation that has exactly what it had before.

- **Given** an instance serving exactly one repository
- **When** any page is rendered
- **Then** every link it contains is unscoped (`/branches`, `/history`, `/api/builds/latest`)
  - **and** no repository switcher is shown, because there is nothing to switch

##### Verified by

- [UiControllerTest — "with one served repository the pages keep their existing URLs and show no switcher"](../../src/test/kotlin/de/hoennig/werkator/server/UiControllerTest.kt)

#### Scenario#13.04: With several repositories every link names its repository

So that a click inside a repository's page stays inside that repository.

- **Given** an instance serving the repositories `test` and `other`
- **When** the page `/repos/test` is rendered
- **Then** its navigation links, its `data-api`, and its `werkator-repo-base` meta carry `/repos/test`
  - **and** the switcher offers `/repos/other`

##### Verified by

- [UiControllerTest — "with several served repositories every link names its repository and the switcher appears"](../../src/test/kotlin/de/hoennig/werkator/server/UiControllerTest.kt)

#### Scenario#13.05: A repository-named route never reaches another repository

So that the repository in the path is a boundary, not a label.

- **Given** a build whose artifact key is not recorded in the repository named in the route
- **When** that build is cancelled through `/api/repos/test/builds/<key>/cancel`
- **Then** the answer is 404
  - **and** the executor is not asked to cancel anything

##### Verified by

- [BuildsApiControllerTest — "cancel does not reach a build of another repository"](../../src/test/kotlin/de/hoennig/werkator/server/BuildsApiControllerTest.kt)
- [BuildsApiControllerTest — "current answers only the served repository's builds"](../../src/test/kotlin/de/hoennig/werkator/server/BuildsApiControllerTest.kt)
- [WatcherTest — "a running build of another repository does not keep this repository's worktree"](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)

## The Solution

Every controller resolves its `RepoContext` per request instead of holding the served one as a bean: `repoOf(name)` is `registry.current()` without a name and `registry.byName(name)` with one, and an unknown name throws `UnknownRepositoryException`, which each controller turns into its own 404 shape.
Each route is mapped twice — scoped and unscoped — so the unscoped form is not a transitional alias but the permanent way to say "the served repository".

`RunningBuild` carries its `RepoContext`, so the executor's instance-global `currentBuilds()` can be filtered: the current-builds view and API show their own repository's builds, and the watcher's worktree pruning is protected by its own repository's builds alone.
`cancel` additionally verifies that the artifact key is recorded in the named repository — a queued or running build always has its PENDING/RUNNING result there.

The pages stay **per repository** instead of merging every repository's rows into one table with a repository column: a row's actions need the repository anyway, branches come from one origin and artifacts from one store, and with the single repository most installations have, such a column is pure noise.
What makes the instance one UI is the switcher in the navigation.

The link prefix follows the *number of served repositories*, not the route a page was reached through — with one repository the installation keeps its existing URLs, with several every link names its repository.
`werkator.js` reads that prefix once from a `werkator-repo-base` meta and builds its action and artifact URLs from it; the paths rendered into the DOM already carry it.
`BranchPermalinks.permanentUrl` takes the prefix too: the permanent key is a hash of the build name alone, so two repositories both having `main` would otherwise share one permanent URL.

## Open Questions

- The metrics page and the control token stay instance-wide (ADR 0009); a per-repository token is not planned.
- `/api/watcher` stays unscoped — its state already carries the per-repository reports, and the UI banner is instance-wide.

## Prerequisite PRs

- PR #12 — the repository registry (step 22 session C).

## Follow-up PRs

- Step 22 session E — the rollout: the registry with Werkator and Werkbaum under one service, and `docs/deployment.md`.
