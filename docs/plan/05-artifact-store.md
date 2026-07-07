# Step 05: Artifact Store

Prerequisites: steps 01, 04.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Persist build artifacts (logs plus configured report directories) with stable naming and retention pruning.

## Design

Create package `de.hoennig.gittally.artifacts`:

- `ArtifactStore` service implementing the interface stubbed in step 04.
- Artifact root: a configurable directory (new key `artifacts.rootDir`), defaulting to `${XDG_STATE_HOME:-~/.local/state}/gittally/artifacts/<repo-key>`.
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
Update `GitTallyConfig`, `InitCommand` templates, and `docs/configuration.md` together.

## Tests

- Key naming: sanitization, hash stability, collision of similar branch names.
- Persist: copies configured dirs, missing artifact dirs are skipped with a log line, atomic move (no partial dirs on failure).
- Prune: deletes exactly the unreferenced dirs, never anything outside the artifact root.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- Step 04's executor persists artifacts through the real store (integration test).
