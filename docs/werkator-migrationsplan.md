# Migration Plan: GitTally → Werkator

The rename is a precaution: `gitTally` is the name of another product in the git space.
Nothing about what the build system does changes, but the name is part of a running installation in more places than the configuration.
This document lists every one of them, says which are handled automatically, and gives the order in which the rest is done.

Read [deployment.md](deployment.md) for the deployment itself; this plan only covers what the rename adds to it.

## What Is Handled Automatically

Every configuration file is looked up under its current name first and under the pre-rename name second, spelled exactly as it was (`config/ConfigFiles.kt`):

| Layer | current | still accepted |
|---|---|---|
| Machine config | `.git/werkator/.werkator.yml` | `.git/gittally/.gittally.yml` |
| Project config | `.werkator.yml` | `.gittally.yml` |
| Branch config, in a build worktree | `.werkator.yml` | `.gittally.yml` |
| Branch config, read out of git | `<commit>:.werkator.yml` | `<commit>:.gittally.yml` |

The current name wins where both exist, and the old file is then ignored rather than merged.
Two files side by side are a half-done rename, not a layering — merging them would revive a setting somebody deliberately dropped while rewriting.

The fallback exists because a configuration that is not found is not an error.
It leaves every setting at its default, so an installation that updated without renaming would come up looking healthy while having forgotten its credentials, its addresses, and what it builds.

The fallback is temporary and goes away once the watched repositories have been renamed.

## What Has To Be Moved By Hand

### 1. The state directory

`.git/gittally/` → `.git/werkator/`, with everything in it:

- the machine configuration (found under either name, but it belongs next to the rest)
- `build-results.json` — the entire build history
- `auto-builds.json` — which scheduled slots already fired today
- `control-token` — the token that authorizes mutating API calls
- `worktrees/<branchKey>/` — the per-branch build worktrees
- the generated systemd unit and its `EnvironmentFile`

There is no fallback for this path.
Without the move the instance starts with an empty history, a fresh control token, and no memory of today's scheduled builds — again without a single failure.

The worktrees hold absolute paths in both directions (`.git/worktrees/<name>/gitdir` and the worktree's own `.git` file).
Either run `git worktree repair` after the move, or simply delete `.git/werkator/worktrees/` — a worktree is rebuilt on the next build of that branch.

### 2. The artifact root

Unless `artifacts.rootDir` is set explicitly, artifacts live under `$XDG_STATE_HOME/werkator/artifacts/<repoKey>`, in practice `~/.local/state/werkator/artifacts/<repoKey>`.
Move `~/.local/state/gittally/` to `~/.local/state/werkator/`.
Left behind, the stored logs and reports of every past build are unreachable, and the permanent latest-green links point at nothing.

### 3. The systemd units

The unit names carry the product name:

| current | before |
|---|---|
| `werkator-<repo>.service` | `gittally-<repo>.service` |
| `werkator.env` | `gittally.env` |
| `werkator-docker-prune.service` / `.timer` | `gittally-docker-prune.service` / `.timer` |

The symlinks in `~/.config/systemd/user/` point into the state directory, so moving that directory breaks them.
Disable and remove the old units, regenerate with `werkator init --systemd`, then enable the new ones.
The prune units are host-global and shared by all instances on the host — replace them once, not per repository.

### 4. Docker names and labels

| what | current | before |
|---|---|---|
| build container | `werkator-build-<repoKey>-<branchKey>` | `gittally-build-…` |
| Gradle cache volume | `werkator-gradle-<repoKey>` | `gittally-gradle-…` |
| container label | `org.hoennig.werkator` | `org.hoennig.gittally` |
| image input label | `org.werkator.build-inputs-sha256` | `org.gittally.…` |

The consequences are all one-off and none of them is fatal:

- A new Gradle cache volume is empty, so the first build after the rename is slow. Rename the volume beforehand if that matters, or accept one cold build.
- The changed image label makes the build image rebuild once.
- Stale containers from before the rename carry the old label, so the cleanup on restart does not see them. Remove them once by hand.

### 5. The Gitea check

`gitea.statusContext` is the name the check appears under in Gitea; the default is now `werkator`.
Gitea itself needs no preparation — the context is created implicitly by the first status posted.
But a branch protection rule that requires the old context will never be satisfied again, and pull requests wait forever for a check nobody posts.
Update the rule in the same step, or leave `statusContext` at the old value until it is.

Statuses already written keep their old context, so a commit built before and after the change shows both.
Werkator also reads the newest status matching the configured context, so the first build after the switch does not see its own earlier results — harmless, at most one extra build.

### 6. The runtime bundle

On hosts without a Java runtime the bundle unpacks to `~/opt/werkator/` and its launcher is named `werkator`.
Move `~/opt/gittally/` accordingly, or unpack the new bundle fresh and remove the old directory once the service runs.

### 7. The managed nginx container

Only where `server.nginx.enabled` is set.
The container defaults to `werkator-nginx-<repo>` and its state (certificates included) to `~/.local/state/werkator/nginx/`.
Move the state directory with the artifact root, and remove the old container so the new one can take the ports.

## Order of Work

Per installation, and only while `/api/builds/current` is `[]` — a running build is interrupted by the restart and re-enqueued, but there is no reason to force that.

1. `systemctl --user stop werkator-<repo>.service` (old name).
2. Move the state directory, the artifact root, and the bundle.
3. Repair or delete the worktrees.
4. Rename the configuration files at the same time, or leave them to the fallback.
5. Deploy the new version.
6. `werkator init --systemd`, disable the old units, enable the new ones.
7. Start the service.
8. Update the Gitea branch protection rule if it names the check.

## Verification

- `werkator config:print --full` before the restart: every definition resolves completely, the credentials are there, the docker settings are the host's.
- After the start: the build history is the one from before, the watcher polls without errors, no warning about a configuration that was not found.
- A branch build starts, runs in the expected image, and reports under the expected Gitea check.

## Rollback

Keep the previous bundle and a timestamped copy of the machine configuration.
Note the asymmetry: the new version reads both names, the old one only reads the old name.
So a rollback works as long as the configuration files still carry — or carry again — their pre-rename names.
The state directory has to move back with it.

## Open Points

- **Should the state directory get the same fallback as the configuration?**
  It would make an update a single step, at the price of a second lookup path in every place that writes state.
  Currently intended as a deliberate manual move, because unlike a configuration the state is written, not only read, and a fallback that writes would have to decide which of two directories wins.
- **This repository's own file names** are a separate step: 121 paths still contain `gittally`, package directories included.
  The rename script for it is written and waits.
- **When the fallback goes away**, `ConfigFiles` loses its legacy entries and a leftover `.gittally.yml` should be rejected by name rather than ignored — the same reasoning as for the legacy `branches` section in [plan step 18](plan/18-remove-branches-section.md).

## Per Host

### vm4006, `hs.hsadmin.ng`

- The machine configuration is `~/hs.hsadmin.ng/.git/gittally/.gittally.yml`, mode 600, and it holds the Gitea token — check the mode after every edit, a shell redirect creates 644.
- The committed configuration on master still sets `statusContext: GitTally`; it changes with the merge that also renames the file, and that merge needs a colleague's approval.
- Deploy only while `/api/builds/current` is `[]`.
- Keep the timestamped backups of the machine configuration that already exist next to it.
