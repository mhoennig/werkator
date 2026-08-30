# Step 16: Git Access Inside Docker Build Containers

Prerequisites: steps 11, 15.
Read `README.md` first.
Motivated by the vm4006 rollout (step 15, fourth finding): hs.hsadmin.ng's build calls git in several places; `:prQuickCheck` failed hard with "fatal: not a git repository", other call sites swallowed the failure silently.

## Problem

Builds run in git worktrees under `.git/werkator/worktrees/<branchKey>`.
A worktree's `.git` is a pointer file into the primary repository's `.git/worktrees/<key>`, and `DockerBuildRunner` bind-mounts only the worktree — so every git call inside the build container fails.
The legacy script did not have this problem because it built in the primary checkout with the real `.git` present (read-write, including all secrets stored next to it — full exposure).
Hard invariant to preserve: a branch build must never be able to reach credentials; `.git/werkator/.werkator.yml` (`git.token`) and the control token live under `.git`.

## Considered Options

- **Read-only `.git` mount with `.git/werkator/` masked (chosen)** — three layered mounts, no config key, no workspace mutation; strictly less privileged than legacy.
- Copy minimal git metadata into the workspace (admin dir plus `objects/info/alternates`) — mutates the workspace, still needs the object database mounted, more moving parts.
- Document the limitation and require git-free build commands — pushes the problem onto every watched project; hsadmin-ng shows real builds do call git.

## Design

`DockerBuildRunner.gitMetadataMounts(workspace, repoDir)` adds three mounts when (and only when) the workspace is a worktree of `repoDir` (detected via the `gitdir:` pointer file, which must resolve into `repoDir/.git`):

1. `repoDir/.git` → same path, **read-only**: objects, refs, and the worktree admin metadata become resolvable; object and ref writes stay impossible.
2. An empty **tmpfs over `repoDir/.git/werkator`**: masks the machine config (`git.token`), the control token, and all werkator state; the workspace bind (deeper path, Docker nests mounts by target depth) resurfaces only this build's own worktree inside the masked directory.
3. `repoDir/.git/worktrees/<key>` → same path, **read-write**: the worktree's admin dir (HEAD, index), so index-refreshing commands like `git status` work.

No configuration key: the exposure is strictly smaller than the legacy baseline, and a knob would join the pinned sandbox-policy set without a known use case.
Remaining, documented exposure: the rest of `.git` — including `.git/config` — is readable by builds; werkator never stores credentials there (fetch auth uses a secret-free `GIT_ASKPASS` with env-passed credentials).

## Tests

- `DockerBuildRunnerTest`: worktree workspace → the three mounts with `:ro` and `--tmpfs`; non-worktree workspace → no git metadata mounts (also keeps the exact-argv test valid).

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- In a real Docker build worktree: `git log`/`git status` succeed inside the container, `.git/werkator/.werkator.yml` and `control-token` are not readable, and a `git push`/ref write fails.
- `docs/configuration.md` (docker notes) and the architecture skill describe the mounts.

## Result (2026-08-10)

Implemented as designed; verified on vm4006 (see below) and in unit tests.
`sh -c 'git log -1 && git status --short && cat .../.git/werkator/.werkator.yml'` inside a build container of the hs.hsadmin.ng worktree: git commands succeed, the machine config read fails with "No such file or directory", `git update-ref` fails on the read-only filesystem.
