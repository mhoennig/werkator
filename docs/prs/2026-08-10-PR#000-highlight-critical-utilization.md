> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

The system page shows CPU, RAM, and disk usage as plain numbers.
Whether a value is harmless or critical only becomes clear after mentally comparing it against the totals in the info line.
Critical utilization should be visible at a glance.

## Non-Goals

- No configurable thresholds; 80%/90% are hardcoded like the rest of the UI styling.
- No highlighting of min/max/avg — they are historical, only the current value is actionable.
- No alerting or notifications; this is display only.

## The Scenarios

### Feature: critical utilization is highlighted on the system page

#### Background

- The utilization metrics with a total are: CPU used vs. CPU count, RAM used vs. RAM total, disk used vs. disk total.
- Free/idle rows and the repo size have no meaningful utilization ratio and are never highlighted.

#### Scenario#000.01: The current value is highlighted from 80% (warn) and 90% (critical) of its total

So that critical load is visible at a glance.

- **Given** a utilization metric with an available total
- **When** the current value reaches 80% of the total
- **Then** the Current cell is highlighted orange (`metric-warn`)
  - **and** from 90% it is highlighted red (`metric-crit`)
  - **and** below 80% it stays unstyled

##### Verified by

- [UiViewsTest — "utilization highlights warn from 80% and crit from 90% of the total"](../../src/test/kotlin/de/hoennig/werkator/server/UiViewsTest.kt)
- [UiViewsTest — "only the used rows with a total get the critical highlighting"](../../src/test/kotlin/de/hoennig/werkator/server/UiViewsTest.kt)

#### Scenario#000.02: Unavailable metrics are never highlighted

So that hosts without `/proc` (no metrics, `n/a` cells) render unchanged.

- **Given** a metric or its total is unavailable (null, NaN, or zero)
- **When** the system page renders or polls
- **Then** no highlighting class is applied

##### Verified by

- [UiViewsTest — "utilization highlighting is off when a value or the total is unavailable"](../../src/test/kotlin/de/hoennig/werkator/server/UiViewsTest.kt)

## The Solution

`UiFormats.utilizationClass(used, total)` returns `""`/`metric-warn`/`metric-crit`; `gittally.js` mirrors it in `utilizationClass` — per the UI invariant, server-rendered HTML and the polling script produce identical output.
`MetricRowView` carries a `currentClass` applied via `th:classappend`; the poller toggles the same classes on the Current cell using a metric-to-total map (`UTILIZATION_TOTALS`).
The CSS reuses the existing badge color variables (`--interrupted-*` for warn, `--failed-*` for critical), so light and dark mode work without new colors.

## Follow-up PRs

- None planned.
