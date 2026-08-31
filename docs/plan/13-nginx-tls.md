# Step 13: Managed nginx/TLS Container

Prerequisites: steps 07, 11, 12.
Read `README.md`, `00-legacy-analysis.md`, and ADR 0005 first.
Consult `legacy/werkator` for the functions referenced below.

## Goal

Serve Werkator over HTTPS on hosts that provide Docker but no host reverse proxy (e.g. Hostsharing managed container environments).
Werkator optionally manages an nginx Docker container with Let's Encrypt certificates, ported from the legacy subsystem.
This is opt-in; the reverse-proxy deployment from step 12 stays the default (ADR 0005).

## Design

Port the legacy nginx subsystem (functions `configure_artifact_nginx_defaults` ~1545, `artifact_nginx_write_ssl_options` ~4051, `artifact_nginx_write_config` ~4077, `artifact_nginx_ports_free` ~4242, `cleanup_stale_artifact_nginx_containers` ~4274, `artifact_nginx_run_container` ~4281, `artifact_nginx_obtain_or_renew_certificate` ~4310, `start_artifact_nginx` ~4342, shutdown cleanup ~723):

- Config under `server.nginx.*`: `enabled` (default false), `serverName`, `httpPort` (8080), `httpsPort` (8443), `upstreamHost` (default: `serverName`), `containerName` (default: `werkator-nginx-<repo-name>`), `stateDir` (default: `${XDG_STATE_HOME:-~/.local/state}/werkator/nginx/<repoKey>`), `letsencryptEmail`.
  Update all three config places (`WerkatorConfig`, `init` templates, `docs/configuration.md`).
- When `server.publicBaseUrl` is empty and `serverName` is set, default it to `https://<serverName>/` (legacy line ~541).
- Shell out to the `docker` CLI like `DockerBuildRunner` (no SDK); label the container `org.hoennig.werkator` for stale-container cleanup.
- Lifecycle as a server-profile component (like `ServerWatcherLifecycle`/`ServerMetricsLifecycle`): start after the web server is up, stop and remove the container on shutdown.
  Nothing runs in CLI mode or tests.
- Two-phase startup, ported from legacy: write an HTTP-only nginx config for the ACME webroot challenge, run the container, obtain the certificate via a certbot container (webroot mode), then rewrite the full HTTPS config and restart nginx.
  If a certificate already exists in the state dir, start with the full config directly.
- All failures are non-fatal warnings; the plain HTTP server keeps running (legacy behavior).
- Improvement over legacy (fix by design, do not port): legacy renewed certificates only at process start, relying on frequent self-update restarts.
  Schedule a periodic renewal check (e.g. daily) in the lifecycle component instead.

## Tests

- Unit tests for nginx config generation (init vs. full mode, upstream/port substitution, server name escaping).
- Unit tests for docker argv assembly (run, certbot, cleanup), mocking the command runner — no real Docker or ACME interaction.
- Renewal scheduling logic with a fake clock/scheduler.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- With `server.nginx.enabled: false` (default) nothing changes; no container is touched.
- Manual walkthrough on a Docker host: nginx container starts with the init config and proxies HTTP to werkator.
  Full ACME issuance needs a public DNS name; if none is available, verify the certbot argv and the full-config path against the legacy script and document that in this file.
- `docs/deployment.md` gains a section for hosts without a reverse proxy; `docs/migration-from-legacy.md` maps the `GITTALLY_ARTIFACT_NGINX_*`/`GITTALLY_ARTIFACT_LETSENCRYPT_EMAIL` variables.

## Result (2026-07-08)

Implemented as `NginxConfigFiles` (config generation), `NginxProxyManager` (docker orchestration), and `ServerNginxLifecycle` (server-profile startup, daily renewal, shutdown cleanup).

Deviations from the design above and from legacy:

- Renewal reloads nginx via `docker exec <container> nginx -s reload` after `certbot renew` instead of restarting the container (no dropped connections).
- Legacy auto-moved the artifact server port on a collision with the nginx ports; the rewrite refuses to start the proxy with a warning instead — the Spring port cannot move after startup.
- Legacy derived a missing `serverName` from the public base URL host; the rewrite requires `serverName` explicitly (the config default direction is only publicBaseUrl ← serverName).
- `serverName` and `upstreamHost` are validated against a host-name pattern instead of substituting raw values, so no nginx directives can be injected via config.
- The container label namespace is `org.hoennig.werkator` (like the Docker build runner), not `org.hostsharing.werkator`; port cleanup still also matches legacy-named containers.
- The legacy `--nginx` CLI flag is not ported; enablement is `server.nginx.enabled` only.
- `ssl-dhparams.pem` is downloaded via the Java HTTP client instead of `curl` (replaceable seam for tests).

Manual walkthrough (dev machine, Rancher Desktop Docker, no public DNS name — so no real ACME issuance):

- Fresh state dir: nginx container started with the init config; port 80 served the ACME challenge location and answered `301 https://<serverName>/...`; `certonly` then failed as expected without public DNS and the HTTP server kept running (non-fatal path).
- Pre-seeded certificate (self-signed): manager took the full-config path, `certbot renew` ran (exit 0), and HTTPS end-to-end worked — `/api/branches` JSON served through the TLS proxy with the configured no-cache headers.
- `SIGTERM` removed the container (lifecycle `@PreDestroy`).
- The certbot `certonly`/`renew` argv and both config modes are asserted verbatim against the legacy script in `NginxProxyManagerTest`/`NginxConfigFilesTest`.
- Environment note: the nginx container cannot reach the host's `localhost`; on the walkthrough machine the upstream had to be `host.docker.internal` (documented in `deployment.md` as `upstreamHost`).
