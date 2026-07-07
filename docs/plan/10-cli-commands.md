# Step 10: CLI Commands

Prerequisites: steps 04, 05, 06.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

CLI parity for interactive use without the server.

## Design

New picocli subcommands (Spring components in `commands/`, wired like the existing ones):

- `status` — print the latest build per branch as a table (branch, status, commit, time, duration); `--history` for all builds.
  Reads `BuildResultRepository` directly; works while a server instance runs (file-based store, read-only).
- `build [<branch>]` — one-shot: fetch, checkout, build the given branch (default: current), print the log to the console, exit with the build's exit code.
  Replaces legacy `--stay` and explicit branch arguments.
  Support the legacy partial-name matching: resolve unique branch-name fragments, list candidates on ambiguity (legacy `resolve_branch_name`).
- `retry` — re-enqueue/build branches whose latest build failed (legacy `--retry`).
- Update the root command's subcommand list and `--help` texts.

Exit codes: 0 on success, 1 on build failure, 2 on usage/config errors (align with `CliRunner`'s `ExitCodeGenerator` contract).

## Out of Scope

- Talking to a running server over HTTP; direct file/executor access is sufficient for now.
- Shell completion.

## Tests

- Command unit tests with MockK-ed services (pattern of `InitCommandTest`).
- Branch fragment resolution: unique match, ambiguity handling, no match.
- Exit code assertions.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- `java -jar ... status` and `java -jar ... build` work in this repository (manual smoke test; document in this file).
