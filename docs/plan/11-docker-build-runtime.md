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
