> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

`instance-update` restarts the systemd unit: for a few seconds nothing listens on Werkator's port at all.
On the Hostsharing Managed Webspace deployment (see [deployment.md](../deployment.md#hostsharing-managed-webspace)) the platform's Apache sits in front and proxies via `.htaccess` (`SystemdServiceFiles.htaccessContent`); with the backend refusing connections, Apache answers with its own bare 502 error page, or the request just hangs until it times out.
Either way the instance looks dead rather than "back in a moment", which is confusing during a deploy that is otherwise routine and fast (the restart itself is under a second; the rest of `instance-update`'s time is upload).

## Non-Goals

- A live progress indicator ("2 of 10 seconds left") — the maintenance page is a static file with no way to know how far the restart has gotten.
- Zero-downtime / blue-green deployment — the restart gap itself is not eliminated, only made to look intentional instead of broken.
- The Docker-host and bwrap-webspace-without-a-domain deployment variants, which do not go through Apache/`.htaccess` at all.

## The Scenarios

### Feature: a refused connection shows a maintenance page instead of a bare error

#### Background

- `init --systemd` writes the `.htaccess` only when `server.publicBaseUrl` is set — unchanged; the maintenance page is generated under the same condition, next to it.
- The generated `.htaccess` already proxies every request to `http://127.0.0.1:<port>` via `mod_rewrite [P]`; a refused connection surfaces as Apache's own 502 (and, depending on Apache's timeout handling, 503/504).

#### Scenario#17.01: A refused backend connection serves the maintenance page

So that a deployment's restart window reads "please retry" instead of a bare error or a hang.

- **Given** the generated `.htaccess` and `werkator-maintenance.html` are in a domain's docroot
- **When** the proxied backend refuses the connection (Apache's 502/503/504)
- **Then** Apache serves `werkator-maintenance.html` instead of its default error page

##### Verified by

- [SystemdServiceFilesTest — "the htaccess maps a refused connection to the static maintenance page"](../../src/test/kotlin/de/hoennig/werkator/commands/SystemdServiceFilesTest.kt)
- Manual on `mih09` (2026-09-03): with the service stopped, `https://werkator.javagil.de/` answered **HTTP 503 with the maintenance page in 0.14 s** — Apache reaches the error path immediately, it does not wait for a timeout. With the service running, the same URL is 200 as before, and `/werkator-maintenance.html` is directly reachable (the `RewriteCond` keeps it out of the proxy).

#### Scenario#17.02: The maintenance page itself is never proxied

So that `ErrorDocument`'s internal sub-request for the page does not loop back into the same rewrite rule (which would proxy it to the — still down — backend and fail again).

- **Given** the generated `.htaccess`
- **When** Apache serves `werkator-maintenance.html` as an `ErrorDocument`
- **Then** the `RewriteCond` excludes exactly that path from the proxy `RewriteRule`

##### Verified by

- [SystemdServiceFilesTest — "the htaccess maps a refused connection to the static maintenance page"](../../src/test/kotlin/de/hoennig/werkator/commands/SystemdServiceFilesTest.kt)

#### Scenario#17.03: A host without a configured public base URL is unaffected

So that a developer machine or a Docker-host deployment, which never went through `.htaccess`, sees no new file and no behavior change.

- **Given** `server.publicBaseUrl` is blank
- **When** `init --systemd` runs
- **Then** neither `.htaccess` nor `werkator-maintenance.html` is written — unchanged from before this PR

##### Verified by

- Existing `InitCommand` behavior (`server.publicBaseUrl.isNotBlank()` gate); not separately tested, as before this PR.

## The Solution

`SystemdServiceFiles.htaccessContent` gains three `ErrorDocument` lines (502/503/504) pointing at a new static `werkator-maintenance.html`, plus one `RewriteCond` excluding that file from the catch-all proxy rule.
`SystemdServiceFiles.maintenancePageContent()` is a small, self-contained HTML page ("Werkator is restarting — please retry in a few minutes") — no external CSS/fonts/images, since nothing would be there to serve them while Werkator itself is the thing that is down.
`InitCommand.createSystemdFiles` writes it under the same `server.publicBaseUrl.isNotBlank()` gate as the `.htaccess`, next to it in `.git/werkator/`.
`tools/remote instance-start` copies both files into the domain docroot in the same step (it already copied the `.htaccess` there; the maintenance page rides along).

No config key was added: like the `.htaccess` itself, the maintenance page is generated whenever a `publicBaseUrl` is configured, not behind a separate toggle nobody is expected to turn off.

## Open Questions

- **Does Apache actually reach the error path, or does the client just time out first?** Answered by the live test above: on `mih09` a refused connection surfaces as **503 within 0.14 s**, not a hang — a connection *refused* is immediate, unlike a connection that is accepted and then stalls. The `ErrorDocument` covers 502/503/504 so the exact code Apache picks does not matter.

## Additional Changes

- None beyond the feature itself.

## Follow-up work discovered while deploying this

- `tools/remote instance-start` places the `.htaccess` (and now the maintenance page) into `doms/<domain>/subs/www/`, but the live `mih09` instance serves from `doms/<domain>/htdocs-ssl/` — a pre-existing path mismatch, unrelated to this PR. Both files were therefore copied into `htdocs-ssl/` by hand for this deployment; `instance-start` still needs fixing separately.
- `InitCommand.loadedServerConfig()` swallows every config-loading exception and falls back to a blank `ServerConfig`, so any unrelated config error makes `init --systemd` silently skip both files as if no `publicBaseUrl` were set. Noticed while smoke-testing this change; worth a warning on that path.

## Prerequisite PRs

- None; builds on the existing `.htaccess` generation (`SystemdServiceFiles`, `InitCommand`), unchanged in shape.

## Follow-up PRs

- An explicit Apache `ProxyTimeout` if the client-side hang (see Open Questions) turns out to matter in practice.
