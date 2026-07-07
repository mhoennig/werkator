# Step 11: Docker Build Runtime (optional)

Prerequisites: step 04.
Read `README.md` and `00-legacy-analysis.md` first.
This step is optional; skip it until builds actually need container isolation.

## Goal

Run build commands inside a Docker container, as the legacy `--docker` mode did.

## Design

Implement a `DockerBuildRunner` for the `BuildRunner` interface from step 04, shelling out to the `docker` CLI (consistent with the git gateway approach; no Docker Java SDK dependency).

Port from legacy (see analysis, lines ~4400+):

- Ensure image: build from configured Dockerfile/context when missing or stale; track staleness via an image label holding the SHA-256 of Dockerfile + context (legacy `org.gittally.build-inputs-sha256`).
- Gradle cache volume per repository (`gittally-gradle-<repo-key>`), mounted and chowned to the host UID/GID.
- Run the build container: workspace mount, branch env var, configured extra env, network mode, docker socket mount for Testcontainers-based builds.
- Post-build ownership repair of the workspace (legacy `repair_docker_workspace_ownership`).
- Label all containers (`org.hoennig.gittally=true`, repository, role) and clean up stale ones on startup.

Decide during implementation whether the hsadmin-ng-specific legacy options (preflight command, `JAVA_TOOL_OPTIONS` injection) are needed; default to NOT porting them (see orphaned-config list in the analysis).

## Config

New `branches.<name>.docker` section: `enabled`, `image`, `dockerfile`, `context`, `network`, `env`.
Update `GitTallyConfig`, `InitCommand` templates, and `docs/configuration.md` together.

## Tests

- Unit tests for command assembly (assert the exact `docker run`/`docker build` argv) with a mocked command runner.
- Checksum staleness logic.
- Optional: one Testcontainers-gated integration test that runs a trivial build in a stock image; skip when Docker is unavailable.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green with Docker absent.
- Native execution path (step 04) is unchanged and remains the default.

## Implementation Notes (2026-07-07)

Implemented as designed: `DockerBuildRunner` (in `build/`) implements `BuildRunner` and shells out to the `docker` CLI via the generic `GitCommandRunner` process wrapper.
The runtime is selected per branch by `DispatchingBuildRunner` (`@Primary`), so `BuildExecutor` keeps a single `BuildRunner` dependency and the native `ProcessBuildRunner` stays the default.
No test needs Docker; the main `docker run` argv is asserted exactly through an injectable process launcher, everything else through the mocked command runner.

Ported from legacy:

- Image ensure (`ensure_docker_build_image`): rebuild when the image is missing or the `org.gittally.build-inputs-sha256` label no longer matches; all four `org.gittally.*` labels are set.
  Without a configured `dockerfile`, the image is used as-is and pulled by `docker run` on demand.
- Gradle cache volume `gittally-gradle-<repo-key>`, created and chowned to the host uid/gid with the legacy container script.
- Build container: workspace bind mount, `branch` env var, configured extra env, network mode, docker socket mount with `DOCKER_HOST`/`TESTCONTAINERS_*` for Testcontainers-based builds, `--add-host host.docker.internal:host-gateway` off host network.
- Ownership repair of `build/` and `.gradle/` (`repair_docker_workspace_ownership`).
- `org.hoennig.gittally` labels (role `build`) and stale-container cleanup.

Deviations and decisions:

- The `BuildRunner` interface gained `repoDir` and `branchConfig` parameters (with defaults), because runner selection and Docker settings are per branch and the per-repo volume/container names need the repository path — a worktree cannot resolve the uncommitted config layer.
- The hsadmin-ng-specific legacy options were not ported, as the step suggests: no preflight command, no `JAVA_TOOL_OPTIONS` injection, no `.testcontainers.properties` generation, no `HSADMINNG_*` env passthrough — `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`/`TESTCONTAINERS_HOST_OVERRIDE`/`DOCKER_HOST` cover modern Testcontainers; anything else fits `docker.env`.
- Ownership repair runs inside the same build container (wrapped around the command, preserving its exit code) instead of a follow-up root container; the separate `prepare_docker_workspace_build_dir` step became unnecessary because the clean command already runs in the container.
- Container names are per branch (`gittally-build-<repo-key>-<branch-key>`), not per repository, because builds of different branches may run concurrently.
- Containers run with `--init`, so termination signals from build cancellation reach the build process inside the container.
- Stale labelled containers are removed before the first Docker build of the process, not at daemon startup, so installations that never build in Docker never invoke docker.
- The Gradle volume is prepared once per process and image, not before every build.
- A missing unix socket skips the socket mount instead of failing the build (legacy errored); a tcp:// `DOCKER_HOST` also skips it.
- `docker.network` defaults to Docker's default network, not to `host` like legacy (host mode was an hsadmin-ng-ism); `network: host` switches the Testcontainers host override to `localhost` exactly like legacy.
- Image-input checksums are computed in-process (`DockerImageInputs`), not via `sha256sum`, with the same input format as legacy.

Manual smoke test (2026-07-07, scratch repo, Rancher Desktop 27.3.1):

- A scratch repo with `docker.enabled`, a two-line Dockerfile, and a build command writing `id -u` into `build/who.txt`: `gittally build` built the image with all four labels, created the `gittally-gradle-<repo-key>` volume, streamed the container output live, and exited 0 (`success after 0:14`).
- A second run reused the image (inputs label matched, no rebuild; `success after 0:04`).
- The command ran as uid 0 inside the container while `build/who.txt` ended up owned by the host user — the in-container ownership repair works.
- No labelled containers were left behind after the builds.
- Caveat found while testing (environmental, not GitTally): with a VM-based Docker (Rancher Desktop/Lima), workspace bind mounts only work for paths shared into the VM (e.g. `$HOME`); a repo under an unshared `/tmp` builds against an empty VM-side directory.
