# Build Only Branches with a Pull Request

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

The watcher builds every changed or recently created origin branch.
On repositories with many work-in-progress branches this wastes build capacity on branches nobody asked to be verified.
The desired policy is: only branches with a pull request get watcher builds.
Detecting pull requests must not require a Gitea API token, so it must work for anonymous read access.

## Non-Goals

- Distinguishing open from closed pull requests (requires the Gitea API, see Open Questions).
- Gating manual `gittally build <branch>` invocations; an explicit command always builds.
- Building the merge preview commit (`refs/pull/<n>/merge`) instead of the branch head.

## The Scenarios

### Feature: watcher builds only pull-request branches

#### Background

- Gitea (like GitHub) exposes each pull request to plain git clients as a ref `refs/pull/<n>/head` pointing at the PR's head commit.
- A branch "has a pull request" when its origin head commit equals one of those pull-request head commits.
- The gate is configured per branch via `branches.<name>.requirePullRequest` (default `false`); setting it under `branches.default` applies it to all branches.

#### Scenario#000.01: A gated branch with a pull request is built!

So that pull-request branches get their commit status verified as before.

- **Given** `requirePullRequest: true` applies to branch `feature/pr`
  - **and** `feature/pr` has new commits on origin
  - **and** a ref `refs/pull/<n>/head` on origin points at the head commit of `feature/pr`
- **When** the watcher polls
- **Then** a build of `feature/pr` at that head commit is enqueued

##### Verified by

- [WatcherTest: "a branch requiring a pull request is only built when its head matches a pull-request head"](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)

#### Scenario#000.02: A gated branch without a pull request is not built!

So that work-in-progress branches do not consume build capacity.

- **Given** `requirePullRequest: true` applies to branch `feature/no-pr`
  - **and** `feature/no-pr` has new commits on origin
  - **and** no `refs/pull/<n>/head` points at its head commit
- **When** the watcher polls
- **Then** no build is enqueued
  - **and** the skip is logged

##### Verified by

- [WatcherTest: "a branch requiring a pull request is only built when its head matches a pull-request head"](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)

#### Scenario#000.03: Pull-request detection works without an API token!

So that GitTally needs no Gitea credentials for this feature.

- **Given** a git remote that serves `refs/pull/<n>/head` refs
- **When** pull-request heads are queried
- **Then** they are read via `git ls-remote origin "refs/pull/*/head"` using the existing git authentication (or none)

##### Verified by

- [GitServiceTest: "pullRequestHeads returns the head commits of the remote's pull-request refs"](../../src/test/kotlin/de/hoennig/werkator/git/GitServiceTest.kt)

#### Scenario#000.04: Ungated setups make no extra remote calls!

So that existing installations see no new network traffic.

- **Given** no due branch has `requirePullRequest` set
- **When** the watcher polls
- **Then** `refs/pull/*/head` is not queried at all

##### Verified by

- [WatcherTest: "pull-request refs are not queried when no due branch requires a pull request"](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)

#### Scenario#000.05: A branch entry overrides the default!

So that permanent branches like `main` keep building after merges, whose merge commits never match a pull-request head.

- **Given** `branches.default.requirePullRequest: true`
  - **and** `branches.main.requirePullRequest: false`
- **When** `main` has new commits and the watcher polls
- **Then** `main` is built without any pull-request check

##### Verified by

- [WatcherTest: "a branch entry overrides requirePullRequest from the default entry"](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)

#### Scenario#000.06: Auto builds respect the gate!

So that scheduled rebuilds follow the same policy as push-triggered builds.

- **Given** a branch with `autoBuild.enabled` and `requirePullRequest: true`
  - **and** its head commit matches no pull-request head
- **When** an auto-build slot becomes due
- **Then** no build is enqueued
  - **and** the slot stays untriggered, so it is retried on later poll cycles

##### Verified by

- [WatcherTest: "an auto build requiring a pull request is skipped and its slot stays untriggered"](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)

#### Scenario#000.07: The gate can be disabled globally for plain git origins!

So that the same committed configuration works on environments whose origin is plain git without pull-request refs (no Gitea/GitHub), e.g. a local test server.

- **Given** `requirePullRequest: true` applies to branch `feature/no-pr`
  - **and** `watcher.pullRequestGate: false` is set (typically in the machine-specific `.git/gittally/.gittally.yml`)
- **When** the watcher polls and `feature/no-pr` has new commits
- **Then** a build is enqueued like for an ungated branch
  - **and** `refs/pull/*/head` is not queried at all

##### Verified by

- [WatcherTest: "a disabled pull-request gate builds gated branches on plain-git origins without querying pull-request refs"](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)

## The Solution

`GitService.pullRequestHeads()` lists `refs/pull/*/head` on origin via `git ls-remote` and returns the set of head commit SHAs.
The refs are read remotely instead of being fetched, because the default fetch refspec only covers `refs/heads/*` and mirroring pull refs locally would bloat every clone.
The watcher's `startBuildIfDue` gate compares the branch's origin head commit against that set when the resolved branch config has `requirePullRequest`.
The set is computed lazily and shared across one poll cycle, so `ls-remote` runs at most once per cycle and only when a gated branch is otherwise due.
The new config key lives in `BranchConfig` with default `false`, so the feature is opt-in and existing configurations behave unchanged.
`watcher.pullRequestGate` (default `true`) turns all `requirePullRequest` gates off globally, because a plain git origin serves no `refs/pull/*/head` and gated branches would otherwise never build there; being a property of the environment, it is typically set in the machine-specific `.git/gittally/.gittally.yml`.
Config reference, `init` templates, and `CLAUDE.md` were updated in sync, including the recommended setup (`default: true`, `main: false`).

## Open Questions

- Closed pull requests whose head ref still equals the branch head also pass the gate, because matching is by commit id only; distinguishing open from closed would require the Gitea API. Currently implemented: any matching `refs/pull/<n>/head` counts.
- A due auto-build slot of a gated branch without a pull request is re-checked every poll cycle for the rest of the day instead of being marked as skipped. Currently implemented: the slot stays untriggered, mirroring the behavior while a build is still running.

## Follow-up PRs

- Optionally use the Gitea API (when a token is configured) to restrict the gate to *open* pull requests.
