# Step 14: Build-Phase Timing and Orchestration Overhead

Prerequisites: steps 04 (build executor), 05 (artifact store), 07 (API), 08 (web UI), 11 (Docker runtime).
Read `README.md` first; read the referenced legacy functions only where this step points at them — the script lives in the history now, see `README.md`.

## Goal

Make the time between "commit picked up" and "build command starts" observable per build, and remove the known overhead sources, so that orchestration costs seconds instead of minutes.

## Motivation: Measurements from 2026-08-10 (legacy on vm2176, hsadmin-ng)

Every build paid about 2:00 minutes before Gradle started, on top of the build itself:

- 12:45:51 checkout done, build command announced.
- 12:46:49 "waiting for build lock" — a 58 s gap with no log output.
- 12:47:46 "Preparing Docker Gradle cache volume" — a 57 s gap after "acquired build lock".
- 12:47:49 Gradle starts; archiving and status publishing after the build cost close to nothing.

The suspects along that code path were measured and are all cheap:
the Gitea status POST takes 17 ms, the `prune_build_results` loop over 141 result lines takes 0.6 s, and `du -sk` over the 5.4 GiB workspace takes 0.9 s warm.
The two ~1-minute gaps therefore remain **unattributed** — that is the core problem: the orchestration has no per-phase timing, so its overhead can only be estimated by log archaeology (journal timestamps, artifact file mtimes).

Side findings from the same session:

- The legacy metrics loop runs `du -sk .` over the whole workspace every cycle and logs `Permission denied` every minute for root-owned leftovers under `buildSrc/build/tmp/` — the legacy ownership repair (`repair_docker_workspace_ownership`) only covers `build/` and `.gradle/`.
- The artifact store (5.2 of 5.6 GiB) lives inside the build repository's `.git/`, so every whole-tree scan (metrics, backups) pays mostly for artifacts.
- `write_current_build_page` rewrites static assets (favicon, about page, license page, script download) on every build-state change.
- The hsadmin-ng build relies on the persisted Gradle user home volume for its build cache (hsadmin-ng PR#282: mechanical tasks cached, test tasks pinned to always execute); the deployment for that repository must keep an equivalent persistent volume.

## Design

1. Record per-build phase timings as first-class data in the build result:
   `queued`, `checkout`, `clean`, `docker-prep`, `build`, `archive`, `status-published` — each with start timestamp and duration.
   Show them in the build detail view and in the JSON API; the build list shows total vs. build-command duration.
2. Log a warning when the non-build phases together exceed a configurable budget (default 30 s), naming the slowest phase.
   This turns a silent regression (like the unattributed 2 minutes above) into a visible finding.
3. Ownership repair covers every path the build container writes:
   either `chown` the whole worktree after the build, or run the build container with the host uid/gid so nothing root-owned is left behind.
4. The repository-size metric excludes the artifact store and the worktrees; the artifact-store size becomes its own metric.
5. Static assets (favicon, about, license, script download) are written once at startup, not per state change.
6. Status publishing and page regeneration run off the build-critical path: the build command starts as soon as checkout and clean are done.

## Interim for the still-deployed legacy script

Only if the legacy script keeps running for a while on vm2176:

- Prefix the orchestration `echo`s in `run_build` with `date -Iseconds` to attribute the 2 minutes.
- Extend `repair_docker_workspace_ownership` from `build .gradle` to also cover `buildSrc/build`.

Deploying the rewrite for the hsadmin-ng backend is the better investment than patching further.

## Out of Scope

- Optimizing the build commands themselves (that belongs to the target repositories).
- Historical phase-timing series or alerting; a per-build breakdown plus the budget warning is enough.

## Tests

- Phase timings recorded and serialized with the build result; round-trip through the repository.
- Budget warning fires when a fake clock pushes a non-build phase over the threshold.
- Ownership repair covers a root-owned file outside `build/` (container test, tagged like other Docker tests).
- Metrics: artifact store and worktrees excluded from the repository-size metric.

## Acceptance Criteria

- A build's detail page answers "where did the non-build time go" without shell access to the host.
- On an idle host, the non-build phases of a Docker build stay under the default budget.
- No root-owned files remain in the worktree after a Docker build.
