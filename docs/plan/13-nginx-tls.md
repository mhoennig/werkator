# Step 13: Managed nginx/TLS Container

Prerequisites: steps 07, 11, 12.
Read `README.md`, `00-legacy-analysis.md`, and ADR 0005 first.
Consult `legacy/gitTally` for the functions referenced below.

## Goal

Serve GitTally over HTTPS on hosts that provide Docker but no host reverse proxy (e.g. Hostsharing managed container environments).
GitTally optionally manages an nginx Docker container with Let's Encrypt certificates, ported from the legacy subsystem.
This is opt-in; the reverse-proxy deployment from step 12 stays the default (ADR 0005).

## Design

Port the legacy nginx subsystem (functions `configure_artifact_nginx_defaults` ~1545, `artifact_nginx_write_ssl_options` ~4051, `artifact_nginx_write_config` ~4077, `artifact_nginx_ports_free` ~4242, `cleanup_stale_artifact_nginx_containers` ~4274, `artifact_nginx_run_container` ~4281, `artifact_nginx_obtain_or_renew_certificate` ~4310, `start_artifact_nginx` ~4342, shutdown cleanup ~723):

- Config under `server.nginx.*`: `enabled` (default false), `serverName`, `httpPort` (8080), `httpsPort` (8443), `upstreamHost` (default: `serverName`), `containerName` (default: `gittally-nginx-<repo-name>`), `stateDir` (default: `${XDG_STATE_HOME:-~/.local/state}/gittally/nginx/<repoKey>`), `letsencryptEmail`.
  Update all three config places (`GitTallyConfig`, `init` templates, `docs/configuration.md`).
- When `server.publicBaseUrl` is empty and `serverName` is set, default it to `https://<serverName>/` (legacy line ~541).
- Shell out to the `docker` CLI like `DockerBuildRunner` (no SDK); label the container `org.hoennig.gittally` for stale-container cleanup.
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
- Manual walkthrough on a Docker host: nginx container starts with the init config and proxies HTTP to GitTally.
  Full ACME issuance needs a public DNS name; if none is available, verify the certbot argv and the full-config path against the legacy script and document that in this file.
- `docs/deployment.md` gains a section for hosts without a reverse proxy; `docs/migration-from-legacy.md` maps the `GITTALLY_ARTIFACT_NGINX_*`/`GITTALLY_ARTIFACT_LETSENCRYPT_EMAIL` variables.
