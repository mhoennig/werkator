# Step 15: Self-Contained Runtime Bundle Distribution

Prerequisites: steps 12, 13.
Read `README.md` first.
This step revises the "Future: Docker-based Deployment" section of `docs/bootstrapping.md` — see the ADR requirement below.

## Goal

Deploy Werkator on hosts that provide Docker and git but no Java runtime (Hostsharing container servers, e.g. `tallyman@vm4006`).
Werkator is distributed as a self-contained runtime bundle: a jlink-trimmed JRE plus `werkator.jar` plus a launcher script, packed as one tarball.
The JAR stays the primary artifact for development and for hosts that already have a JRE.

## Distribution Format Decision (ADR 0006)

Three formats were considered; write ADR 0006 recording the decision and this rationale:

- **jlink runtime bundle (chosen)** — no production-code changes, plain JVM semantics, one tarball to `scp`.
  git and docker CLIs are used from the host, worktree paths stay host paths, and the `init --systemd` unit works unchanged because `java.home` and the running-jar path resolve into the bundle.
- **GraalVM native image (rejected)** — Spring AOT evaluates bean conditions at build time.
  Werkator's dual-context design (CLI context without web, second `SpringApplication` with the `server` profile and `WebApplicationType.SERVLET`, `@Profile("!server")` `CliRunner`, `@Profile("server")` lifecycles) cannot be represented in a single AOT arrangement.
  Supporting it would require replacing the profile wiring with runtime guards and collapsing the two context shapes — an invasive rewrite with regression risk for the JVM path.
- **Containerized Werkator runtime (rejected, was the `docs/bootstrapping.md` sketch)** — needs git and docker CLIs inside the image, a same-path `$HOME` mount plus docker-socket mount and uid/gid mapping so that `DockerBuildRunner`'s `--volume $workspace:$workspace` sibling mounts keep working, and a hand-edited systemd unit.
  Kept as the documented fallback if the bundle approach ever becomes unworkable.

## Target Host Facts (verified 2026-08-10)

- `vm4006.hostsharing.net`: Debian 13, x86_64, glibc 2.41, git 2.47.3, docker 26.1.5, GNU make, systemd user session running with `Linger=yes`, no Java runtime.
- The jlink image contains natively linked JVM libs, so it must be built on glibc ≤ 2.41 for the same architecture; the Ubuntu 24.04 dev machine (glibc 2.39, x86_64) qualifies.
  For reproducible builds elsewhere, the bundle can be built inside an `eclipse-temurin:21-jdk` container (glibc 2.39 base).

## Design

Gradle:

- Add a `runtimeBundle` task (depends on `bootJar`); the normal `./gradlew build` stays unchanged.
- The task runs `jlink` from the configured Java toolchain (every JDK 21 ships jlink; no new toolchain requirement).
- The JDK module list is pinned in the build script, computed once via `jdeps` on the exploded boot jar and its `BOOT-INF/lib`; document the jdeps command next to the list and re-check it when dependencies change.
- Bundle layout: `werkator/jre/` (jlink image), `werkator/lib/werkator.jar`, `werkator/bin/werkator` (sh launcher: `exec "$DIR/../jre/bin/java" $JAVA_OPTS -jar "$DIR/../lib/werkator.jar" "$@"`).
- Output: `build/distributions/werkator-runtime-linux-x64.tar.gz` with preserved execute permissions.

Deployment (no code changes expected):

- Unpack to `~/opt/werkator/` on the target host; run everything via `~/opt/werkator/bin/werkator`.
- `init --systemd` already generates `ExecStart=<java> $JAVA_OPTS -jar <jar> server` from `java.home` and the running jar path — from the bundle both resolve into `~/opt/werkator/`, so the unit points at the bundle without changes.
  Verify this instead of adapting code; adapt only if the resolution fails.
- Updating Werkator = unpack a new bundle over `~/opt/werkator/` (or switch a symlink) and restart the service.

Documentation:

- `docs/deployment.md`: prerequisites become "JRE 21 **or** the runtime bundle"; add a section "Hosts Without a Java Runtime (Runtime Bundle)" with build, `scp`, unpack, and systemd setup.
- `docs/bootstrapping.md`: replace the "Future: Docker-based Deployment" section with the runtime bundle and a pointer to ADR 0006.
- `docs/adrs/0006-…`: the distribution-format decision (see above).
- `docs/migration-from-legacy.md`: add a note that migrating to a different host allows parallel operation, with a distinct `gitea.statusContext` per instance so the two CIs do not overwrite each other's commit statuses.

## Tests

- No production code changes are expected, so no new unit tests; existing tests must stay green.
- Smoke-verify the bundle manually: `bin/werkator --help`, `init` in a scratch repo, `config:print --full`, a short `server` run, and `init --systemd` unit content pointing into the bundle; document the results in this file.
- Verify on vm4006 (which has no Java): copy the bundle, run `bin/werkator --help` and `config:print`; document the results in this file.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green, with unchanged toolchain requirements.
- `./gradlew runtimeBundle` produces a tarball whose `bin/werkator` runs `--help`, `init`, and `server` on a machine without any Java runtime.
- A fresh deployment to vm4006 following `docs/deployment.md` and `docs/migration-from-legacy.md` reaches a running service: web UI reachable, a Docker build succeeds, commit status arrives in Gitea, managed nginx/TLS works (`server.nginx.enabled: true`, DNS for `serverName` pointing at vm4006).
- vm2176 (legacy) keeps running in parallel during the migration; the legacy service is only retired after vm4006 is verified.
- Docs and ADR 0006 written as described; document deviations in this file.

## Result (2026-08-10)

Implemented as designed; no production-code change was needed.
The step was originally drafted for a GraalVM native image; it was re-planned to the jlink bundle after the Spring-AOT build-time condition evaluation turned out to be incompatible with the dual-context CLI/server wiring (see ADR 0006).

- `runtimeBundle` task in `build.gradle.kts` with the pinned module list (jdeps output plus java.logging, jdk.crypto.ec, jdk.management, jdk.zipfs); launcher script in `packaging/werkator`; tarball ~66 MB.
- Smoke tests on the dev machine (with `JAVA_HOME` unset and a stripped `PATH`): `--help`, `init --systemd` in a scratch repo, `config:print --full`, and a `server` run all passed; `/` served HTTP 200 and `/api/branches` returned JSON.
- The `init --systemd` unit generated from the bundle points at `<bundle>/jre/bin/java` and `<bundle>/lib/werkator.jar` as predicted — no detection code needed.
- Verified on vm4006 (no Java installed): bundle unpacked to `~/opt/werkator`, `--version`, `--help`, and `status` in a scratch repo (host git via `GitCommandRunner`) all worked.

Production deployment to vm4006 (2026-08-10, same session):

- `hs.hsadmin.ng` cloned to `~/hs.hsadmin.ng` on vm4006; legacy configuration from vm2176 (repo `.werkator` + `werkator.env`) migrated to `.werkator.yml` per `docs/migration-from-legacy.md`; Gitea token moved (the token in vm2176's `werkator.env` file was stale — the valid one came from the running legacy process environment).
- `statusContext: werkator@vm4006` for the parallel phase; rename to `werkator` after vm2176 is retired.
- systemd user service installed via `init --systemd` from the bundle and running; watcher fetches origin branches with the migrated credentials.
- Managed nginx/TLS live: Let's Encrypt certificate for `vm4006.hostsharing.net` obtained, `https://vm4006.hostsharing.net/` serves the UI with a valid chain, HTTP 301s to HTTPS (Hostsharing routes public 80/443 to `httpPort`/`httpsPort`, same as on vm2176).
- Fix discovered during rollout: certbot removed `ssl-dhparams.pem` from its repository, so the first nginx start failed with HTTP 404.
  The DH parameters (RFC 7919 ffdhe2048) are now bundled as the classpath resource `nginx/ssl-dhparams.pem` instead of downloaded; the download seam and `NginxConfigFiles.DH_PARAMS_URL` were removed (revises the step-13 note about the download).
  Legacy on vm2176 only kept working because its state dir cached the file.

Second fix discovered by the first real build: under a **rootless** Docker daemon, `DockerBuildRunner` ran the build container as `--user <host-uid>` (ported from legacy).
With rootless identity mapping the host user is container root, and the host uid inside the container falls into the subuid range — the container could not create `.gradle` in the freshly created worktree ("Failed to create parent directory").
Legacy on vm2176 only worked because its ownership-repair chown had (unintentionally) moved `build/` and `.gradle/` into subuid ownership on the host (verified: owned by uid 166536 there) — a stable but wrong equilibrium tied to its reused primary checkout.
The rewrite now always runs the build container as `--user 0`: under rootless that IS the unprivileged host user (files stay host-owned, the repair chown degenerates to `0:0`); under rootful daemons the behavior is unchanged (root + chown to the host ids).

Third finding (config, not code): the hsadmin-ng Liquibase migration tests (`LiquibaseCompatibilityIntegrationTest`, `ImportHostingAssets.liquibaseMigrationForBookingAndHosting`) failed on vm4006 with "environment variable HSADMINNG_POSTGRES_ADMIN_USERNAME not set".
These tests run Liquibase programmatically without Spring's `spring.liquibase.parameters`, so the changelog parameters resolve only via Liquibase's env-var substitution; the legacy script had a host-env passthrough for exactly these variables.
The values `admin`/`restricted` are hsadmin-ng's committed defaults, but only for the other execution paths: `.tc-environment` for the documented dev workflow (`. .tc-environment; ./gradlew …`) and the `${…:admin}` fallbacks in `src/test/resources/application.yml` for the Spring-managed Liquibase path.
The programmatic path deliberately has no fallback — `009-check-environment.sql` exists to verify the environment is configured — so a CI environment must export the variables itself.
Fix: `HSADMINNG_POSTGRES_ADMIN_USERNAME=admin` and `HSADMINNG_POSTGRES_RESTRICTED_USERNAME=restricted` in `branches.default.docker.env` on vm4006 — the mechanism `docs/migration-from-legacy.md` prescribes for the legacy passthrough list; alternatively the build command could source `.tc-environment` like the dev workflow.
Verified by running both test classes in the build container with the variables set: green.
Open oddity: the same commit passed on vm2176 although neither its daemon environment, build image, Gradle volume, nor any build-script mechanism supplies these variables there (an unused git-ignored `.environment` file exists in its primary checkout, but nothing in the build reads it); the loading path on vm2176 remains unidentified.

Fourth finding (Werkator limitation, worked around in config): with all tests green, the build then failed in hsadmin-ng's `:prQuickCheck` — "fatal: not a git repository".
Werkator builds in a git worktree whose `.git` is a pointer file into the primary repository's `.git/worktrees/…`, and the Docker build container (deliberately, credentials live under `.git/werkator/`) only mounts the worktree — so build steps that call git fail; the legacy script avoided this by building in the primary checkout.
Workaround: `prQuickCheck` removed from the vm4006 build command — it is a PR quality gate against a base branch and has no meaning in a post-merge master build (on vm2176 it only passed as an accidental no-op).
The underlying question (safe git availability inside Docker build containers without exposing `.git/werkator/` secrets) is left as a follow-up design task.

Cutover completed (2026-08-10, same day): after three green master builds and verified Gitea statuses from vm4006, the legacy service on vm2176 was disabled and removed from systemd.
vm4006's `statusContext` was switched to the canonical `werkator` (effective without a restart — the Gitea client loads the config per call), and the branches still carrying red statuses from the buggy first hours were re-queued.
vm2176 now runs only a redirect nginx container (`werkator-redirect`, ports 8080/8443 like before): HTTP and HTTPS answer 301 to `https://vm4006.hostsharing.net$request_uri`, the ACME webroot keeps serving so the `nginx-letsencrypt-renew.timer` continues to renew the old host's certificate (the renew unit gained an `ExecStartPost` nginx reload).
Werkator answers the legacy static page names (`/index.html`, `/branches.html`, `/history.html`, `/system.html`, `/about.html`, `/license.html`) with permanent redirects to the new routes, so pre-rewrite links survive the host redirect.

Update to v0.9.8 (2026-08-10): the running build was awaited first (a restart would have killed it), then service stopped, `~/opt/werkator` backed up to `~/opt/werkator.v0.9.7.bak` and the new bundle unpacked over it, service started.
Verified live: `/` reports v0.9.8, the nav has no `Current` entry, the permanent `🔗` link appears only on branches whose latest build is their latest green one, and the newly linked reports answer 200 — including the stable `/branches/<branch>/reports/profile/`.
Master has no profile report yet because its `.werkator.yml` still carries the pre-PR#282 build command; it appears once that PR merges.

Update to v0.9.9 (2026-08-11): no build was running, service stopped, `~/opt/werkator` backed up to `~/opt/werkator.v0.9.8.bak` and replaced by the new bundle, service started.
The changed `server.bindAddress` default was harmless here because vm4006 sets `0.0.0.0` explicitly in `.git/werkator/.werkator.yml` — which the managed nginx container needs.
Found and fixed on the host: `.git/werkator/.werkator.yml` (the Gitea token) was still `0644` and its directory `0755` from the pre-0.9.9 `init`; both were tightened to `0600`/`0700` manually, as the new code only sets the mode for files it creates.
Verified live: `/` reports v0.9.9, HTTP 301s to HTTPS with a valid certificate, `/api/builds/latest` answers, a control token in the query string is rejected with 403, `config:print` masks `git.token`, and the mobile header stacks title over repository name.

Update to v0.9.10 (2026-08-11): same procedure, `~/opt/werkator.v0.9.9.bak` as the rollback copy.
Verified live: `/` reports v0.9.10, the pages no longer contain `werkator-control-token`, `/api/builds/latest` and `/branches` still answer 200 without any credential, and a mutation without the token is rejected with 403.
The operator has to paste the token from `~/hs.hsadmin.ng/.git/werkator/control-token` once per browser now.

Update to v0.9.11 (2026-08-14): same procedure, no build was running, `~/opt/werkator.0.9.10.bak` as the rollback copy.
Verified live: `bin/werkator --version` reports v0.9.11 before the start, the service is `active`, `/` answers 200 with v0.9.11 in the footer, `/releases` shows the v0.9.11 entry, and `/api/builds/current` is empty.
The watcher's new local-ref fast-forward logged nothing, because `~/hs.hsadmin.ng` had already been reset to `origin/master` by hand — it only acts on a branch that actually lags behind.

Update to v0.9.12 (2026-08-26): same procedure, `~/opt/werkator.0.9.11.bak` as the rollback copy; a build was running — interrupted and re-enqueued by the startup recovery as designed.
Verified live: `bin/werkator --version` reports v0.9.12 before the start, the service is `active`, `/` shows v0.9.12, and the recovery re-enqueued exactly one build per affected branch (the pre-fix duplicate queue entries were collapsed by `markStaleRunningAsInterrupted` + `latestPerBranch`).
Shipped fixes: prune never removes queued/running results (a branch deleted mid-build stays visible), and manual triggers dedup against an already active build of the same branch and commit.

Update to v0.9.13 (2026-08-28): same procedure, no build was running, `~/opt/werkator.0.9.12.bak` as the rollback copy.
Verified live: `bin/werkator --version` reports v0.9.13 before the start, the service is `active`, `/` answers 200 with v0.9.13 in the footer, `/api/builds/latest` carries the new `name` field, and the watcher polls without errors.
Shipped feature: an `autoBuild.times` entry can carry its own `buildCommand` and `name` — a named nightly slot (e.g. `master@nightly`) gets its own branches-view row, retention pool, and permanent latest-green link; restart/retry/recovery repeat a build with its original command and name.

Update to v0.9.14 (2026-08-28): same procedure, `~/opt/werkator.0.9.13.bak` as the rollback copy; a build was running — interrupted and re-enqueued by the startup recovery as designed (now under its recorded build definition `default`).
Verified live: `bin/werkator --version` reports v0.9.14 before the start, the service is `active`, `/` answers 200 with v0.9.14 in the footer, the watcher polls without errors, and the deprecation warning for `branches.master.autoBuild` appears once in the log.
Shipped feature: named build definitions (ADR 0007) — the `builds` section defines jobs with `onPush`/`atTimes` triggers, branch selectors (globs, `activeWithin`), and setting overrides; named builds record under `<branch>@<build>` pools; the v0.9.13 per-slot syntax was removed again.

Update to v0.9.15 (2026-08-29): same procedure, `~/opt/werkator.0.9.14.bak` as the rollback copy, no build was running.
Verified live: `bin/werkator --version` reports v0.9.15 before the start, the service is `active`, the API answers, and the watcher polls without errors.
The leftover `builds.maxConcurrent` in the repository's committed `master` config is ignored with exactly one warning per context instead of failing the configuration — the reason it is tolerated rather than rejected: that config needs a colleague's approval to change, and the installation must not be stuck on a key it is meant to forget.
Shipped feature: the `.werkator.yml` committed on a branch takes precedence for its `builds` section as well, and the watcher reads it per origin branch (`git show`, cached by head commit) to decide which of that branch's builds are due.
Verified live within seconds of the start: the build definition `reactivate-pi-test-with-full-pitest`, which exists only on the branch `mihoe/reactivate-pi-test` and not in the `master` config, fired its due 05:00 UTC slot, recorded under the pool `mihoe/reactivate-pi-test@reactivate-pi-test-with-full-pitest`, and ran the branch's `pitestFull` command in the build container inherited from the `master` config.

Update to v0.9.16 (2026-08-29): same procedure, `~/opt/werkator.0.9.15.bak` as the rollback copy.
A build was running at the first attempt, so the deployment aborted itself before stopping anything; the service was then stopped in the first idle window (waiting rather than interrupting was the operator's call).
Verified live: `bin/werkator --version` reports v0.9.16 before the start, the service is `active`, `/releases` lists v0.9.16, and the watcher polls without errors.
Shipped features: hourly scheduled builds (`atTimes: ["??:05"]`) and the artifact page showing the command a build actually runs.
Verified live: the branch's `atTimes: ["??:00"]` — rejected by v0.9.15 with a warning on every poll cycle — is accepted, and its 06:00 UTC slot fired right after the restart.

Update to v0.9.17 (2026-08-29): same procedure, `~/opt/werkator.0.9.16.bak` as the rollback copy.
Deployed under the operator's condition "only if no build is running": the deploy script re-checks `/api/builds/current` immediately before the stop and exits without touching anything when a build is executing.
Verified live: `bin/werkator --version` reports v0.9.17 before the start, the service is `active`, `/releases` lists v0.9.17, the served `werkator.js` carries the resume listeners, and no errors in the log.
Shipped fix: a page returning from the background fetches the current state immediately instead of showing (and ticking) the state it was left in.

Update to v0.9.18 (2026-08-29): same procedure, `~/opt/werkator.0.9.17.bak` as the rollback copy, no build was running.
Verified live: `bin/werkator --version` reports v0.9.18 before the start, the service is `active`, `/releases` lists v0.9.18, the watcher polls without fetch or poll errors, and the only warnings are the two known `builds.maxConcurrent` lines from the repository's committed config.
Shipped feature: a configuration file can declare the Werkator it is written for (`werkator.version.since`/`below`), so an incompatibility is named instead of silently ignored.
The configs of the watched repository declare nothing yet and are unaffected — a missing declaration is never an error.

Update to v0.9.19 (2026-08-29): same procedure, `~/opt/werkator.0.9.18.bak` as the rollback copy, no build was running.
Shipped feature: a build definition describes its build completely (`requirePullRequest`, the whole `docker` section), `builds.default` is the settings base of every other definition, and the per-branch `branches` section is superseded — read only while nothing defines a build at all.
The machine config `.git/werkator/.werkator.yml` was rewritten to the new shape beforehand (backup `.werkator.yml.20260829T085959Z.bak`), in a form valid under both versions: `builds.default` plus `builds.master` for the new one, a reduced `branches` block for v0.9.18, which has no sandbox policy inside a definition.
It also drops the machine-local `--no-build-cache` command that had shadowed master's own `buildCommand` for every on-push build; the commands now come from `origin/master`'s committed config.
Verified live: `bin/werkator --version` reports v0.9.19 before the start, the service is `active`, `/releases` lists v0.9.19, and the log carries the expected "ignoring the branches section" warning next to the known `builds.maxConcurrent` one.
The new `master@master` job fired on the first poll after the restart — its 01:00 slot was due and unmarked for that pool — and runs in `hsadmin-ng-build-env:latest` with `bootJarWithDocumentation`: the pinned `docker.enabled`/`network` reached it through the inheritance from `builds.default`, while its own command won.
The legacy `branches` block stays in the machine config for the transition week as the rollback path to v0.9.18; it is inert under v0.9.19.

Update to v0.9.20 (2026-08-29): same procedure, `~/opt/werkator.0.9.19.bak` as the rollback copy, no build was running.
Shipped feature: a definition's `trigger` block, `!` exclusion patterns in `trigger.branches`, and a per-build Gitea `statusContext`.
The machine config had to be migrated in the same window (backup `.werkator.yml.20260829T100842Z.bak`): v0.9.19 drops an unknown `trigger` block silently — which would leave every definition without a trigger — and v0.9.20 refuses the flat keys, so the file is valid for exactly one of the two versions and had to be swapped while the service was down.
The new configuration was validated with the new binary (`config:print --full`) after the swap and before the start.
Verified live: `--version` reports v0.9.20, the service is `active`, `/releases` lists v0.9.20, the watcher polls without errors, and the two expected warnings (`builds.maxConcurrent`, the ignored `branches` section) are the only ones from the host configuration.
The branch-scoped refusal showed itself in production immediately: `mihoe/reactivate-pi-test` still commits its triggers flat, so its committed config is refused with a message naming the branch, the commit, and each definition's offending keys — the watcher falls back to the host's definitions for scheduling, and builds of that branch fail until the file is migrated. Every other branch is unaffected, which is the whole point of the per-file scoping.

Update to v1.0.0 (2026-08-31), the rename to Werkator, deployed from the branch as the final test of PR#1: `~/opt/gittally.0.9.21.bak` as the rollback copy, no build was running, and an 8.8 KB snapshot of the state directory without its worktrees (`~/gittally-state-20260831T065716Z.tar.gz`) as the way back — everything that cannot be recreated fits in it, the 878 MB are worktrees the migration drops and the next build recreates.
The artifact root was moved by hand (`~/.local/state/gittally` to `~/.local/state/werkator`, 1.3 GB, instant on the same filesystem), the state directory moved itself.
Two defects surfaced and were fixed before the service was started, both of the silent kind this release is about: the directory move left the machine configuration under its old name in the new directory, a pair of names the lookup did not expect, so the instance resolved empty credentials and none of the host's build definitions; and `init --systemd` wrote a fresh template `.werkator.yml` into the watched working tree beside its committed `.gittally.yml`, which would have made that repository build `./gradlew test` instead of its own command. The bundle was rebuilt from the fixed branch before the units were switched.
Verified live: `--version` reports v1.0.0, the service is `active`, the build history is the one from before, the watcher polls without errors, HTTPS through the managed nginx answers 200 with the certificates found under the moved state path, and a master build started by itself — worktree recreated, container `werkator-build-…` running in `hsadmin-ng-build-env:latest` under the new `org.hoennig.werkator` label.
The two expected warnings (`builds.maxConcurrent`, the ignored `branches` section) are still the only ones, both from master's committed configuration.

Update to v0.9.21 (2026-08-30): same procedure, `~/opt/werkator.0.9.20.bak` as the rollback copy, no build was running; the machine config needed no change this time.
Shipped feature: an unreachable origin is shown in the web UI (step 19), and a lasting fetch failure is logged once per message instead of once per poll.
The occasion was an outage the same morning: the `git.token` in the machine config had been overwritten with a placeholder string, Werkator failed every fetch for 57 minutes, and the branches view kept showing its last known list as if nothing were wrong.
Verified live: `--version` reports v0.9.21, the service is `active`, `/` answers 200 with v0.9.21 in the footer, `/api/watcher` reports `lastFetchError: null`, the served `werkator.js` carries `refreshWatcherBanner`, `/branches` carries the banner element, and the only warnings are the two expected ones from the repository's committed config (`builds.maxConcurrent`, the ignored `branches` section).
