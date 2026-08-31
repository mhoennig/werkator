# Step 20: Track build duration over time

Prerequisites: the single-build duration already exists — `BuildResult.duration` (pure execution time from `runningSince`, without the queue wait) is shown in the History view, the JSON API (`durationSeconds`) and the CLI status.
Read `README.md` first.

## Why

Step 14's `Out of Scope` deliberately left out *historical* phase series and alerting; it only gave a per-build breakdown.
A per-build number answers "how long was the last run", not "is the build getting slower".
On a CI serving one repository the first sign of trouble is usually a build that used to take 6 minutes now taking 9 — before a failure, at a point where the per-build figure still looks fine.

This step turns the already-recorded durations into a trend, so a regression in build time is visible as it happens instead of after the fact.

## What Already Exists

- `BuildResult.duration` — set once on the final transition in `BuildExecutor` (`Duration.between(runningSince, now)`).
- `repository.history()` returns every recorded build, newest first, each carrying `duration`.
- The History view (`/history`, `/api/builds/history`) already renders `durationSeconds` per row via `BuildResultDto`.
- No aggregation, no trend, no comparison against history exists anywhere.

So the raw data is complete; nothing about build recording needs to change.

## Goal

A per-job (build-definition name) duration trend derived from the existing history, shown where the History view already lives.
The trend answers, for a chosen job and a bounded time window (default: last 30 days):

- how long each build took, chronologically;
- the running/window average, the min and the max;
- the slowest recent build and how far it sits above the window average — a visible outlier instead of a silent one;
- how the latest finished build compares to the window average (slower/faster by how much).

Derived on read from `repository.history()`, not stored: it is the same data, and deriving keeps the repository schema untouched and the numbers always fresh as the retention window prunes old runs.

## Scope Decision 1: whole build, not phases

This step tracks the *whole execution* duration — the number that tells a user "the build is getting slower".
Step 14 tracks the *phase* breakdown (where orchestration time goes) and already owns the per-build budget warning.
Keep the two apart: phase timing is Step 14's, whole-duration trends are this step's.
Only compute a trend where `duration` is not null (queued/cancelled builds have none and carry no signal for this purpose).

## Scope Decision 2: which builds go into the trend — jobs, not branches

Branch builds matter here as much as the primary branch, often more: on our projects the feature-branch build is the performance gate, not `main`.
But the history is already grouped by `name` (`branch`, or `<branch>@<build>`), and a branch may sit behind the machine config by up to a week, yet still build several times a day.
So the trend must **not** merge every branch into one per-job line — that would average a stale branch together with the current one and hide exactly the regression the feature exists to show.

Rule: group the trend by the history's own grouping — `name` — which is already the natural comparison unit (every "latest" and every retention pool is keyed by it).
A job defined on the host (`main@nightly`) gets its own line; each branch build (`mihoe/feature-x`) gets its own line.
Where `build == DEFAULT` the line is just the branch, and still distinct from every other branch and from every named job.
The series stays comparable because each line is a single, self-consistent grouping; the window average of `main@nightly` is never polluted by a branch that lags the config.

What this deliberately does *not* do is aggregate branches together for a project-level view.
"A branch build got slower" is a per-name question and is answered per line; conflating branches has no one right answer while they lag the config by different amounts.
The default UI shows the primary branch or the default job, and the other lines are a lookup, not a summary.

This assumes the per-branch duration itself is a fair comparison: every build of one `name` runs the same build steps with the same config (pinning strips only a fixed set).
A branch lagging the machine config by under a week still runs its committed `.werkator.yml` — see the branch-layer invariant — so its durations come from the same definition it has always run, and the trend is honest within that line.

## Design

1. `build/BuildDurationTrend.kt` — pure function over `List<BuildResult>`, grouped by `name` (the history's grouping — one line per name, see Scope Decision 2), optionally filtered by a `since: Instant` window:
   per name return the chronological `(startedAt, duration)` series plus window average, min, max, and the latest finished duration's deviation from the window average.
   Pure and dependency-free so it is unit-testable without Spring.
   Durations with a `null` `duration` are excluded up front.
2. `server/BuildsApiController.kt` — a `GET /api/builds/duration` returning the trend for all names over the default window. The History view polls it on the same cycle as the rest.
3. Web UI — a compact trend block on the History view (`/history`): for the primary branch (the default job), the series of recent durations with the window average and the latest build's delta, with an explicit marker when the latest build is slower than the window average by a margin (the exact ink and wording are free; the marker must call out *slowness* in the user's words, not just plot a line). The other names are a lookup, not a summary — see Scope Decision 2. Follow the existing discipline: a fetch timeout, and a failure of this request must not break the view's own refresh.
   `UiFormats` and `werkator.js` keep producing identical formats (invariant in `AGENTS.md`).
4. CLI — `status --history` already prints per-build duration; add nothing to the CLI. The trend is a server-side view; the CLI has no need to chart it.

## Out of Scope

- Phase timing and the overhead budget warning — Step 14.
- Storing a time series separate from the build results; the retention window already prunes old runs, and the trend lives where the history lives.
- Alerting or notifications (Webhook, Gitea status) on a slow build — this step surfaces the trend in the UI; wiring it to push is a later concern and belongs nowhere here.

## Tests

- `build/BuildDurationTrendTest.kt` — grouping by name (a branch build and a named job stay separate, never merged); the window filter; a `duration == null` build is excluded; average/min/max correct for an asymmetric series; the latest build's deviation (slower and faster) against the window average.
- `server/BuildsApiControllerTest.kt` — a history with several durations across two names resolves through `/api/builds/duration` to the expected per-name trends.
- The JavaScript has no test harness; the trend block is verified manually below.

## Documentation

- `docs/configuration.md` — nothing configurable is added (the window is a constant for now); no config key changes.
- `docs/adrs/` — none: this repeats no architectural decision, it derives read-only from existing data.
- `AGENTS.md` web-UI invariant is already covered; no new invariant.

## Verification

- In a scratch install, run several builds of different durations (e.g. by building on commits with different workloads), then open `/history` and confirm the trend shows the series, the window average, and a slower-latest marker on the last run.
- Delete a run via the API and confirm the trend reflects the pruned history, since it is derived on read.
- On a narrow viewport the trend block must not push the table off screen.

## Production

Nothing to deploy beyond the next release: the feature is read-only over existing history and needs no config or migration on vm4006.
Deploy as usual.
