# Step 02: Git Gateway

Prerequisites: none.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Extend `GitService` into a complete, tested gateway for all git operations the watcher and builder need.

## Design

Keep the existing approach: shell out to the `git` CLI via `ProcessBuilder`.
Extract a small `GitCommandRunner` (command + workingDir → exit code, stdout, stderr) so `GitService` becomes testable and readable.

Operations to add (legacy references in parentheses):

- `fetchOrigin()` and `fetchBranch(branch)` with authentication (legacy `git_with_gitea_token`, askpass).
  Use the `GIT_ASKPASS` environment technique: write a temp script returning `git.account` / `git.token` from config; `GIT_TERMINAL_PROMPT=0`.
  Only needed for HTTPS origins; skip auth setup for SSH origins.
- `localBranches()`, `originBranches()` (legacy `branch_candidates`).
- `hasNewCommits(branch)` — compare local head with upstream/origin (legacy `branch_has_new_commits`, `has_new_commits` via `rev-list`).
- `newOriginBranches(maxAge)` — origin branches without local counterpart whose latest commit is younger than `watcher.newBranchMaxAge` (legacy `recent_new_origin_branches`).
- `checkout(branch)` — switch, or create tracking branch from origin (legacy `switch_to_branch`).
- `resetHardToOrigin(branch)` (legacy `pull_branch_if_possible`).
- `commitTimestamp(sha)`, `currentBranch()`, `headCommit()`.

Parse the `newBranchMaxAge` duration format (`5d`, `12h`) in a small dedicated parser with tests.

## Out of Scope

- No polling loop (step 06), no build triggering.
- No Gitea API calls (step 03); only the askpass credential bridge is set up here.

## Tests

- `GitCommandRunner` unit tests.
- Integration tests against local fixture repositories: create a bare "origin" repo and a clone in a temp dir via the runner itself, then exercise fetch/branch/commit operations.
  No network access needed.
- Duration parser edge cases.
- Askpass script content test (do not test against a real remote).

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- No temp askpass files leak (created per invocation or cleaned via try/finally; legacy leaked them).
