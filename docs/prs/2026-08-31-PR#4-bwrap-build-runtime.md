> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

Werkator runs its builds on the host, either natively or inside a Docker container.
A Hostsharing **Managed Webspace** has neither root nor a Docker daemon, so neither runtime works there — yet that is exactly where some users want to run a Werkator that builds Werkator itself.

The only sandboxing primitive available there is `bwrap` (bubblewrap): unprivileged user namespaces with a uid-0 mapping and read-only root binds.
Step 17 (docs/plan/17-bwrap-build-runtime.md) defines a third build runtime behind the `BuildRunner` interface that is based on it.

## Non-Goals

- No change to the native and Docker runtimes; bwrap is added alongside them and selected per branch via config.
- No Docker inside the sandbox; Testcontainers-based tests cannot run there and are excluded by a branch's own build command.
- No overlayfs: the webspace's bubblewrap 0.8.0 predates `--overlay`, so a throwaway writable rootfs per build is out of scope.
- No web-access deployment; that half of step 17 (Apache `.htaccess` proxy, systemd user unit, Let's Encrypt) needs a real webspace and is written up separately.
- No per-repo build automation on the webspace; this PR makes it possible, and a follow-up runs it on a real host.

## The Scenarios

### Feature: bubblewrap as the third build runtime

#### Background

- A branch selects exactly one runtime: nothing (native), `docker.enabled`, or `bwrap.enabled`.
- `bwrap.enabled` and `bwrap.rootfs` are pinned (host-set), so a branch's committed config cannot switch its own sandbox off or substitute a foreign rootfs.
- `docker.enabled` and `bwrap.enabled` are mutually exclusive per branch; enabling both is rejected, not silently picked.

#### Scenario#4.01: A bwrap build runs the command inside the sandbox as root

So that the build is isolated from the host exactly as the native and Docker runtimes intend.

- **Given** a branch with `bwrap.enabled` and a `bwrap.rootfs` archive
- **When** a build for that branch starts
- **Then** Werkator unpacks the rootfs on demand into `.git/werkator/buildenv/<envKey>/rootfs`
  - **and** invokes `bwrap` with a uid-0 mapping, a read-only root bind of the rootfs, the workspace bound at its host path, and `--chdir` into it
  - **and** the returned process is the attached `bwrap` process, so streaming and cancellation behave like native builds
- **and** the environment plus `bwrap.env` are passed via `--setenv`

##### Verified by

- [BwrapBuildRunnerTest](../../src/test/kotlin/de/hoennig/werkator/build/BwrapBuildRunnerTest.kt)

#### Scenario#4.02: Git metadata mounts keep secrets out of the sandbox

So that builds can run read-only git commands but never reach the machine config or the control token.

- **Given** a workspace that is a worktree of the repository
- **When** the sandbox is assembled
- **Then** the primary `.git` is bound read-only
  - **and** `.git/werkator/` is masked by an empty tmpfs
  - **and** the worktree admin directory is bound read-write

##### Verified by

- [BwrapBuildRunnerTest](../../src/test/kotlin/de/hoennig/werkator/build/BwrapBuildRunnerTest.kt)

#### Scenario#4.03: A branch cannot turn its sandbox off or swap its rootfs

So that the pinned sandbox policy holds for builds a branch invents as well as for ones the host already knows.

- **Given** a branch whose committed config sets `bwrap.enabled` or `bwrap.rootfs`
- **When** that config is resolved into a build
- **Then** the pinned keys are stripped from the worktree layer
  - **and** enabling both `docker` and `bwrap` on a build is rejected, not picked silently

##### Verified by

- [ConfigLoaderTest](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)

#### Scenario#4.04: The dispatcher routes builds to the bwrap runtime

So that a bwrap-explicit branch builds inside the sandbox rather than natively.

- **Given** a branch with `bwrap.enabled`
- **When** its build is dispatched
- **Then** `BwrapBuildRunner` is selected

##### Verified by

- [DispatchingBuildRunnerTest](../../src/test/kotlin/de/hoennig/werkator/build/DispatchingBuildRunnerTest.kt)

### Feature: Werkator builds itself without Docker

#### Background

- Werkator's own build runs the full test suite, which includes `TestcontainersSmokeTest`.
- On a Docker-less host (the webspace, and the bwrap sandbox that builds there), that test must not fail the self-build.

#### Scenario#4.05: The Testcontainers smoke test is skipped, not failed, without Docker

So that a Docker-less build of Werkator itself stays green.

- **Given** no reachable Docker daemon
- **When** the test suite runs
- **Then** `TestcontainersSmokeTest` is reported as skipped
  - **and** the build is not failed by it
- **and** with a Docker daemon present the test still runs and verifies a container

##### Verified by

- [TestcontainersSmokeTest](../../src/test/kotlin/de/hoennig/werkator/framework/TestcontainersSmokeTest.kt)

## The Solution

**A third `BuildRunner` by the same shell-out pattern as git and Docker.**
`BwrapBuildRunner` shells out to the `bwrap` CLI (no library), unpacks a prepared Debian rootfs on demand into `.git/werkator/buildenv/<envKey>/rootfs`, reuses the step-16 git-metadata mounts verbatim, binds a persistent `.git/werkator/buildenv/home` as `/root`, and returns the attached `bwrap` process so streaming and cancellation match the other runtimes.
`DispatchingBuildRunner` routes by `bwrap.enabled`.

**Pinning and mutual exclusion hold at the choke point.**
`bwrap.enabled` and `bwrap.rootfs` join the pinned sandbox-policy set, stripped from the worktree layer so a branch cannot disable its sandbox or substitute a foreign rootfs.
`docker` and `bwrap` are mutually exclusive per build, rejected in `buildSettings` — the single point every build passes through — instead of picked silently.

**Tooling makes the two manual steps reproducible.**
`tools/build-bwrap-rootfs.sh` builds the rootfs archive (debootstrap-minbase Debian + JDK 21 + git + locales) on any machine with Docker, since `debootstrap` is not available on the target.
`tools/werkator-build-prerequisites.sh` re-runs the exact precondition command line from the plan on the target webspace and checks all three signals.
`TestcontainersSmokeTest` is gated with `enabledIf docker available`, so a Docker-less self-build skips it.

## Open Questions

- Whether the rootfs archive built by `tools/build-bwrap-rootfs.sh` is complete for `./gradlew build` has not been exercised on a real webspace; the JDK 21, git and locales package set is a good baseline but project-specific tooling must be added.
- The `systemd` user unit for a webspace should gain `MemoryMax`/`TasksMax` (configurable per the plan); not implemented here.

## Additional Changes

- Config template (`InitCommand`), `docs/configuration.md` and `AGENTS.md` updated so all three config places stay in sync and the pinned set is documented.
- The plan file 17 records the precondition result and the new tooling.

## Follow-up PRs

- ADR 0007 recording the bubblewrap runtime decision (options: bwrap vs proot/fakechroot vs plain native).
- Web-access deployment on a real webspace and the `docs/deployment.md` third variant, written once verified there.
- `MemoryMax`/`TasksMax` on the webspace systemd unit.
