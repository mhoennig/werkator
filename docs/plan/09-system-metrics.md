# Step 09: System Metrics

Prerequisites: step 07 (API), step 08 (layout).
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Port the legacy system page: CPU, RAM, disk, and repository size with min/max/avg aggregation.

## Design

Create package `de.hoennig.werkator.metrics`:

- `SystemMetricsCollector` sampling every 60s (server profile only):
  CPU used/idle from `/proc/stat` deltas, RAM from `/proc/meminfo`, disk from `java.nio.file.FileStore`, repo size via periodic `du -sk` (or a file walk) — throttle repo-size sampling (legacy ran `du` every cycle, which was expensive).
- Keep running min/max/avg per metric since server start; persist aggregation state in the artifact root so restarts continue the series (legacy `system_state.dat`, but as JSON).
- `GET /api/system` returning the current snapshot plus aggregates (legacy `system.json` fields are the reference).
- `/system` HTML view in the step 08 layout, polling `/api/system` every 60s with the same error-badge rules.

## Out of Scope

- Alerting, historical time series, external monitoring integration.
- Windows/macOS support beyond graceful degradation (missing `/proc` → metric shows "n/a").

## Tests

- Collector unit tests with fake `/proc` file content (read paths injectable).
- Aggregation math: min/max/avg over samples, persistence round-trip.
- Controller slice test for `/api/system`.
- Graceful degradation when a source is unreadable.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- `/system` renders live values on Linux (manual smoke test; document in this file).

## Implementation Notes (2026-07-07)

Implemented as designed: `SystemMetricsCollector` in `de.hoennig.werkator.metrics` samples every 60s once `ServerMetricsLifecycle` (server profile only) calls `start()`, following the watcher's start/stop pattern.
CPU comes from `/proc/stat` deltas, RAM from `/proc/meminfo`, disk from `java.nio.file.FileStore` (`df` semantics: used = total − unallocated, free = usable), and the repository size from a file walk.
`GET /api/system` returns the snapshot plus aggregates, and `/system` renders the legacy system page in the step 08 layout, polling every 60s with the same timeout/error-badge rules.
Since the metric rows are fixed, `werkator.js` only updates the cell texts in place — nothing is rebuilt.

Deviations and decisions:

- The JSON uses camelCase fields with nested `{current, min, max, avg}` aggregates instead of the flat snake_case legacy `system.json`; the value set matches legacy.
- The aggregation state persists as `system-metrics-state.json` in the artifact root and restarts continue the series, as this step requires.
  Legacy actually deleted `system_state.dat` on every start, so the footnote now reads "since the first server start" instead of "since script start".
  `ArtifactStore` gained `rootDir()` so the state can live next to the stored builds.
- CPU load needs a counter delta, so the first sample after process start reports no CPU metric yet (`n/a`); legacy aggregated a meaningless near-zero first delta instead.
- The repository size is re-probed only every 10th sample (10 minutes) and reused in between — the throttle this step requires; legacy ran `du -sk` every cycle.
  The file walk sums file sizes, not disk blocks like `du`, which is close enough for a trend metric.
- An unavailable source (no `/proc` outside Linux, unreadable file store) yields explicit `null` metrics over HTTP 200 and `n/a` cells; the failure is logged once, not every 60s.
- No new config keys: the 60s interval is fixed like legacy, so `WerkatorConfig`, the `init` templates, and `docs/configuration.md` are unchanged.
- The legacy `generation` field was not ported; it only guarded the legacy JS against monitor restarts.
- The CPU count comes from `Runtime.availableProcessors()` instead of `nproc`.

Manual smoke test (2026-07-07): scratch repository with a bare origin, server on port 18986, observed through a real browser tab (via a TCP proxy, so the tab outlived backend restarts).
The first sample rendered RAM/disk/repo values immediately with CPU `n/a` and the totals line (`8 cores`, RAM/disk GiB, updated time).
After the next 60s poll the open tab updated in place without reload: the updated time ticked, CPU used appeared (1.58 cores, idle 6.42 = 8 total), and min/max diverged.
Killing the server flipped the indicator to the red `error` badge and dimmed the table — zero spinners; after a restart the tab returned to `live` and the series continued from the persisted state (`sampleCount` 5, min/max from before the restart preserved).
The 375px viewport stacked the rows as labeled cards, and SIGINT shut the server down cleanly (exit 130, no exceptions).
