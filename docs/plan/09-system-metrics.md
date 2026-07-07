# Step 09: System Metrics

Prerequisites: step 07 (API), step 08 (layout).
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Port the legacy system page: CPU, RAM, disk, and repository size with min/max/avg aggregation.

## Design

Create package `de.hoennig.gittally.metrics`:

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
