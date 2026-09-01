> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

The restart button repeats the commit a build was recorded on.
On the History and Latest views that is exactly right: a row there is a past run, and repeating it means that run.

On the Branches view it is wrong, because a row there is not a run — it is a branch, listed whether it was ever built or not.
Pressing restart on `master` therefore rebuilt whatever commit master happened to point at the last time it was built, not what master is now.

Repeating an overtaken commit answers a question nobody asked, and it can be worse than useless.
A build gate that compares the built commit against origin cannot pass on a superseded commit at all: hs.hsadmin.ng's `prQuickCheck` asserts that HEAD contains `origin/master`, which an overtaken master commit can never do.
Observed in production on 2026-08-31: a restart of master rebuilt a commit from two days earlier and failed, and every further press produced the same red result, because pressing it again cannot change the commit.

## Non-Goals

- No change to the Latest and History views: a row there stands for a recorded run, and repeating it is the point.
- No change to which build definition a restart uses, and no way to pick one — the row's own definition is re-run either way.
- No change to the watcher, which decides on its own what to build and when.
- No new button: the existing one changes what it does on one view, and says so.

## The Scenarios

### Feature: a restart on the Branches view builds the branch as it is now

#### Background

- A row on `/branches` stands for a branch of origin, with its latest build or an `unknown` row.
- A row on `/` (Latest) and `/history` stands for a recorded build.
- A build *name* is the pool: the branch itself for the default build, `<branch>@<build>` for a named one.

#### Scenario#3.01: The Branches view builds the branch's current origin head

So that a restart answers "build this branch as it is", which is what a branch row means.

- **Given** a branch whose last recorded build ran on an overtaken commit
- **When** the restart button on the Branches view is pressed
- **Then** the branch's current origin head is built
  - **and** the recorded commit is not built

##### Verified by

- [BuildsApiControllerTest: "restart with atOriginHead builds the branch as it is now, not the recorded commit"](../../src/test/kotlin/de/hoennig/werkator/server/BuildsApiControllerTest.kt)
- [UiControllerTest: "the branches view restarts at the branch's origin head, the latest view repeats the run"](../../src/test/kotlin/de/hoennig/werkator/server/UiControllerTest.kt)

#### Scenario#3.02: The row keeps its build definition and its real branch

So that restarting a named build does not silently turn it into a different build.

- **Given** a row for a named build such as `main@pitest`
- **When** it is restarted from the Branches view
- **Then** the new commit is built under that same definition
  - **and** on the branch the record names, not on the pool name

##### Verified by

- [BuildsApiControllerTest: "restart with atOriginHead keeps the recorded build definition and its real branch"](../../src/test/kotlin/de/hoennig/werkator/server/BuildsApiControllerTest.kt)

#### Scenario#3.03: A branch that is gone from origin is refused by name

So that a restart cannot quietly fall back to a commit the user did not ask for.

- **Given** a row for a branch that no longer exists on origin
- **When** it is restarted from the Branches view
- **Then** the request is refused, naming the branch
  - **and** no build is started

##### Verified by

- [BuildsApiControllerTest: "restart with atOriginHead of a branch gone from origin is refused by name"](../../src/test/kotlin/de/hoennig/werkator/server/BuildsApiControllerTest.kt)

#### Scenario#3.04: Latest and History still repeat the recorded run

So that the one view whose rows are runs keeps the behavior that fits them.

- **Given** a row on the Latest view
- **When** its restart button is pressed
- **Then** the recorded commit is built, as before

##### Verified by

- [UiControllerTest: "the branches view restarts at the branch's origin head, the latest view repeats the run"](../../src/test/kotlin/de/hoennig/werkator/server/UiControllerTest.kt)
- [BuildsApiControllerTest: "restart enqueues the branch's last recorded commit, also for branch names with slashes"](../../src/test/kotlin/de/hoennig/werkator/server/BuildsApiControllerTest.kt)

## The Solution

**One flag, decided by the view, not by the button.**
`/api/builds/restart` takes `atOriginHead`; with it the commit comes from `GitService.originHeadCommit` instead of the recorded build.
`UiController` sets `restartAtOriginHead` per view — true for Branches, false for Latest and History — and the template and `werkator.js` carry it to the button.
The decision therefore lives where the meaning of a row is decided, and there is one endpoint, one token check, one place to change.

**The button says what it does.**
On the Branches view it reads "Build current head" instead of "Restart build", server-rendered and in the JavaScript alike.
A button whose label promises a repeat while it builds something else would be the same class of quiet surprise this change removes.

**A missing branch is an error, not a fallback.**
Falling back to the recorded commit would produce exactly the behavior the user asked to leave behind, at the moment they can least expect it.
The message names the branch.

## Additional Changes

- The endpoint's parameter is still called `branch` although the views pass a build *name* (`main@pitest`).
  It resolves correctly — the recorded branch is used to build — but the name is misleading and is left for a separate change, to keep this one reviewable.
