# Step 05: Artifact Store

Prerequisites: steps 01, 04.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Persist build artifacts (logs plus configured report directories) with stable naming and retention pruning.

## Design

Create package `de.hoennig.werkator.artifacts`:

- `ArtifactStore` service implementing the interface stubbed in step 04.
- Artifact root: a configurable directory (new key `artifacts.rootDir`), defaulting to `${XDG_STATE_HOME:-~/.local/state}/werkator/artifacts/<repo-key>`.
  Do NOT default to `/tmp` like legacy — artifacts vanished on reboot.
- Repo key: sanitized absolute repo path (legacy `repository_key`): non `[A-Za-z0-9._-]` → `_`.
- Artifact key per build: sanitized branch name + 12-char SHA-256 prefix, plus sanitized start timestamp + hash (legacy `build_artifact_key`); keep this scheme so URLs stay predictable.
- `persist(build, stagingDir)`: copy configured `artifactDirs` and the log files into staging, then atomically move staging → `<root>/branches/<artifactKey>/`.
- `prune(keptResults)`: delete artifact directories whose keys are no longer in the result repository (call after repository retention pruning from step 01).
- Provide `artifactDir(artifactKey)` lookups for the server (step 07).

## Out of Scope

- HTTP serving (step 07).
- HTML index pages (step 08 renders from data instead of generated files).

## Config

New key `artifacts.rootDir` (empty = platform default above).
Update `WerkatorConfig`, `InitCommand` templates, and `docs/configuration.md` together.

## Tests

- Key naming: sanitization, hash stability, collision of similar branch names.
- Persist: copies configured dirs, missing artifact dirs are skipped with a log line, atomic move (no partial dirs on failure).
- Prune: deletes exactly the unreferenced dirs, never anything outside the artifact root.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- Step 04's executor persists artifacts through the real store (integration test).

## Execution Notes (done 2026-07-07)

Implemented as `FileArtifactStore` in `de.hoennig.werkator.artifacts`; build green, 12 new tests
(`FileArtifactStoreTest`, `BuildExecutorArtifactIntegrationTest`, plus a `repoKey` case in `ArtifactKeysTest`).
Deviations and decisions:

- The `ArtifactStore` interface stays in `de.hoennig.werkator.build` (moving it would make `build` depend on `artifacts`).
  It gained `prune(keptResults)` and `artifactDir(artifactKey)`; the `NoOpArtifactStore` placeholder was removed.
- Interface gap from step 04 resolved by an additional parameter: `persist(build, stagingDir, workspace)`.
  The store copies the configured `artifactDirs` out of the branch worktree itself, so the archived layout stays store knowledge.
  `workspace` is null when a build crashed before its worktree was prepared; only the logs are stored then.
- Key naming was reused from step 04's `ArtifactKeys` (UTC `Instant`, not local time like legacy); only `repoKey` was added.
  The repo key sanitizes the absolute normalized working directory, not `git rev-parse --show-toplevel` — consistent
  with step 04's decision to resolve everything relative to the working directory.
- The atomic move does not move the staging directory directly: staging is a temp dir (usually under `/tmp`) and may
  be on a different filesystem than the artifact root, where a move is not atomic.
  Like legacy `persist_build_artifacts` (`$artifact_dir.tmp.$$`), everything is assembled in `.incoming-<key>` next to
  the target and then moved with `ATOMIC_MOVE`; on failure the incoming dir is deleted, so no partial dirs appear.
  The staging directory is deleted after a successful move.
- The legacy archive layout was ported: `build/reports` archives as `reports/`, every other artifact dir below `reports/<dir>`.
- Concurrency (builds run concurrently since the step 04 amendment): persists share a read lock — their target dirs are
  disjoint because artifact keys are unique per build — while `prune` takes the write lock, so it never deletes mid-persist.
  `prune` also removes `.incoming-*` leftovers of crashed persists, deletes symlinks without following them,
  and returns the removed keys.
- `artifactDir` rejects keys outside `[A-Za-z0-9._-]+` and anything resolving outside `<root>/branches/` (path traversal).
- `artifacts.rootDir` supports a leading `~/` and resolves relative paths against the repository;
  when empty, the default is `$XDG_STATE_HOME` (or `~/.local/state`) + `/werkator/artifacts/<repo-key>` as designed.
- The bean is wired in `ArtifactsConfiguration` with the working directory defaulting to `.`, mirroring `BuildConfiguration`.
