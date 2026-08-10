# Migration from the Legacy Script

The bash script `legacy/gitTally` is deprecated and replaced by this application.
This document maps the legacy environment-variable configuration to the YAML configuration and lists the manual migration steps.
See [configuration.md](configuration.md) for the full configuration reference and [deployment.md](deployment.md) for the new service setup.

## Configuration Mapping

Legacy configuration came from environment variables (`gitTally --env` template, sourced env files).
The new configuration lives in two YAML files: `.gittally.yml` (committed) and `.git/gittally/.gittally.yml` (machine-specific, secrets).

Branch-level keys below live under `branches.<name>`; use `branches.default` for what used to be the global value.

| Legacy environment variable | New YAML key |
|---|---|
| `GITTALLY_BUILD_COMMAND` | `branches.<name>.buildCommand` |
| `GITTALLY_BUILD_CLEAN_COMMAND` | `branches.<name>.cleanCommand` |
| `GITTALLY_BUILD_ARTEFACT_DIRS` | `branches.<name>.artifactDirs` — YAML list instead of `;`-separated |
| `GITTALLY_BUILD_STDOUT_LOG` | `branches.<name>.stdoutLog` |
| `GITTALLY_BUILD_STDERR_LOG` | `branches.<name>.stderrLog` |
| `GITTALLY_NEW_BRANCH_COMMIT_MAX_AGE` | `watcher.newBranchMaxAge` |
| `GITTALLY_BUILD_DOCKER_IMAGE` | `branches.<name>.docker.image` — also set `docker.enabled: true` (replaces the `--docker` flag) |
| `GITTALLY_BUILD_DOCKERFILE` | `branches.<name>.docker.dockerfile` |
| `GITTALLY_BUILD_DOCKER_CONTEXT` | `branches.<name>.docker.context` |
| `GITTALLY_BUILD_DOCKER_NETWORK` | `branches.<name>.docker.network` — default is now Docker's default network, not `host` |
| `GITTALLY_BUILD_DOCKER_ENV` | `branches.<name>.docker.env` — YAML map instead of space-separated assignments |
| `GITTALLY_ARTIFACT_SERVER_PORT` | `server.port` |
| `GITTALLY_ARTIFACT_SERVER_BIND_ADDRESS` | `server.bindAddress` |
| `GITTALLY_ARTIFACT_PUBLIC_BASE_URL` | `server.publicBaseUrl` |
| `GITTALLY_ARTIFACT_BUILD_RETENTION_PER_BRANCH` | `artifacts.retentionPerBranch` for a count, `artifacts.retentionMaxAge` for a legacy age value (`h`/`d` suffix); unlike legacy, both limits can be combined |
| `GITTALLY_IMPRESSUM_URL` | `server.impressumUrl` |
| `GITTALLY_AUTO_BUILD_BRANCHES` | `branches.<name>.autoBuild.enabled: true` per branch instead of a branch list |
| `GITTALLY_AUTO_BUILD_TIMES` | `branches.<name>.autoBuild.times` — YAML list, per branch |
| `GITTALLY_GITEA_BASE_URL` | `gitea.baseUrl` |
| `GITTALLY_GITEA_OWNER` | `gitea.owner` |
| `GITTALLY_GITEA_REPO` | `gitea.repo` |
| `GITTALLY_GITEA_STATUS_CONTEXT` | `gitea.statusContext` |
| `GITTALLY_GITEA_GIT_USERNAME` | `git.account` — in `.git/gittally/.gittally.yml` |
| `GITTALLY_GITEA_TOKEN` | `git.token` — in `.git/gittally/.gittally.yml`, never committed |
| `GITTALLY_ARTIFACT_NGINX_SERVER_NAME` | `server.nginx.serverName` — also set `server.nginx.enabled: true` (replaces the `--nginx` flag) |
| `GITTALLY_ARTIFACT_NGINX_HTTP_PORT` | `server.nginx.httpPort` |
| `GITTALLY_ARTIFACT_NGINX_HTTPS_PORT` | `server.nginx.httpsPort` |
| `GITTALLY_ARTIFACT_NGINX_UPSTREAM_HOST` | `server.nginx.upstreamHost` |
| `GITTALLY_ARTIFACT_NGINX_CONTAINER_NAME` | `server.nginx.containerName` |
| `GITTALLY_ARTIFACT_NGINX_STATE_DIR` | `server.nginx.stateDir` |
| `GITTALLY_ARTIFACT_LETSENCRYPT_EMAIL` | `server.nginx.letsencryptEmail` |

New keys without a legacy counterpart: `builds.maxConcurrent`, `artifacts.rootDir`, and `watcher.pollInterval`.

## Intentionally Not Ported

- Self-install and self-update (`--install`, `--pull`, `GITTALLY_INSTALL_DIR`) — replaced by jar deployment plus `init --systemd`.
- `GITTALLY_BUILD_DOCKER_PREFLIGHT_COMMAND` and `GITTALLY_BUILD_DOCKER_JAVA_TOOL_OPTIONS` — hsadmin-ng-specific; use `branches.<name>.docker.env` if needed.
- `HSADMIN_NG_*` environment-variable fallbacks.
- Env-file configuration itself — the systemd `EnvironmentFile` now only tunes the JVM (`JAVA_OPTS`).
- `GITTALLY_GITEA_DELETED_STATUS_DESCRIPTION`, `GITTALLY_BIN_FORWARD`, `GITTALLY_CONFIG_*` — internal legacy mechanics without a counterpart.

## Build History

Legacy build history (`.git/git-watch-origin-and-test/build-results.tsv`) is **not** imported; history starts fresh.
The formats differ substantially (TSV vs. JSON with commit metadata and artifact keys), and retention would prune imported rows quickly anyway.
Old artifacts under the legacy artifact root remain readable on disk until you delete them.

## Manual Migration Steps

When migrating to a **different host**, the legacy instance can keep running in parallel until the new one is verified — then skip step 1 here and stop the legacy service on the old host last.
During parallel operation, give the new instance a distinct `gitea.statusContext`, so the two instances do not overwrite each other's commit statuses in Gitea.

1. Stop and remove the legacy service:

   ```bash
   systemctl --user disable --now gitTally.service
   rm -f ~/.config/systemd/user/gitTally.service
   systemctl --user daemon-reload
   ```

2. Build and place the jar as described in [deployment.md](deployment.md).
3. In the repository, run `java -jar ~/bin/gittally.jar init`.
4. Transfer your settings from the legacy env file into `.gittally.yml` using the table above.
5. Put `git.account` and `git.token` into `.git/gittally/.gittally.yml`.
6. Verify the effective configuration: `java -jar ~/bin/gittally.jar config:print --full`.
7. Install and start the new service: `init --systemd` plus the printed commands, see [deployment.md](deployment.md).
8. Optionally clean up legacy state: `.git/git-watch-origin-and-test/` and the legacy artifact root.
