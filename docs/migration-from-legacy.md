# Migration from the Legacy Script

The bash script `legacy/werkator` is deprecated and replaced by this application.
This document maps the legacy environment-variable configuration to the YAML configuration and lists the manual migration steps.
See [configuration.md](configuration.md) for the full configuration reference and [deployment.md](deployment.md) for the new service setup.

## Configuration Mapping

Legacy configuration came from environment variables (`werkator --env` template, sourced env files).
The new configuration lives in two YAML files: `.werkator.yml` (committed) and `.git/werkator/.werkator.yml` (machine-specific, secrets).

Build-level keys below live in a build definition under `builds.<name>`; use `builds.default` for what used to be
the global value — it is the base every other definition inherits its settings from.

| Legacy environment variable | New YAML key |
|---|---|
| `werkator_BUILD_COMMAND` | `builds.<name>.buildCommand` |
| `werkator_BUILD_CLEAN_COMMAND` | `builds.<name>.cleanCommand` |
| `werkator_BUILD_ARTEFACT_DIRS` | `builds.<name>.artifactDirs` — YAML list instead of `;`-separated |
| `werkator_BUILD_STDOUT_LOG` | `builds.<name>.stdoutLog` |
| `werkator_BUILD_STDERR_LOG` | `builds.<name>.stderrLog` |
| `werkator_NEW_BRANCH_COMMIT_MAX_AGE` | `watcher.newBranchMaxAge` |
| `werkator_BUILD_DOCKER_IMAGE` | `builds.<name>.docker.image` — also set `docker.enabled: true` (replaces the `--docker` flag) |
| `werkator_BUILD_DOCKERFILE` | `builds.<name>.docker.dockerfile` |
| `werkator_BUILD_DOCKER_CONTEXT` | `builds.<name>.docker.context` |
| `werkator_BUILD_DOCKER_NETWORK` | `builds.<name>.docker.network` — default is now Docker's default network, not `host` |
| `werkator_BUILD_DOCKER_ENV` | `builds.<name>.docker.env` — YAML map instead of space-separated assignments |
| `werkator_ARTIFACT_SERVER_PORT` | `server.port` |
| `werkator_ARTIFACT_SERVER_BIND_ADDRESS` | `server.bindAddress` |
| `werkator_ARTIFACT_PUBLIC_BASE_URL` | `server.publicBaseUrl` |
| `werkator_ARTIFACT_BUILD_RETENTION_PER_BRANCH` | `artifacts.retentionPerBranch` for a count, `artifacts.retentionMaxAge` for a legacy age value (`h`/`d` suffix); unlike legacy, both limits can be combined |
| `werkator_IMPRESSUM_URL` | `server.impressumUrl` |
| `werkator_AUTO_BUILD_BRANCHES` | a build definition with `branches: [...]` selecting them |
| `werkator_AUTO_BUILD_TIMES` | `builds.<name>.atTimes` — YAML list of UTC `HH:MM` slots |
| `werkator_GITEA_BASE_URL` | `gitea.baseUrl` |
| `werkator_GITEA_OWNER` | `gitea.owner` |
| `werkator_GITEA_REPO` | `gitea.repo` |
| `werkator_GITEA_STATUS_CONTEXT` | `gitea.statusContext` |
| `werkator_GITEA_GIT_USERNAME` | `git.account` — in `.git/werkator/.werkator.yml` |
| `werkator_GITEA_TOKEN` | `git.token` — in `.git/werkator/.werkator.yml`, never committed |
| `werkator_ARTIFACT_NGINX_SERVER_NAME` | `server.nginx.serverName` — also set `server.nginx.enabled: true` (replaces the `--nginx` flag) |
| `werkator_ARTIFACT_NGINX_HTTP_PORT` | `server.nginx.httpPort` |
| `werkator_ARTIFACT_NGINX_HTTPS_PORT` | `server.nginx.httpsPort` |
| `werkator_ARTIFACT_NGINX_UPSTREAM_HOST` | `server.nginx.upstreamHost` |
| `werkator_ARTIFACT_NGINX_CONTAINER_NAME` | `server.nginx.containerName` |
| `werkator_ARTIFACT_NGINX_STATE_DIR` | `server.nginx.stateDir` |
| `werkator_ARTIFACT_LETSENCRYPT_EMAIL` | `server.nginx.letsencryptEmail` |

New keys without a legacy counterpart: `builds.maxConcurrent`, `artifacts.rootDir`, and `watcher.pollInterval`.

## Intentionally Not Ported

- Self-install and self-update (`--install`, `--pull`, `werkator_INSTALL_DIR`) — replaced by jar deployment plus `init --systemd`.
- `werkator_BUILD_DOCKER_PREFLIGHT_COMMAND` and `werkator_BUILD_DOCKER_JAVA_TOOL_OPTIONS` — hsadmin-ng-specific; use `builds.<name>.docker.env` if needed.
- `HSADMIN_NG_*` environment-variable fallbacks.
- Env-file configuration itself — the systemd `EnvironmentFile` now only tunes the JVM (`JAVA_OPTS`).
- `werkator_GITEA_DELETED_STATUS_DESCRIPTION`, `werkator_BIN_FORWARD`, `werkator_CONFIG_*` — internal legacy mechanics without a counterpart.

## Build History

Legacy build history (`.git/git-watch-origin-and-test/build-results.tsv`) is **not** imported; history starts fresh.
The formats differ substantially (TSV vs. JSON with commit metadata and artifact keys), and retention would prune imported rows quickly anyway.
Old artifacts under the legacy artifact root remain readable on disk until you delete them.

## Manual Migration Steps

When migrating to a **different host**, the legacy instance can keep running in parallel until the new one is verified — then skip step 1 here and stop the legacy service on the old host last.
During parallel operation, give the new instance a distinct `gitea.statusContext`, so the two instances do not overwrite each other's commit statuses in Gitea.

Rename the context back to the canonical one **while no build is running**.
The Gitea client reads the config per call, so a rename between a build's `running` and its final status splits that build over two contexts: the old one keeps the `pending` "build running" entry forever, and Gitea's combined status of that commit stays *pending* although the build succeeded.
Gitea has no API to delete a commit status; the only way out is to post a closing status for the abandoned context by hand:

```bash
curl -X POST -H "Authorization: token $TOKEN" -H 'Content-Type: application/json' \
  -d '{"state":"success","context":"<old context>","description":"superseded by <new context>"}' \
  "$GITEA/api/v1/repos/<owner>/<repo>/statuses/<commit-sha>"
```

1. Stop and remove the legacy service:

   ```bash
   systemctl --user disable --now werkator.service
   rm -f ~/.config/systemd/user/werkator.service
   systemctl --user daemon-reload
   ```

2. Build and place the jar as described in [deployment.md](deployment.md).
3. In the repository, run `java -jar ~/bin/werkator.jar init`.
4. Transfer your settings from the legacy env file into `.werkator.yml` using the table above.
5. Put `git.account` and `git.token` into `.git/werkator/.werkator.yml`.
6. Verify the effective configuration: `java -jar ~/bin/werkator.jar config:print --full`.
7. Install and start the new service: `init --systemd` plus the printed commands, see [deployment.md](deployment.md).
8. Optionally clean up legacy state: `.git/git-watch-origin-and-test/` and the legacy artifact root.
