# Security Audit and Hardening

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## Related Links

- ADR 0004 — server-rendered UI, systemd behind the host's reverse proxy (the deployment posture this audit assumes).
- ADR 0005 — opt-in managed nginx/TLS container for hosts without a reverse proxy (e.g. Hostsharing, a multi-tenant host — relevant to the secret-file findings).

## The Audit Summary

A security audit of the Kotlin/Spring source was performed on 2026-07-08.
It found no critical injection or memory-safety class bug — the classic sinks are correctly closed (see [Verified Safe](#verified-safe-checked-not-vulnerable)).
The real weaknesses are deployment-posture and secret-handling issues.

Two threat actors frame the findings:

- Anyone who can push a branch or open a pull request — they influence branch names and the built commit.
- Anyone who can reach the HTTP port — the UI and API are unauthenticated by default and bind `0.0.0.0`.

The whole security model leans on an external reverse proxy providing access control.
Nothing in the app enforces that, and the one in-app control (the control token) is undermined by how it is distributed.
The highest-impact confidentiality issue is a world-readable Gitea-token file on exactly the multi-tenant host GitTally is meant to run on.

This PR tracks the remediation as a checklist.
It may be split into smaller PRs per severity; the filename and scenario IDs use the `PR#000` placeholder until a pull request is opened.

## Non-Goals

- The applicaiton has deliberately no authentication/authorization layer (no Spring Security, no user accounts) — the reverse proxy stays the primary access gate.
- No re-audit of the confirmed-safe areas; they are recorded here so future changes do not silently regress them.
- TODO 6 introduces a layered config where the build worktree overrides build-specific keys; it does **not** let the worktree override secrets, Gitea/server settings, or the sandbox policy — those stay pinned to `.git`/primary.

## The Identified Issues

The remediation plus one design change (TODO 6), grouped by severity.
Each item is a TODO with the background that justifies it.

### High

#### TODO 1 — Restrict the Gitea-token config file to the owner at creation

- [x] In [`InitCommand.kt:86-106`](../../src/main/kotlin/de/hoennig/gittally/commands/InitCommand.kt), create `.git/gittally/.gittally.yml` and its parent `.git/gittally/` with `0600`/`0700`, atomically at creation (as [`GitAskPass`](../../src/main/kotlin/de/hoennig/gittally/git/GitAskPass.kt) does), not via plain `writeText` at the umask default.
  **Done:** both `init` paths now go through [`SecretFiles`](../../src/main/kotlin/de/hoennig/gittally/SecretFiles.kt), which sets the mode as a file attribute at creation.

**Background.**
`init` creates the file it labels "secrets" — where the operator pastes the Gitea API token — with `writeText` and no permission restriction, so it inherits the umask (typically `0644`, world-readable).
The shell path (`tools/setup-gittally-instance:245-253`) already does `chmod 600` on its equivalent file, and `ControlTokenService`/`GitAskPass` both restrict theirs — the Kotlin `init` path is the lone exception.
On a shared host (Hostsharing is a multi-tenant deployment target, per ADR 0005) any local user can then read the token, which grants git push and commit-status access to the repo.

#### TODO 2 — Stop handing the control token to every unauthenticated reader

- [x] Reconsider the token distribution in [`UiController.kt:186`](../../src/main/kotlin/de/hoennig/gittally/server/UiController.kt) and [`fragments.html:9`](../../src/main/resources/templates/fragments.html): do not embed the live token in public HTML, or gate the pages behind the same check as the mutations.
  **Done in v0.9.10, without gating the pages.** Public read access is a requirement, not an oversight: build states, logs and artifacts must stay linkable without a login.
  So the `<meta>` tag is gone and `gittally.js` keeps the token in `localStorage`, asking for it once per browser (the operator reads it from `.git/gittally/control-token`, which needs shell access to the host).
  The token is a real secret again, and as a request header it stays inherently CSRF-safe — a foreign origin cannot set it without a CORS grant.
  A login with a session cookie (the alternative that would also hide the buttons from visitors) stays possible later; it would revise ADR 0004 and needs its own PR.
- [x] At minimum, document loudly that under the current design any read access equals full write access.
  Superseded: read access no longer implies write access. `docs/deployment.md` gained a "Control Token" section describing the split.

**Background.**
Every server-rendered page embeds the live control token in `<meta name="gittally-control-token">` so [`gittally.js`](../../src/main/resources/static/gittally.js) can read it, but no GET is authenticated.
So `curl -s http://host:18080/ | grep gittally-control-token` yields the token, which unlocks restart/cancel/delete.
Read access therefore equals write access, and the token provides no real second trust tier.
Blast radius is limited to build-lifecycle operations (a DoS/integrity concern, not secret disclosure or RCE), which is why this is a design flaw rather than Critical — but the token gives a false sense of protection.

### Medium

#### TODO 3 — Accept the control token via header only

- [x] Remove the `token` query-parameter variant from the three mutating endpoints in [`BuildsApiController.kt:90,115,129`](../../src/main/kotlin/de/hoennig/gittally/server/BuildsApiController.kt); keep only the `X-GitTally-Token` header (which the bundled UI already uses).

**Background.**
Tokens in URLs are routinely written to access logs, reverse-proxy logs, browser history, and the `Referer` header on outbound navigation.
The token never expires (static file secret), so any historical log capture yields a still-valid credential.
The query-param path exists only for legacy convenience.

#### TODO 4 — Redact the Gitea token in `config:print`

- [x] Mask `git.token` (and any future secret) by default in [`ConfigPrintCommand.kt:20-31`](../../src/main/kotlin/de/hoennig/gittally/commands/ConfigPrintCommand.kt); gate the plaintext value behind an explicit `--show-secrets` flag.
  Masked as `***` on both the `--full` and the raw path, with a leading YAML comment naming the flag, so the output stays parseable when piped.
- [x] Update `tools/setup-gittally-instance:272`, which currently steers the operator to run `config:print --full` to view the token.

**Background.**
Both the `--full` and default branches print the token verbatim to stdout, landing it in terminal scrollback, `script(1)` captures, screen-shares, or CI logs.
There is no redaction and no masked default.

#### TODO 5 — Reduce information disclosure on the unauthenticated read API

- [x] Decide whether build-log streaming ([`BuildsApiController`](../../src/main/kotlin/de/hoennig/gittally/server/BuildsApiController.kt) `/api/builds/current/{key}/log`), [`SystemApiController`](../../src/main/kotlin/de/hoennig/gittally/server/SystemApiController.kt), and [`WatcherApiController`](../../src/main/kotlin/de/hoennig/gittally/server/WatcherApiController.kt) should stay fully public, or be gated / scrubbed.
  **Decided: they stay fully public, no scrubbing.** The watched projects (GitTally itself and hs.hsadmin.ng) are open source, the repositories hold no secrets, and the builds run tests against test data — credentials appearing in a log are fixtures, not real ones. Builds neither deploy nor sign; the only planned artifact is a jar.
  Public logs are also the point of the tool: a red build must be diagnosable from the link in the Gitea status without a login.
  This is a property of the watched project, not of GitTally: an installation whose builds touch real credentials must keep its instance off the public internet (reverse proxy or `bindAddress: 127.0.0.1`), because GitTally offers no per-endpoint gating.

**Background.**
Every read endpoint is public.
Raw build output may contain secrets echoed by build scripts; `/api/system` exposes host metrics; `/api/watcher` exposes last fetch/poll error strings, which can leak git remote URLs or error detail.

### Design change — layered build config (Medium)

#### TODO 6 — Layer build config over the worktree, with secrets and sandbox pinned to `.git`

A branch is built with its own build settings: `.gittally.yml` from the **build worktree** (the commit being built) overrides the `.git`/primary config — except for a pinned set that a branch must never control. **Implemented in this PR.**

- [x] Worktree config layer for builds via [`ConfigLoader.loadForWorktree`](../../src/main/kotlin/de/hoennig/gittally/config/ConfigLoader.kt), wired into the two build-time config consumers ([`BuildExecutor.branchConfig`](../../src/main/kotlin/de/hoennig/gittally/build/BuildExecutor.kt), [`FileArtifactStore.branchConfig`](../../src/main/kotlin/de/hoennig/gittally/artifacts/FileArtifactStore.kt)) — both already hold the prepared worktree path. Precedence is worktree > `.git` > project.
- [x] **Pinned to `.git`/primary — stripped from the worktree layer:** `git`, `gitea`, `server` (secrets + server-side), and the sandbox policy `docker.enabled`/`docker.network`. A branch cannot disable its container or change its network mode.
- [x] Worktree-overridable: `buildCommand`, `cleanCommand`, `artifactDirs`, `stdoutLog`/`stderrLog`, `autoBuild`, and `docker.image`/`dockerfile`/`context`/`env`.
- [x] Pinned set enforced in code (`ConfigLoader.stripPinned`), documented in `docs/configuration.md`, and asserted as an invariant in `AGENTS.md`.
- [ ] **Deferred:** `autoBuild` scheduling and `requirePullRequest` are still read from the primary config, not the worktree — the watcher evaluates them *before* a build (and thus a worktree) exists. Sourcing them per-branch would need the watcher to read the branch's committed config directly (e.g. via `git show <branch>:.gittally.yml`); out of scope here.

**Background.**
The build command is executed via `bash -c "$3"` inside the build container ([`DockerBuildRunner.kt:285`](../../src/main/kotlin/de/hoennig/gittally/build/DockerBuildRunner.kt)).
Letting a branch define its own `buildCommand` is not a new risk — a CI already runs arbitrary code from that commit; the container is the sandbox.
The real escalation is a branch turning the sandbox **off**: if the worktree could set `docker.enabled: false` (or host `docker.network`), the build would run natively on the host — which is why those two keys are pinned.
Secrets are also safe from the build process (the Gitea token is used only by the server/watcher and is never placed in the build environment — `runCommand` passes only `mapOf("branch" to ...)`), and the whole `git`/`gitea`/`server` sections are stripped from the worktree layer as defense in depth.
Before this PR all build config was loaded from the primary checkout via `configLoader.load(build.workingDir)`, the worktree's `.gittally.yml` was never consulted, and `.git` took precedence over the committed project config — so this both adds the per-branch feature and reverses that assumption.

### Low / defense-in-depth

#### TODO 7 — Default `bindAddress` to `127.0.0.1`

- [x] Change the default in [`GitTallyConfig.kt:21`](../../src/main/kotlin/de/hoennig/gittally/config/GitTallyConfig.kt) and the `init` template ([`InitCommand.kt:126`](../../src/main/kotlin/de/hoennig/gittally/commands/InitCommand.kt)) from `0.0.0.0` to `127.0.0.1`; require operators to opt into all-interfaces.
  Shipped as v0.9.9 with the migration note in the release notes, `docs/configuration.md` ("Notes on `server.bindAddress`") and `docs/deployment.md`: existing configs keep their explicit value, and the managed nginx now needs `0.0.0.0` set deliberately.

**Background.**
The current default binds all interfaces, which — combined with the public read surface and token-in-HTML — exposes the whole UI and the control token to the network whenever the reverse proxy is forgotten.
The deployment doc already recommends `127.0.0.1`; the default and template should match it.

#### TODO 8 — Compare fixed-length hashes in the token check

- [x] In [`ControlTokenService.kt:35-37`](../../src/main/kotlin/de/hoennig/gittally/server/ControlTokenService.kt), compare `SHA-256(submitted)` against `SHA-256(secret)` with `MessageDigest.isEqual`, so the comparison is always over equal-length buffers.

**Background.**
`MessageDigest.isEqual` returns early on a length mismatch, leaking the token length via timing.
Largely theoretical given the 192-bit CSPRNG token, but a cheap deviation from constant-time best practice to close.

#### TODO 9 — Add `--` before positional refnames in git calls

- [x] Insert `--` before the branch argument in `checkout`, `fetchBranch`, and `resetHardToOrigin` in [`GitService.kt:145,40,155`](../../src/main/kotlin/de/hoennig/gittally/git/GitService.kt) (e.g. `git switch -- <branch>`).
  **Done for `checkout` and `fetchBranch`.** `resetHardToOrigin` keeps its plain form: `git reset --hard -- <commit>` is rejected (`fatal: Cannot do hard reset with paths`), and its argument is already prefixed with `origin/`, so it can never start with `-`.

**Background.**
Git accepts refnames beginning with `-` (verified: `git check-ref-format 'refs/heads/-foo'` exits 0), so a pushed branch name could in principle be read as a git option.
These three methods currently have no production callers and the actively-used paths embed the branch in a `refs/...` prefix, so this is dormant — but the guard should be in place before any of them is wired to server-triggered input.

#### TODO 10 — Create secret files restricted atomically

- [x] Set the mode at creation for the control-token file ([`ControlTokenService.kt:29-30`](../../src/main/kotlin/de/hoennig/gittally/server/ControlTokenService.kt)) and the setup-script YAML (`tools/setup-gittally-instance:253`), instead of `chmod 0600` after the write.
  **Done:** the control-token file via [`SecretFiles`](../../src/main/kotlin/de/hoennig/gittally/SecretFiles.kt), the setup script by writing the YAML in a `umask 077` subshell instead of `chmod`-ing afterwards.

**Background.**
Both currently write the file at the umask default and tighten it afterward, leaving a brief window where the secret exists world-readable.
A small TOCTOU gap; `GitAskPass`'s atomic-at-creation approach is the pattern to copy.

## Open Questions

- ~~TODO 2: full fix (gating the pages) versus documentation-only.~~ Settled by the operator: unauthenticated **read** access is a requirement — build states and artifacts must stay linkable without a login. So the pages are not gated; only the token distribution changed (v0.9.10).
- ~~TODO 5: whether public read access is acceptable by design (it matches legacy) or should change.~~ Settled by the operator: public reads are intended, and the watched open-source projects put no real secrets into their logs. See TODO 5 above.
- TODO 6: the pinned set is settled (secrets + Gitea/server + `docker.enabled`/`docker.network`); the open point is whether `docker.env` should also be pinned, since a branch overriding it controls its own container's environment (currently proposed as worktree-overridable).
- ~~TODO 7: changing the default `bindAddress` is a behavior change for existing installs that rely on `0.0.0.0`; needs a migration note.~~ Settled: the default changed in v0.9.9 and the migration note is in the release notes and both deployment docs.

## Additional Changes

- Update `docs/deployment.md` to state explicitly that the reverse proxy is a hard requirement, not a recommendation, and that read access currently implies write access via the embedded token (until TODO 2 lands).

## Follow-up PRs

- If TODO 2 grows into real UI authentication, that is a separate PR with its own ADR (it revises the "no auth layer" stance of ADR 0004).

## Attachments

### Verified Safe (checked, not vulnerable)

Recorded so future changes do not silently regress these.

- **Path traversal in artifact serving** — [`ArtifactFileController`](../../src/main/kotlin/de/hoennig/gittally/server/ArtifactFileController.kt) normalizes then enforces `startsWith(artifactDir)` and `isRegularFile(..., NOFOLLOW_LINKS)` (rejects symlink escape); [`FileArtifactStore`](../../src/main/kotlin/de/hoennig/gittally/artifacts/FileArtifactStore.kt) whitelists the key to `[A-Za-z0-9._-]+` and requires `dir.parent == branchesDir`.
- **Command injection** — every git/docker/certbot/nginx call uses `ProcessBuilder(List<String>)` ([`GitCommandRunner.kt`](../../src/main/kotlin/de/hoennig/gittally/git/GitCommandRunner.kt)); no `Runtime.exec(String)`, no shell-string interpolation; the only `sh -c`/`bash -c` uses pass data as positional args.
- **Branch-name → filesystem** — always routed through [`ArtifactKeys.sanitize`](../../src/main/kotlin/de/hoennig/gittally/build/ArtifactKeys.kt) (`/` → `_`) plus a SHA suffix, so worktree/container/volume names cannot traverse.
- **XSS** — no `th:utext` in any template; `gittally.js` builds all DOM via `createElement` + `textContent`/`dataset`, no `innerHTML`.
- **SSRF** — the Gitea status proxy validates the commit against `[0-9a-fA-F]{7,40}` ([`StatusApiController.kt`](../../src/main/kotlin/de/hoennig/gittally/server/StatusApiController.kt)); the Gitea base URL/owner/repo/token come from config, not the request.
- **Deserialization** — Jackson JSON/YAML without polymorphic/default typing (no gadget-chain RCE); unreadable state files degrade to empty.
- **Credential transport** — Gitea token sent as an `Authorization` header, never in a URL ([`GiteaClient.kt`](../../src/main/kotlin/de/hoennig/gittally/gitea/GiteaClient.kt)); git auth via env-based `GIT_ASKPASS` with a `0700` script containing no secrets, deleted in `finally`; TLS verification never disabled.
- **nginx/certbot** — `serverName`/`upstreamHost` validated against `[A-Za-z0-9][A-Za-z0-9.-]*` before substitution ([`NginxProxyManager.kt`](../../src/main/kotlin/de/hoennig/gittally/server/NginxProxyManager.kt)); ports range-checked.
