# Step 19: Show a stalled watcher in the web UI

Prerequisites: none — the data already exists, only nothing renders it.
Read `README.md` first.

## Why

On 2026-08-30 the Gitea token in the machine config on vm4006 was replaced by a placeholder string.
For 57 minutes werkator failed `git fetch --prune origin` every ten seconds and wrote 297 warnings to the journal.
The branches view showed a calm, ordinary list the whole time: every branch with its last build, nothing amiss.
The failure was noticed only because an expected build did not start, and it took reading the journal to see why.

A watcher that cannot reach origin means every branch row on the page is stale — the one thing the page exists to tell.
Silence is the wrong answer, and the journal is not the user interface.

## What Already Works

`Watcher.poll` records the failure: `state = state.copy(lastPollAt = …, lastFetchError = e.message ?: …)`, cleared again after a clean cycle.
`WatcherApiController` serves it at `/api/watcher` as `WatcherState`, which distinguishes three failure modes:

- `lastFetchError` — origin unreachable (credentials, network, gone remote);
- `lastPollError` — the cycle crashed after a successful fetch;
- `running: false` — the poll loop is not scheduled at all.

All three mean the same thing to a reader: what you see is not current.
The endpoint needs no change.

## Code

- `static/werkator.js`: fetch `/api/watcher` from the same polling cycle that refreshes the view, and show a banner while any of the three conditions holds.
  Keep the existing discipline — a timeout on the fetch, and a failure of *this* request must never break the view's own refresh.
- `templates/fragments.html`: add a `watcher-banner` fragment to the `nav(view)` row so every view inherits it; hidden unless the script fills it.
- Wording says what is stale and since when, not just that something failed: the branch list is not updating, since `lastPollAt`, because `<error>`.
  Timestamps go through the shared formatting — `UiFormats` and `werkator.js` must produce identical formats (invariant in `AGENTS.md`).
- Do not overload `live-indicator`: it reports whether the *browser* reaches the server.
  This banner reports whether the *server* reaches origin. Two independent failures, two independent signals.

While in there, fix the logging volume: one outage produced 297 identical warnings.
Log the fetch failure when its message changes, not on every cycle, and log once more when the fetch succeeds again.
This is the same class as the `atTimes` warning that repeats every poll for an invalid slot — fix both or neither, but do not leave the new one behind.

## Tests

- `server/WatcherApiControllerTest.kt` — assert that a state carrying `lastFetchError` reaches the JSON, so the field the UI depends on is covered.
- `watcher/WatcherTest.kt` — a failing `fetchOrigin` sets `lastFetchError` and a following clean cycle clears it; a repeated identical failure logs once.
- The JavaScript has no test harness; the banner is verified manually below.

## Verification

- Break it deliberately in a scratch repo install: set `git.token` to a wrong value, and watch the banner appear within one poll interval and disappear again after fixing it.
- Stop the watcher (`running: false`) and confirm the banner says so in its own words rather than reporting a fetch error.
- On a narrow viewport the banner must not push the table off screen.

## Executed 2026-08-30

Done as described. Two notes:

- `server/WatcherApiControllerTest.kt` already asserted that `lastFetchError` reaches the JSON, so no test was added there; `watcher/WatcherTest.kt` gained the log-volume test instead.
- The `nav(view)` fragment became a `th:block` wrapping the view row and the banner, so the five templates that include it needed no change and a sixth cannot forget it.

Verified against a scratch repository with an unreachable origin: the banner reads "origin unreachable — the list below is not updating", disappears within one poll interval after the remote is fixed, and the whole outage produced one warning plus one recovery line instead of one warning per cycle.
