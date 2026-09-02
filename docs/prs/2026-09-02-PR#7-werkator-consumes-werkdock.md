> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

PR #6 grew Werkdock as a standalone sandbox CLI, but `BwrapBuildRunner` still assembled its own raw `bwrap` invocation — the extraction was only half done, and the two implementations could drift.
Separately, the webspace deployment path in `tools/remote` still followed the original self-build prototype: clone Werkator's own repository onto the target and build it there, which is exactly the pattern ADR 0006 rejected ("build locally, install the bundle") and step 21 set out to correct.

## Non-Goals

- `tools/remote`'s configuration-writing duplication (heredocs/`sed` into the machine config) — that is step 23, PR #8/#9.
- Multi-repository support for one Werkator instance (step 22, PR #10).
- RFC 0002 levels 2/3 and RFC 0003 (composable toolchain mounts) stay deferred/candidate.

## The Scenarios

### Feature: `BwrapBuildRunner` delegates to Werkdock

#### Scenario#7.01: A build runs through `werkdock run` instead of a raw `bwrap` invocation

So that Werkator and Werkdock never carry two implementations of the same sandbox invocation.

- **Given** `bwrap.enabled` and a configured rootfs archive
- **When** a build needs the sandbox
- **Then** `BwrapBuildRunner` loads the image via `werkdock images`/`werkdock load` (once, keyed by `imageName(rootfs)` = `werkator-buildenv-<hash-of-source>`) and runs the build via `werkdock run --rm`
  - **and** the configured `bwrap.werkdock` binary (default: `werkdock` via `PATH`) is what gets invoked.

##### Verified by

- [assembles the exact werkdock run command for a loaded image](../../src/test/kotlin/de/hoennig/werkator/build/BwrapBuildRunnerTest.kt)
- [loads the image once when werkdock does not know it yet](../../src/test/kotlin/de/hoennig/werkator/build/BwrapBuildRunnerTest.kt)
- [does not load an image werkdock already has](../../src/test/kotlin/de/hoennig/werkator/build/BwrapBuildRunnerTest.kt)
- [uses the configured werkdock binary path](../../src/test/kotlin/de/hoennig/werkator/build/BwrapBuildRunnerTest.kt)

#### Scenario#7.02: The git-metadata mask survives the move to ordered `-v`/`--tmpfs` flags

So that secrets stay outside the sandbox exactly as before, now expressed as flag order instead of an internal mount list.

- **Given** a worktree build
- **When** the invocation is assembled
- **Then** `.git` is bound read-only, then `.git/werkator/` is masked with `--tmpfs`, then the worktree's admin dir is bound read-write, in that exact order
  - **and** Werkdock's `Mount` list (replacing the earlier unordered `Bind` list) preserves the order flags were given in.

##### Verified by

- [exposes git metadata read-only with the werkator dir masked, in mount order](../../src/test/kotlin/de/hoennig/werkator/build/BwrapBuildRunnerTest.kt)
- [mounts no git metadata when the workspace is not a worktree](../../src/test/kotlin/de/hoennig/werkator/build/BwrapBuildRunnerTest.kt)
- [TestParseRunKeepsMountFlagOrderAcrossVolumeAndTmpfs](../../werkdock/internal/cli/run_test.go)

#### Scenario#7.03: Werkdock gained what the delegation needed

So that `images`/`:rw` were built because Werkator's runner needed them, not speculatively.

- **Given** the new `werkdock images` verb and `:rw` volume option
- **When** the runner checks whether an image is already loaded, or mounts the admin dir read-write
- **Then** `images` lists loaded image names (one per line, `docker images --format` shaped) and `-v src:dst:rw` is accepted alongside the existing `:ro`.

##### Verified by

- [TestListNamesLoadedImagesAndIgnoresTmpLeftovers](../../werkdock/internal/store/store_test.go)
- [TestParseRunAcceptsTheExplicitRwVolumeOption](../../werkdock/internal/cli/run_test.go)
- [TestImageNameFromArchive](../../werkdock/internal/store/store_test.go)

#### Scenario#7.04: The TMPDIR workaround is gone because it is now structurally impossible

So that the fix and its own workaround do not both linger in the codebase.

- **Given** Werkdock's `--clearenv`
- **When** a build runs in the sandbox
- **Then** no host `TMPDIR`/`TMP` reaches the sandboxed process at all, so `BwrapBuildRunner`'s earlier explicit `--setenv TMPDIR /tmp` workaround (PR #4) is removed as dead code, not merely redundant.

##### Verified by

- [adds bwrap env after the branch environment](../../src/test/kotlin/de/hoennig/werkator/build/BwrapBuildRunnerTest.kt)
- [TestArgvAssemblesTheHardenedInvocation](../../werkdock/internal/engine/bwrap_test.go)

### Feature: the webspace install path replaces the self-build prototype

#### Scenario#7.05: `tools/remote` separates the builder role from the built (watched) repository role

So that "build Werkator on the webspace" and "Werkator watches a repository on the webspace" are never conflated again.

- **Given** a Managed Webspace target
- **When** the wrapper manages the Werkator runtime versus a repository Werkator watches
- **Then** `instance-install`/`instance-update`/`instance-start` install and run the Werkator **builder** binary+bundle
  - **and** `repo-init` prepares a repository to be **built by** that instance
  - **and** the retired `install`/`build`/`start` commands fail loudly, naming their successors, instead of silently doing the old thing.

##### Verified by

- manual invocation of the retired commands on mih34 (shell script; no automated test harness for `tools/remote`)

#### Scenario#7.06: The self-build prototype is gone

So that Werkator is never again built by checking out its own source onto the target and compiling there.

- **Given** the old prototype cloned werkator's own repository onto the webspace and built it in place
- **When** an instance is installed or updated now
- **Then** the runtime bundle is built locally and transported (`instance-install`/`instance-update`), never cloned-and-built on the target.

##### Verified by

- live run on mih34: `instance-update` against a runtime bundle built locally

## The Solution

`BwrapBuildRunner.invocation()` no longer builds a `bwrap` argv; it shells out to the `werkdock` binary named by `bwrap.werkdock` (a new pinned config key, alongside `bwrap.enabled`/`bwrap.rootfs`) for `images`, `load`, and `run --rm`.
`werkdock/internal/engine/engine.go` was rewritten from an unordered `Bind` list to an ordered `Mount` list (`MountBind`/`MountRoBind`/`MountTmpfs`) specifically so the CLI's `-v`/`--tmpfs` flag order — which the git-metadata mask depends on — survives into the sandbox invocation unchanged.
`werkdock/internal/cli/images.go` is new; `run.go`'s volume parsing gained the `:rw` option.
`tools/remote` was reorganized around two roles instead of one flat command list: builder lifecycle (`instance-install`/`instance-update`/`instance-start`) versus watched-repository lifecycle (`repo-init`); the old `install`/`build`/`start` now `die` with the successor's name.
`require_idle()`/`FORCE=1` guards a runtime swap against a build in progress.

## Additional Changes

- `docs/deployment.md`: the webspace section now describes the role-separated commands.
- `docs/configuration.md` and `AGENTS.md`: `bwrap.werkdock` documented as a fourth pinned bwrap key.
- Step 21 plan: sessions C and D marked done with live-verification notes.

## Prerequisite PRs

- PR #6 (Werkdock bootstrap) — this PR is the consumer of the CLI it built.

## Follow-up PRs

- PR #8/#9: `tools/remote`'s remaining configuration-writing duplication with `werkator init` is resolved next (step 23).
- PR #10: multi-repository support for one Werkator instance (step 22).
