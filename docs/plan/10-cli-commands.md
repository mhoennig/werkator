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

## Implementation Notes (2026-07-07)

Implemented as designed: `status`, `build`, and `retry` are picocli `@Component` subcommands in `commands/`, wired into `werkatorCommand` like the existing ones.
They implement `Callable<Int>`, so the exit codes align with `CliRunner`'s `ExitCodeGenerator` contract: 0 on success, 1 on build failure, 2 on usage/config errors (picocli's own `USAGE` code for invalid options matches).

- `status [--history]` reads `BuildResultRepository` directly and prints an aligned table (branch, status, commit, time, duration); it reuses `UiFormats`, so the console shows the same timestamp/duration formats as the web UI.
- `build [<branch>]` fetches best-effort, resolves the branch, enqueues one build on the async `BuildExecutor`, and blocks until it finished.
  `ConsoleBuildRunner` streams the live log to stdout while the build runs and afterwards waits (up to 30s) until the artifacts are persisted, so the CLI exit never cuts off the artifact copy.
- `retry` rebuilds every branch whose latest build is FAILED (like legacy `--retry`) at its origin head, sequentially, streaming each log; branches gone from origin are skipped.

Deviations and decisions:

- Branch fragment resolution (`BranchNameResolution`) ports legacy `resolve_branch_name` non-interactively: an exact name wins, a unique substring match resolves, and ambiguity lists the candidates and exits with code 2 — the legacy interactive selection prompt was not ported (a one-shot command should be scriptable).
- Commit selection follows the legacy flow without moving local refs: origin's head when the branch has new commits there, otherwise the local head — so unpushed local commits build as they are; origin-only branches build origin's head.
  `GitService` gained `localHeadCommit(branch)` for that.
- Unlike the watcher (and unlike legacy without new commits), an explicit `build` always builds, even when the commit was already built — that is the point of a one-shot command.
- A failed fetch only warns and the commands continue from the last-known origin state, so they work offline.
- `retry` only retries FAILED builds (legacy `branch_has_failed_build` checked exactly `failed`); interrupted/pending builds are the watcher's startup-recovery job.
- Exit code 130 for cancelled builds was not ported; a cancelled/interrupted build exits 1 like any non-success.
- Found while smoke testing: `.gitignore`'s `*.jar` rule excluded `gradle/wrapper/gradle-wrapper.jar`, so builds in fresh checkouts — including every werkator worktree — failed with `ClassNotFoundException: GradleWrapperMain`.
  Fixed with a `!gradle/wrapper/gradle-wrapper.jar` exception and by adding the jar (same class of defect as the `build/` rule fixed in step 04).

Manual smoke test (2026-07-07, in this repository):

- `status` with no recorded builds printed `(no builds recorded)`, exit 0.
- `build no-such-branch` printed `error: no local or origin branch matches 'no-such-branch'`, exit 2.
- Fetching origin fails on this machine (no credentials configured); every command printed the warning and continued from the last-known origin state as designed.
- The first `build` of the current branch (`main`) exposed the missing wrapper jar: the log streamed, the build failed after 0:00, exit 1 — correct behavior against a broken commit.
- After placing the wrapper jar (untracked files survive the worktree's forced checkout, so this mirrors the state once the `.gitignore` fix is committed), `build` streamed the whole Gradle log live and ended with `build of branch main: success after 0:53`, exit 0; both builds' logs and the `reports` dir arrived in the artifact root before the JVM exited.
- `retry` after the success printed `no failed builds to retry`, exit 0.
- `status` showed one aligned row (`main  success  a6a2c9de0b97  2026-07-07 13:15  0:53`); `status --history` additionally listed the earlier failed build.
