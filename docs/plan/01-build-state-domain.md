# Step 01: Build State Domain and Repository

Prerequisites: none.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

A tested domain model for build results plus a persistent repository, replacing the legacy `build-results.tsv`.

## Design

Create package `de.hoennig.gittally.build`:

- `BuildStatus` enum: `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `INTERRUPTED`, `CANCELLED`.
  Add `isTerminal`, `isRestartable` (pending/running/interrupted) properties.
- `BuildResult` data class: branch, commit SHA, status, startedAt, duration, artifactKey.
  Use `java.time.Instant`/`Duration`; format only at the edges.
- `BuildResultRepository` interface: append, update status of latest entry for a branch, query latest per branch, query history, delete entry, prune.
- `FileBuildResultRepository`: JSON file at `.git/gittally/build-results.json`.
  Write atomically (write temp file, then `Files.move` with `ATOMIC_MOVE`).
  Reuse the Jackson YAML/JSON setup style from `ConfigLoader`.

Business logic to include (port from legacy, see analysis):

- `markStaleRunningAsInterrupted()` — called at startup; running → interrupted, superseded pending → interrupted.
- Retention pruning: keep N builds per branch (`artifacts.retentionPerBranch`); drop entries for branches no longer on origin (branch list passed in as a parameter, no git dependency here).

## Out of Scope

- No git access, no Gitea, no execution — pure domain and file I/O.
- Artifact directory pruning (step 05 consumes the pruning result).
- Age-based retention (legacy supported `h`/`d` suffixes); count-based only, extend later if needed.

## Config

Uses existing `artifacts.retentionPerBranch`.
No new keys expected.

## Tests

Kotest `FunSpec`, no Spring context needed.

- Round-trip persistence, atomicity (temp file cleaned up).
- Status transition helpers and `markStaleRunningAsInterrupted` edge cases.
- Retention pruning: per-branch count, removed branches, ordering by timestamp.
- Corrupt/missing file → empty repository, no crash (legacy failed silently; we log a warning).

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- New code has no dependency on picocli or web classes.
