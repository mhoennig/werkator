> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

The bwrap work merged as PR #4 drifted from the original intent (plan step 21): it built Werkator *on* the webspace instead of extracting the generic sandbox machinery into a reusable tool.
This PR starts that extraction: **Werkdock**, a docker-like sandbox CLI over `bwrap` — filesystem isolation only — grown in the `werkdock/` subdirectory until it stands on its own.
The concrete goal of the session (decided 2026-09-01): the sandbox builds of Werkator, Werkbaum, and Werkdock itself must work on a Managed Webspace.
Getting there surfaced and fixed three real defects: the rootfs archive silently lost every directory named `sys`/`proc`/`dev` (tar exclude patterns are unanchored by default), the sandbox inherited the host's pam_tmpdir `TMPDIR` and broke every tool honoring it, and non-report build artifacts were stored mislabeled under `reports/` and never shown in the UI.

## Non-Goals

- OCI image pull, the Docker Engine API daemon, and Testcontainers support (RFC 0002 levels 2 and 3, deferred indefinitely).
- Persistent Werkdock instances (`run` currently requires `--rm`) and the verbs beyond `doctor`/`load`/`run`.
- Session C (Werkator's `BwrapBuildRunner` delegating to the `werkdock` CLI) and session D (the webspace install path replacing the self-build prototype).
- Composable toolchain mounts (RFC 0003 stays a candidate).
- Multi-repository support for one Werkator instance.

## The Scenarios

### Feature: Werkdock, a docker-shaped sandbox CLI

#### Background

- An *image* is a rootfs archive, unpacked into the store at `$WERKDOCK_HOME` (default `~/.werkdock`); an *instance* corresponds to a docker container.
- The contract is filesystem isolation only: network, uid mapping target, `/proc`, `/dev`, `/tmp` come from the host.
- RFC 0001 decided Go (stdlib-only, one static binary); RFC 0002 decided the docker-compatible surface.

#### Scenario#6.01: A command runs inside the sandbox as root with a clean environment

So that builds are reproducible and docker knowledge transfers.

- **Given** a loaded image and the bwrap CLI on the host
- **When** `werkdock run --rm -v /repo:/repo -e CI=true -w /repo IMAGE sh -c '...'` is invoked
- **Then** the command runs with uid 0 mapped to the calling user, the rootfs read-only at `/`, tmpfs on `/tmp` and `/root`
  - **and** the environment is cleared (`--clearenv`) with `HOME`/`PATH` set explicitly and the `-e` variables applied
  - **and** the command's exit code is passed through (werkdock's own errors exit 125, like docker).

##### Verified by

- [TestArgvAssemblesTheHardenedInvocation](../../werkdock/internal/engine/bwrap_test.go)
- [TestRunInsideRealSandbox](../../werkdock/internal/engine/bwrap_test.go) (gated: skips without bwrap/userns)
- [TestRunPassesTheExitCodeThrough](../../werkdock/internal/engine/bwrap_test.go)

#### Scenario#6.02: Docker flags whose promise cannot be kept are refused loudly

So that a docker user is never silently under-isolated.

- **Given** the docker-shaped `run` flag surface
- **When** `-p`, `--network`, `--memory`, `--cpus`, `--user`, or `-d` is passed
- **Then** the invocation fails with the reason, never a silent no-op
  - **and** `run` without `--rm` fails with "persistent instances are not implemented yet".

##### Verified by

- [TestParseRunRefusesDockerFlagsLoudly](../../werkdock/internal/cli/run_test.go)
- [TestParseRunRequiresRmForNow](../../werkdock/internal/cli/run_test.go)

#### Scenario#6.03: Images load atomically and mountpoints are pre-created

So that a failed load leaves no half image and bind targets missing from a read-only rootfs cannot fail the run.

- **Given** a rootfs archive
- **When** `werkdock load -i ARCHIVE` imports it and a later `run` binds paths the rootfs does not ship
- **Then** the image is unpacked to a temp directory and renamed into place (a broken archive leaves nothing behind)
  - **and** missing bind mountpoints are pre-created inside the rootfs — directories for directory sources, files for file sources
  - **and** a bind destination escaping the rootfs is refused.

##### Verified by

- [TestLoadUnpacksArchiveIntoTheStore, TestLoadLeavesNoHalfImageOnFailure](../../werkdock/internal/store/store_test.go)
- [TestEnsureMountpointsCreatesMissingAndSkipsExisting, TestEnsureMountpointsRefusesEscapingDestinations](../../werkdock/internal/engine/bwrap_test.go)

#### Scenario#6.04: `werkdock doctor` decides whether a host can run sandboxes

So that a broken host fails loudly before the first build, in the PASS/FAIL format of the prerequisites script it ports.

- **Given** a target host
- **When** `werkdock doctor [TARGET_DIR]` runs
- **Then** it checks the userns probe (uid 0 inside, uid_map back to the caller, read-only root enforced), tar/zstd, free space, and group-quota headroom
  - **and** exits non-zero when any check fails.

##### Verified by

- [doctor_test.go](../../werkdock/internal/doctor/doctor_test.go) (probe evaluation, df/quota parsers incl. wrapped quota lines)

### Feature: Werkdock builds itself on the webspace

#### Scenario#6.05: The `werkdock` build definition compiles the Go module in the sandbox

So that "Werkdock builds itself" is CI reality while it lives in this repository.

- **Given** the `builds.werkdock` definition in `.werkator.yml` and a build image containing the go toolchain
- **When** a commit is pushed
- **Then** the pool `<branch>@werkdock` runs gofmt gate, `go vet`, `go test`, and a static `go build`
  - **and** the binary is stored as a build artifact (verified live on mih34: the CI-built binary downloads and runs).

##### Verified by

- live run on mih34 (config change; the definition mechanics are covered by the existing build-definition tests)

#### Scenario#6.06: The rootfs archive contains everything its packages installed

So that a directory of the Go stdlib named `sys` is never again silently missing (seen live: "package internal/runtime/sys is not in std").

- **Given** `tools/build-bwrap-rootfs.sh`
- **When** the archive is packed
- **Then** only the top-level `/proc`, `/sys`, `/dev` mountpoints are excluded (anchored patterns), not every path component of that name.

##### Verified by

- live rebuild + archive listing (script change; asserted by the green go build in Scenario#6.05)

#### Scenario#6.07: The sandbox resets TMPDIR to its own /tmp

So that hosts with pam_tmpdir (`TMPDIR=/tmp/user/<uid>`) cannot break tools honoring TMPDIR inside the sandbox.

- **Given** a server environment carrying `TMPDIR`/`TMP`
- **When** `BwrapBuildRunner` assembles the invocation
- **Then** both are set back to `/tmp` before the configured environment, which can still override them.

##### Verified by

- [BwrapBuildRunnerTest](../../src/test/kotlin/de/hoennig/werkator/build/BwrapBuildRunnerTest.kt)

### Feature: honest artifact paths

#### Scenario#6.08: Non-report artifact directories keep their own paths and appear on the artifact page

So that a built binary is neither mislabeled below `reports/` nor invisible.

- **Given** a build with `artifactDirs` beyond `build/reports`
- **When** its artifacts are persisted and the artifact page is rendered
- **Then** `build/reports` still archives as `reports/` (the browsable report anchor and every existing link)
  - **and** every other directory archives at its workspace-relative path
  - **and** the page lists those files (capped at 200) with download links, logs staying in their own section.

##### Verified by

- [FileArtifactStoreTest](../../src/test/kotlin/de/hoennig/werkator/artifacts/FileArtifactStoreTest.kt)
- [UiControllerTest."artifact index lists plain files outside reports/…"](../../src/test/kotlin/de/hoennig/werkator/server/UiControllerTest.kt)

## The Solution

Werkdock is its own Go module in `werkdock/` — stdlib-only, no Gradle coupling, `CGO_ENABLED=0 go build` yields one ~3.5 MB static binary.
The layering anticipates RFC 0002 level 3: CLI verbs are thin frontends over `internal/engine` (a `RunSpec` behind an `Engine` interface, bwrap first, native namespaces possible later per RFC 0001), `internal/store` (images on disk), and `internal/doctor`.
The bwrap invocation is the port of the runner hardened live in PR #4 — mount ordering, mountpoint pre-creation, uid mapping — plus `--clearenv` (which makes Werkdock immune to the TMPDIR class of bugs by construction).
The decisions are recorded as RFCs in `werkdock/docs/rfcs/`: 0001 language (Go, over Rust/Python/Kotlin-Native/bash, ten-criteria scoring), 0002 docker-compatible surface (level 1 now, OCI and daemon deferred), 0003 composable toolchain mounts (candidate).
The build image grew into one fat trixie rootfs (JDK 21 headless + Go + Node/npm) and then shrank below the original JDK-only archive: 351 MB vs 375 MB, after trimming X11 (headless JDK), non-en/de locales, man/doc, and apt lists.

## Open Questions

- The version werkdock reports (`0.1.0-dev`) has no release process yet; it gets one when the repository split nears.

## Additional Changes

- Step 21 plan: session notes, the deferral decisions, and the builder-vs-built role tangle in `tools/remote` noted for session D.
- ADR 0008 (bwrap runtime) written; step 17 and the plan README now point at 0008 (0007 was already taken).
- PR-docs of PR #3 and PR #4 renamed from their `PR#000` placeholders.
- Architecture skill: the third runtime documented; AGENTS.md decision list caught up with ADR 0007/0008.
- `tools/build-bwrap-rootfs.sh` gained `--pkgs-extra`.
- `docs/configuration.md`: artifactDirs archiving described.

## Prerequisite PRs

- PR #4 (bwrap build runtime) — Werkdock ports its hardened invocation.

## Follow-up PRs

- PR #7: session C (`BwrapBuildRunner` delegates to the `werkdock` CLI) and session D (the webspace install path replaces the self-build prototype in `tools/remote`).
- PR #8/#9: `tools/remote` and `werkator init` stop duplicating each other's configuration writing.
- PR #10: multi-repository support for one Werkator instance (step 22).
