# Legacy werkator Analysis

Condensed analysis of `legacy/werkator` (bash, ~6000 lines) as input for the rewrite.
Line numbers refer to the legacy script at the time of analysis (version 0.7.8).

## What the Legacy System Does

A single bash daemon per repository that:

1. Polls `origin` for changed local branches and recent new origin branches.
2. Checks out and builds each changed branch (natively or in Docker), one at a time.
3. Records results and publishes commit statuses to Gitea.
4. Archives build logs and report directories as browsable artifacts.
5. Serves a static-HTML web UI via an embedded Python HTTP server.
6. Optionally manages an nginx+certbot Docker container for HTTPS.
7. Installs itself as a systemd user service.

## Persistent State (formats to replace)

All state lives in files; there is no database.

| File | Format | Content |
|---|---|---|
| `.git/git-watch-origin-and-test/build-results.tsv` | TSV | `branch, commit, status, timestamp, duration(MM:SS), artifact_key` |
| `.git/git-watch-origin-and-test/auto-builds.tsv` | TSV | `branch, date, time_slot` — prevents double auto-builds |
| `.git/git-watch-origin-and-test/build.lock` | flock file | build mutex, holder found via `fuser`/`lsof` |
| `.git/git-watch-origin-and-test/cancel-*` | touch/token files | cancellation request/token/accepted handshake |
| `$TMPDIR/git-watch-origin-and-test/<repo_key>/` | HTML + files | artifact root: generated pages, per-build artifact dirs |
| `<artifact_root>/system.json` + `system_state.dat` | JSON + text | system metrics snapshot and aggregation state |

Build statuses: `pending`, `running`, `success`, `failed`, `interrupted`, `cancelled` (legacy alias `passed` → `success`).

Artifact key naming: sanitized branch + 12-char SHA256 prefix, plus sanitized timestamp + hash for per-build dirs.

## External Interactions

Git (always via CLI): `rev-parse`, `for-each-ref`, `fetch`, `switch`, `reset --hard`, `show -s --format=%cI`, `rev-list`.
HTTPS git auth uses a generated `GIT_ASKPASS` script feeding username + Gitea token.

Gitea API:

- `POST /api/v1/repos/{owner}/{repo}/statuses/{sha}` — publish status; payload `state`, `context`, `description`, `target_url`.
- `GET /api/v1/repos/{owner}/{repo}/commits/{sha}/statuses?sort=recentupdate` — read statuses, filtered by `context`.
- `GET /api/v1/user` — resolve username from token.
- State mapping: success→success; failed/interrupted/cancelled→failure; pending/running→pending.

Web control endpoints (Python handler):

- `GET /control/status?commit=<sha>&local_status=<s>` — proxy Gitea status for a commit.
- `POST /control/cancel` — cancel running build (CSRF-token protected).
- `POST /control/restart` — append a new pending result row.
- `POST /control/delete` — remove a result row and patch HTML files via regex.

## Root Causes of the Known Bugs

Stuck loading animation (UI):

- Status cells start as `status-loading` and each row fetches `/control/status` with a 15s timeout; failures leave the spinner forever (no error state).
- Page auto-refresh re-fetches the page HTML and diffs `#build-rows`; HTML is regenerated server-side by regex-patching files, which fails silently when structure drifts.
- "Current" and "System" nav views exist, but the page generator only implements `latest`/`branches`/`history`, so some views fall through.

No status changes observable during a build (control loop):

- The main loop (lines ~4944–4966) is synchronous: `checkout_and_build` blocks until the build ends; only then is origin re-scanned.
- `retry_origin_change_check()` is an uninterruptible internal retry loop with 10s sleeps.
- The console spinner is cosmetic and runs independently of actual progress.

## Behavior Worth Preserving

- Startup recovery: mark stale `running` builds as `interrupted`; restart restartable builds; optional retry of failed builds.
- New-branch age filter (`newBranchMaxAge`) to avoid building stale branches.
- Auto-build time slots (UTC HH:MM) per branch with per-day/slot dedup.
- Retention per branch by count or age, pruning both results and artifact dirs.
- Per-branch build/clean command, artifact dirs, and log file names (now via `branches:` YAML config).
- Cancellation with token handshake; process-tree termination (TERM, wait, KILL).
- Build log capture: stdout/stderr to files plus a live "current build" log.
- Gitea status published on every transition with `target_url` pointing at the artifact page.

## Not Ported (decided)

- nginx + certbot/Let's Encrypt container management — replaced by deployment documentation (step 12).
  Revised by ADR 0005: it IS needed for Hostsharing container hosts and returns as an opt-in feature (step 13).
- Self-install (`--install`), self-update script generation — replaced by jar deployment plus systemd docs (step 12).
- Legacy `HSADMIN_NG_*` environment fallbacks and env-file config — replaced by YAML config (done).
- Regex-based in-place HTML patching — replaced by server-rendered pages/JSON endpoints.
- Static HTML artifact index generation with embedded 900-line JavaScript — replaced by templates.
- `.aliases` sourcing (line 731), impressum footer link handling stays optional/simple.

## Orphaned or Dubious Legacy Config

Verify need before porting any of these:

- `werkator_BUILD_DOCKER_PREFLIGHT_COMMAND`, `werkator_BUILD_DOCKER_JAVA_TOOL_OPTIONS` — highly hsadmin-ng-specific defaults.
- `werkator_ARTIFACT_NGINX_*`, `werkator_ARTIFACT_LETSENCRYPT_EMAIL` — dropped with nginx management; revived as `server.nginx.*` by step 13 (ADR 0005).
- `werkator_IMPRESSUM_URL` — keep as optional simple footer link if wanted.
- `werkator_INSTALL_DIR` — dropped with self-install.
