> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

`gitTally` is the name of another product in the git space.
Sharing it is a risk that is cheap to remove now and expensive to remove later, so the project is renamed to **Werkator** as a precaution.
Nothing about what the build system does changes.

A rename of a CI system is not a search-and-replace, because the name is part of a running installation:

- The configuration is found by name — `.gittally.yml` and `.git/gittally/.gittally.yml`.
- The state is found by path — `.git/gittally/` holds the build history, the control token, the scheduled-build state and the build worktrees.

Neither is loud when it goes missing.
A configuration that is not found leaves every setting at its default, and a state directory that is not found is simply empty.
An installation updated without renaming would therefore not fail; it would come up looking healthy, having forgotten its credentials, its addresses, its build history and what it builds.

The rename must therefore carry existing installations across, not just rename files in this repository.

## Non-Goals

- No behavioral change to builds, watching, the API or the web UI.
- No rename in the old PR-docs under `docs/prs/` and no rewriting of the historic entries in the release notes: both record what was published at the time.
- No automatic migration of anything outside the repository — the artifact root, the systemd units, the runtime bundle, the Docker names and labels and the Gitea check context stay manual, listed in [the migration plan](../werkator-migrationsplan.md).
- No permanent dual-name support: the configuration fallback is temporary and goes away once the watched repositories have been renamed.

## The Scenarios

### Feature: an installation keeps working across the rename

#### Background

- The *committed configuration* is `.werkator.yml` at the repository root, in a build worktree, and as committed on a branch.
- The *machine configuration* is `.git/werkator/.werkator.yml` and holds the secrets.
- The *state directory* is `.git/werkator/` and holds everything the instance remembers.
- *Pre-rename* names are spelled exactly as they were: `.gittally.yml` and `.git/gittally/`.

#### Scenario#1.01: A configuration under its pre-rename name is still read

So that an installation may be updated and renamed in two separate steps, at its own pace.

- **Given** a repository whose committed configuration is named `.gittally.yml`
  - **and** no `.werkator.yml` next to it
- **When** the configuration is loaded
- **Then** its settings take effect exactly as under the current name

##### Verified by

- [ConfigLoaderTest: "falls back to the pre-rename .gittally.yml at the repository root"](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)
- [ConfigLoaderTest: "falls back to the pre-rename machine config under .git/gittally"](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)
- [ConfigLoaderTest: "a branch whose config is committed under the pre-rename name is still read as the branch layer"](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)
- [WatcherTest: "a branch config committed under the pre-rename name is still read"](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)

#### Scenario#1.02: The current name wins, and the old file is not merged

So that a half-done rename cannot revive a setting somebody deliberately dropped while rewriting.

- **Given** a repository holding both `.werkator.yml` and `.gittally.yml`
- **When** the configuration is loaded
- **Then** only `.werkator.yml` is read
  - **and** keys present only in `.gittally.yml` have no effect

##### Verified by

- [ConfigLoaderTest: "the current name wins where both exist, so a half-done rename is not merged"](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)

#### Scenario#1.03: The state directory moves itself at the first start

So that an update does not silently cost an installation its build history and its control token.

- **Given** an installation whose state lives in `.git/gittally/`
  - **and** no `.git/werkator/` next to it
- **When** any Werkator command is started in that repository
- **Then** the directory is `.git/werkator/` afterwards, with its contents
  - **and** the build history and the control token are the ones from before

##### Verified by

- [StateDirMigrationTest: "moves the pre-rename state directory to its current path"](../../src/test/kotlin/de/hoennig/werkator/StateDirMigrationTest.kt)

#### Scenario#1.04: The moved worktrees are dropped rather than repaired

So that a build worktree pointing at its old path cannot break the next build.

- **Given** a state directory that is moved and holds build worktrees
- **When** the move happens
- **Then** the worktrees are gone
  - **and** the rest of the state survives
  - **and** the next build of a branch recreates its worktree

##### Verified by

- [StateDirMigrationTest: "drops the moved worktrees, because they point at their old path"](../../src/test/kotlin/de/hoennig/werkator/StateDirMigrationTest.kt)

#### Scenario#1.05: Two state directories are never merged or guessed between

So that an operator who moved the directory by hand does not lose the live state to an automatism.

- **Given** an installation holding both `.git/gittally/` and `.git/werkator/`
- **When** a command is started
- **Then** nothing is moved
  - **and** a warning names the leftover

##### Verified by

- [StateDirMigrationTest: "leaves both alone when the current directory already exists"](../../src/test/kotlin/de/hoennig/werkator/StateDirMigrationTest.kt)
- [StateDirMigrationTest: "does nothing where there is no pre-rename directory"](../../src/test/kotlin/de/hoennig/werkator/StateDirMigrationTest.kt)

#### Scenario#1.06: The machine configuration survives a directory that moved without it

So that the move cannot produce a pair of names its own lookup does not expect.

- **Given** a state directory that has moved to `.git/werkator/`
  - **and** the machine configuration inside it still named `.gittally.yml`
- **When** the configuration is loaded
- **Then** its settings take effect
  - **and** where the move did it, the file carries the current name afterwards

##### Verified by

- [StateDirMigrationTest: "renames the machine configuration inside the moved directory"](../../src/test/kotlin/de/hoennig/werkator/StateDirMigrationTest.kt)
- [StateDirMigrationTest: "never overwrites a machine configuration that already carries the current name"](../../src/test/kotlin/de/hoennig/werkator/StateDirMigrationTest.kt)
- [ConfigLoaderTest: "finds the machine config left under its old name in an already-moved directory"](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)

## The Solution

**One spelling rule.**
`Werkator` where the name is prose, capitalized where it is a Kotlin type and the file holding it, lowercase everywhere a machine reads it — the command, the packages, paths, configuration keys and values, the Gitea check context.
Environment variables keep their own convention and are uppercase throughout.
This is what turns a rename into a decidable question instead of a matter of taste, and it is applied across the whole repository, file names included.

**A name fallback for the configuration, in one place.**
[`ConfigFiles`](../../src/main/kotlin/de/hoennig/werkator/config/ConfigFiles.kt) holds the pair of names and is used by all four lookups: the machine and project layers in `ConfigLoader.loadRaw`, the build worktree in `ConfigLoader.loadForWorktree`, the branch layer the watcher reads out of git, and the same read in the web UI.
The current name wins and the old file is then ignored rather than merged — two files side by side are a half-done rename, not a layering.
Error messages name the file that was actually read, so they never point at a file that does not exist.

**A move that finishes the job.**
The deployment to vm4006 found the gap the hard way: the move renames the *directory* and leaves the file inside it alone, so the machine configuration ended up at `.git/werkator/.gittally.yml` — a pair of names the lookup did not expect.
The instance came up with empty credentials and no host build definitions, without an error, which is the failure this PR exists to prevent.
`StateDirMigration` now renames the configuration along with the directory, unless one under the current name is already there, and `ConfigFiles` carries the intermediate path as a third candidate for a directory somebody moved by hand.

**A one-time move for the state.**
[`StateDirMigration`](../../src/main/kotlin/de/hoennig/werkator/StateDirMigration.kt) renames `.git/gittally` to `.git/werkator` from `CliRunner`, before any command resolves a path under it and therefore before the second Spring context of `server` exists.
A fallback was rejected here: unlike a configuration, the state is written, so a fallback would have to decide on every write which of two directories wins, where a one-time move decides once and leaves a single path behind.
The move is deliberately narrow — only when the old directory exists and the new one does not — and a failure is an error in the log, never an abort, because a CI must not hang on it.

**A round number for the new name.**
The release after `0.9.21` is `1.0.0`, and the release note says why: a product that changes its name is better off counting from one under it.
It is deliberately not a claim about maturity — plan steps 14, 17 and 18 are open.

**A plan for what cannot be automated.**
[`docs/werkator-migrationsplan.md`](../werkator-migrationsplan.md) lists the artifact root, the systemd units, the Docker names and labels, the runtime bundle, the managed nginx container and the Gitea check context, with the order of work, the verification and the rollback.
The rollback section names the asymmetry: the new version reads both names, the old one only the old, so a rollback works only while the configuration files still carry their pre-rename names.

## Open Questions

- **Decided: lowercase.** The default `gitea.statusContext` is `werkator`.
  It was worth asking, because it is the one value a human reads as a label, in Gitea next to the commit.
  It stays lowercase because it is also matched by Gitea and read back by the client, which makes it a value, not prose.

## Additional Changes

- Deleted `docs/migration-from-legacy.md`.
  It mapped the legacy bash script's environment variables to YAML keys, and every host it addressed has migrated.
  Its removal also settles one open item of [plan step 18](../plan/18-remove-branches-section.md).
- Fixed a broken web UI: five templates already referenced `/werkator.js` and `/werkator.css` while the static files were still named `gittally.*`, so every page but the release notes served neither stylesheet nor live updates.
- Renamed the Kotlin types `werkatorCommand` and `werkatorMeta`, which a case-insensitive replace had left lowercase, and the constant `werkator_LABEL` to `WERKATOR_LABEL`.
- Added the release-notes entry for the rename, as `unreleased`.
- Removed the legacy environment-variable conversion from `tools/setup-werkator-instance`.
  The blanket rename had rewritten the old script's `GITTALLY_*` variables to a spelling that never existed on any host, so the conversion would have read nothing from a real legacy file and written an almost empty configuration — silently.
  The conversion has served its purpose with the vm2176 → vm4006 migration; what remains is the setup of a new instance: the preconditions, the credential prompt, and the machine configuration written mode 600.
  It also stops emitting a legacy `branches:` section, which [plan step 18](../plan/18-remove-branches-section.md) is about to reject outright.
- Restored the real `GITTALLY_*` spelling in `docs/plan/00-legacy-analysis.md` and `docs/plan/13-nginx-tls.md`, which record what the old script read.
- Left the pre-rename fallback out of the release notes and `docs/configuration.md`.
  Exactly one repository carries the old names and it is migrated by hand in the same move as this release, so the fallback is a transition of days rather than something to plan around.
  It stays described where it is worked on: in `ConfigFiles`, in `StateDirMigration`, and in the migration plan.
- Migrated this repository's own `.werkator.yml` from the deprecated `branches` section to a `builds.default` definition with a `trigger` block.
  Nothing about its build changes; it stops being the blocker for the precondition of [plan step 18](../plan/18-remove-branches-section.md), which requires that no configuration still in play carries the section, and which rejects it by name afterwards.

## Follow-up PRs

- Set the version and the release date, and deploy to vm4006 following the migration plan.
- Change `gitea.statusContext` in the watched repository `hs.hsadmin.ng`, where `origin/master` still sets `GitTally`, together with the branch protection rule that names the check.
- Remove the configuration name fallback once the watched repositories carry the current names, and reject a leftover `.gittally.yml` by name — the same reasoning as for the legacy `branches` section in [plan step 18](../plan/18-remove-branches-section.md).
