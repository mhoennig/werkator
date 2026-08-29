# GitTally Configuration Reference

GitTally is configured via YAML files. Settings are merged from several sources in order — later layers override earlier ones.

## Config File Locations

| Layer                    | Path                       | Committed to Git | Purpose                                      |
|--------------------------|----------------------------|------------------|----------------------------------------------|
| Project config           | `.gittally.yml`            | Yes              | Shared team settings                         |
| Repo installation config | `.git/gittally/.gittally.yml` | No               | Machine- or user-specific overrides, secrets |
| Branch config            | `.gittally.yml` committed on a branch | Yes  | That branch's build settings and build definitions |

The repo install config (`.git/gittally/.gittally.yml`) wins on any key present in both files. Typically used to set `git.token` and `git.account` without committing them.

### Which GitTally a file is written for

Every configuration file may declare the GitTally it was written for. Without it, a
version that renames or drops a key does not fail — it silently ignores what it no longer
understands, and the effect shows up as a build that does the wrong thing.

```yaml
gitTally:
  version:
    since: "0.9.16"   # enforced: an older GitTally refuses to read this file
    below: "2.0"      # your release marker; GitTally decides how strictly to take it
```

There is deliberately **no version of the file format** (no `apiVersion`): no API is
involved — GitTally reads its own configuration — and only one configuration generation is
ever supported. The declaration exists to make an incompatibility nameable, never to run
two parsers.

`since` is a hard floor and covers both directions:

- a newer file on an older GitTally is refused instead of being half-understood;
- a file written *before* a breaking change and read *after* it is refused as well —
  GitTally knows in which version its configuration format last broke, so the message can
  name the change: *"is written for GitTally 1.4.0, but the configuration format changed
  incompatibly in 2.0.0: `builds:` is now `buildSpec:`"*.

`below` is optional and names the first version this file was **not** released for. The
bound is exclusive, so `below: "2.0"` means everything up to 2.0.0. On its own it only
warns — a caution marker nobody maintained must never stop a CI. The refusal above comes
from GitTally's own knowledge of its breaking changes, not from this value. The intended
routine is the one known from IDE plugins: a new version appears, the warning shows up, you
try it (on a test host, or in production with a rollback ready), and then raise `below` and
commit that.

A file that declares nothing is read as before, with a hint in the log — a missing line
must never stop a server either. `gittally init` writes the running version into the
generated config.

How far a violation reaches depends on the file, following the same rule as everything
else here: the machine and project configs abort the start (the message names the file and
the rollback), while an incompatible **branch** config fails only the builds of that
branch. A branch that was cut before a migration must never stop the server or hold up the
branches that are fine.

### The branch layer: a branch describes its own CI

The `.gittally.yml` committed on a branch is applied as a third layer on top of the two
above, giving the precedence **branch > repo install > project**. It takes precedence for
everything that describes how this branch is built: the whole `builds` section — its own
definitions and its overrides of the definitions from the project config, with
`buildCommand`, `cleanCommand`, `artifactDirs`, log file names, and
`docker.image`/`dockerfile`/`context`/`env` inside them. That is how a new configuration is tried out: change it on a branch, and
no other branch's builds are affected.

The branch layer is used in both places where it matters: the watcher reads the committed
config of each origin branch (via `git show`, only when the branch moved) to decide which
of *its* builds are due, and the build itself resolves its settings from the worktree of
the commit being built.

A branch's definitions apply to that branch alone. Their selectors are evaluated for it
only, so a definition committed on one branch can never trigger builds of another — even
when its `branches` selector names one.

A pinned set is always taken from the repo install/project config, because none of it
describes this branch's build:

- secrets: the whole `git` section;
- host- and repository-side settings: the whole `server`, `gitea`, `executor`, and `watcher` sections;
- the container sandbox policy: `docker.enabled` and `docker.network`;
- the trust gate: `requirePullRequest`.

This keeps a branch from reaching credentials, reporting statuses to another repository,
raising the global concurrency, disabling its own build container, changing its network
mode, or bypassing its own pull-request gate. Everything else is the branch's to decide —
it can already run any command through `buildCommand`.
The pinned settings are stripped wherever they appear, in a build definition as well as in
a legacy `branches` entry. The deprecated `branches` section itself is read from the repo
install/project config only, and only while nothing defines a build at all.

## Inspect the Effective Config

```bash
java -jar build/libs/gittally.jar config:print         # only explicitly set values
java -jar build/libs/gittally.jar config:print --full  # all values including defaults
```

`git.token` is masked as `***` by default, so the output can safely be shared or pasted.
Add `--show-secrets` to print it in clear text.

## `.gittally.yml`

Values shown are the defaults.

```yaml
# The GitTally this file is written for (see the section above).
gitTally:
  version:
    since: "0.9.18"   # enforced: older GitTally refuses this file
    below: "2.0"      # optional release marker; warns, does not block

server:
  # Public base URL of this GitTally installation — used for all links posted to Gitea.
  publicBaseUrl: https://ci.example.org/
  # HTTP port of the `server` subcommand (default 18080, like legacy)
  port: 18080
  # bind address of the `server` subcommand; loopback only, because the UI and the API
  # are unauthenticated — set 0.0.0.0 only deliberately (see the note below)
  bindAddress: 127.0.0.1
  # optional Impressum (legal disclosure) link in the web UI footer; empty hides the link
  impressumUrl: ""
  # Opt-in managed nginx+certbot Docker container for HTTPS, for hosts without
  # a usable reverse proxy (ADR 0005; see notes below and deployment.md).
  nginx:
    # manage an nginx Docker container with Let's Encrypt certificates
    enabled: false
    # public DNS name served by nginx and used for the certificate; required when enabled
    serverName: ""
    # host port published as nginx port 80 (ACME challenge + HTTPS redirect)
    httpPort: 8080
    # host port published as nginx port 443
    httpsPort: 8443
    # host nginx proxies to; empty = serverName (the container cannot reach localhost)
    upstreamHost: ""
    # name of the managed container; empty = gittally-nginx-<repo-name>
    containerName: ""
    # directory for nginx config, certificates, and logs;
    # empty = $XDG_STATE_HOME (or ~/.local/state) plus /gittally/nginx/<repo-key>
    stateDir: ""
    # e-mail for the Let's Encrypt account; empty registers without one
    letsencryptEmail: ""

# Gitea integration for fetching commits and posting build statuses.
gitea:
  baseUrl: https://git.example.org   # base URL of the Gitea instance
  owner: my-org                      # repository owner (user or organisation) for Gitea API (e.g. status checks)
  repo: my-repo                      # repository name
  statusContext: GitTally            # label shown on Gitea commit status checks (default: GitTally)

# Build execution settings, enforced for all builds regardless of their trigger
# (watcher, UI restart, CLI build/retry).
executor:
  # How many builds may run at the same time.
  # At most one build per branch runs regardless; each branch builds in its own
  # git worktree under .git/gittally/worktrees/, never in the primary checkout.
  # Changing this value requires a restart.
  maxConcurrent: 1

# Named build definitions (jobs, see notes below); every key names a build.
builds:
  # "default" is the base every other definition inherits its settings from — never its
  # trigger — and, with onPush, the build of every branch. Without this entry an implicit
  # default build (onPush over all branches) applies; writing it replaces that implicit
  # one, so a default without a trigger is a settings base and nothing else.
  default:
    onPush: true                # trigger: build every new commit of the selected branches
    # run before each build
    cleanCommand: rm -rf build
    # shell command for each build
    buildCommand: ./gradlew --console=plain --no-daemon test
    # directories copied as build artifacts
    artifactDirs:
      - build/reports
      - build/doc
    stdoutLog: build.stdout.log   # filename for captured stdout
    stderrLog: build.stderr.log   # filename for captured stderr
    # Build a selected branch only while its head commit matches a pull-request head on
    # origin (refs/pull/*/head — read via plain git, no API token needed; see notes below).
    # Pinned: a branch cannot set this in its own committed config.
    requirePullRequest: false
    # Optional Docker build runtime; when enabled, the clean and build commands
    # run inside a container instead of natively (see notes below).
    docker:
      # run clean/build commands in a Docker container (pinned)
      enabled: false
      # image for the build container; required when enabled
      image: ""
      # Dockerfile to (re)build the image from when it is missing or stale; empty pulls the image as-is
      dockerfile: ""
      # Docker build context used with dockerfile
      context: "."
      # Docker network mode for the build container; empty = Docker default (pinned)
      network: ""
      # additional environment variables set inside the build container
      env: {}

  # Every further job inherits the settings above and adds its own trigger and selector.
  # The nightly rebuild runs the full check instead of the quick on-commit one and is
  # recorded separately as master@pitest:
  pitest:
    onPush: false                     # trigger: build every new commit (default: false)
    atTimes: ["01:00"]                # trigger: daily UTC times HH:MM, "??:05" = hourly at :05
    branches: ["master", "release/*"]  # selector: names or glob patterns (default: all)
    activeWithin: 24h                 # selector: only branches with commits in the last 24h
    buildCommand: ./gradlew -PfullPitTest --console=plain --no-daemon piTestFull
    artifactDirs: [build/reports, build/libs]

# Build artifact storage and retention.
artifacts:
  # Root directory for stored build artifacts.
  # Empty means the platform default: $XDG_STATE_HOME (or ~/.local/state) plus /gittally/artifacts/<repo-key>,
  # where <repo-key> is the sanitized absolute repository path.
  # A leading ~/ expands to the home directory; a relative path is resolved against the repository.
  rootDir: ""
  # number of builds to keep per branch
  retentionPerBranch: 3
  # Additionally drop builds older than this age; suffixes s (seconds), m (minutes), h (hours), d (days).
  # Empty means no age limit.
  # Combines with retentionPerBranch: a build is kept only while it satisfies both limits.
  # A branch's newest build is never age-pruned, so dormant branches keep their last status.
  retentionMaxAge: ""
  # Keep each branch's latest green (successful) build even beyond retentionPerBranch and retentionMaxAge.
  # This backs the permanent artifact URLs /branches/<branch-key>/... — they always serve
  # the latest green build of a branch and stay valid while newer builds fail.
  # The kept build is still dropped once its branch is deleted from origin.
  keepLatestGreen: true

# Controls the branch-polling loop.
watcher:
  # delay between poll cycles; suffixes s (seconds), m (minutes), h (hours), d (days)
  pollInterval: 10s
  # max commit age for new origin branches to be pulled automatically
  newBranchMaxAge: 5d
  # Honor the builds.<name>.requirePullRequest gates (see notes below).
  # Set false for a plain git origin without pull-request refs (no Gitea/GitHub);
  # gated branches then build on new commits like any other branch.
  pullRequestGate: true
  # At the end of each poll cycle, fast-forward the primary checkout's local branch refs
  # to their origin counterparts (see notes below). Fast-forward only — a diverged or
  # ahead local branch is never touched. Set false to leave refs/heads/* alone entirely.
  fastForwardLocalRefs: true

```

### Notes on `server.bindAddress`

The default is `127.0.0.1`.
Neither the web UI nor the JSON API authenticates read access — which is intended, so build states and artifacts can be linked from anywhere — so GitTally is meant to sit behind the host's reverse proxy rather than on a public interface.
Set `0.0.0.0` only deliberately — for the managed nginx container (which reaches GitTally over the Docker bridge, not over loopback), or when the proxy runs on another host.
Installations created before v0.9.9 have `bindAddress: 0.0.0.0` written into their `.gittally.yml` and keep it; the new default only applies where the key is absent or `init` writes a fresh file.

### Notes on `server.nginx`

With `nginx.enabled`, the `server` subcommand also starts a managed nginx Docker container that serves GitTally over HTTPS (ADR 0005).
This is meant for hosts that provide Docker but no usable reverse proxy (e.g. Hostsharing managed containers); otherwise prefer the reverse-proxy setup in [deployment.md](deployment.md).
Certificates are obtained and renewed via Let's Encrypt (certbot Docker container, webroot mode), so `serverName` must be a public DNS name pointing at the host and `httpPort` must be reachable from the internet as port 80 (or via a port forward).
When `server.publicBaseUrl` is empty and `serverName` is set, it defaults to `https://<serverName>/`.
All nginx/certificate failures are non-fatal warnings; the plain HTTP server keeps running without the proxy.
The container is labelled `org.hoennig.gittally`; stale nginx containers of the repository are removed before each start, and the container is removed on shutdown.
`server.port` must differ from `httpPort` and `httpsPort`.

### Notes on `builds.<name>.requirePullRequest`

The gate applies to all watcher-triggered builds (push-triggered and scheduled auto builds).
A manual `gittally build <branch>` always builds, regardless of this setting.

Detection works without a Gitea API token:
the watcher lists `refs/pull/*/head` on origin via `git ls-remote` and builds a branch only when its head commit equals one of those pull-request head commits.
This ls-remote call is made at most once per poll cycle, and only when a branch requiring a pull request is otherwise due.

Because matching is by commit id, a closed pull request whose head ref still equals the branch head also counts.
Distinguishing open from closed pull requests would require the Gitea API.

To build pull-request branches only, gate the default build and give the permanent branches a build of their own:

```yaml
builds:
  default:
    onPush: true
    requirePullRequest: true
  main:
    onPush: true
    branches: ["main"]
    requirePullRequest: false
```

Without that second definition, direct pushes and merges to `main` would never build — merge commits do not match any pull-request head.
Note that `main` is then selected by both definitions, so a push builds it twice; give the default build a `branches` selector that excludes it, or accept the second run.

A plain git origin (no Gitea/GitHub) serves no `refs/pull/*/head` at all, so gated branches would never build there.
For such origins, disable all gates globally with `watcher.pullRequestGate: false` — typically in the machine-specific `.git/gittally/.gittally.yml`, so the committed configuration keeps the gates for forge-backed environments.

### Notes on `builds` (build definitions)

Every key of the `builds` section names a build definition (a job) over the branches — ADR 0007.
A build definition has triggers, a branch selector, and build-setting overrides.

Triggers: `onPush: true` builds every new commit of the selected branches; `atTimes: ["HH:MM", …]` rebuilds their heads once per day and slot (UTC).
A slot may also be written as `??:MM` — that minute of every hour, expanded to its 24 slots, so the build runs hourly.
Only the latest due slot of a day triggers, so slots missed while the server was down are skipped instead of piling up, and a slot whose pool is still building is retried on the next poll cycle until it succeeds.
A definition may have both; one with neither never triggers automatically — which is how `builds.default` is written when it is meant as a settings base only.
GitTally logs a warning once when no definition has a trigger at all, because such an instance never builds anything on its own.

Selector: `branches` lists branch names or glob patterns (`*` matches any characters, also across `/`); empty selects all origin branches.
`activeWithin` (e.g. `24h`) additionally keeps only branches whose origin head commit is younger than the duration — useful to run a nightly deep check over all recently active branches.
Both parts combine as an intersection.

Settings: `buildCommand`, `cleanCommand`, `artifactDirs`, `stdoutLog`/`stderrLog`, `requirePullRequest`, and `docker` with all its keys.
A definition carries the complete description of its build; unset keys fall back to `builds.default` and then to GitTally's own defaults.
`requirePullRequest`, `docker.enabled`, and `docker.network` are pinned: they are read from the repo install/project config even when a branch sets them in its own committed config, see [the branch layer](#the-branch-layer-a-branch-describes-its-own-ci).
Inheritance from `builds.default` covers the settings only — a trigger and a selector say when and where *this* build runs, so `onPush`, `atTimes`, `branches`, and `activeWithin` are never inherited.
Definitions are part of the branch layer: a branch may add its own and override those from the project config, for its own builds only.
Because the inheritance is applied after all layers are merged, a build a branch invents still inherits the host's `builds.default` — its sandbox policy included, which is what keeps the pinning effective for a build the host has never heard of.

The implicit `default` build (`onPush: true`, all branches) preserves the behavior without any definitions; defining other builds does not disable it, `builds.default.onPush: false` does.
The `default` build records under the plain branch name; every other build records under `<branch>@<name>` with its own row in the branches view (sorted after its branch), its own `retentionPerBranch` count, latest status, and permanent latest-green artifact link.
The URL key is the sanitized pool name — `master@pitest` is served as `/branches/master_pitest/…`.
The pools live as long as the underlying branch exists on origin.
Restart, `gittally retry`, and the startup recovery re-run a build under its recorded definition, resolving the settings from the current configuration — the job definition is the source of truth, not the historical run.
The builds still run in their branch's worktree, one build per branch at a time, and the Gitea commit status is reported per commit in the shared status context (the last build of a commit wins there).

The concurrency limit that used to live in this section moved to `executor.maxConcurrent` without an alias.
A leftover `builds.maxConcurrent` key (or any other scalar where a definition belongs) is ignored with a warning, not a startup failure — a committed config cannot always be changed right away.

### The legacy `branches` section

Before build definitions existed, the settings lived in a per-branch `branches` section, with `branches.default` as the fallback for every branch not listed.
That section is deprecated and will be removed.
It is still read, but only while the merged configuration defines no build at all: as soon as one real definition exists, `branches` is ignored completely and a warning names it.
`builds.maxConcurrent` is not a definition — a configuration carrying only that leftover still uses `branches`.

Either or, never both: a definition now carries the complete description of its build, and two half-answers would silently pull against each other.
The decision is made on the merged configuration, so a machine config that defines builds switches `branches` off for every branch, including the branches whose committed config still has one.

`branches.<name>.autoBuild` (`enabled` + `times`) is the pre-ADR-0007 schedule that goes with it: it rebuilds the branch's own pool with its regular command and logs a deprecation warning.
`autoBuild.times` entries carrying their own `buildCommand`/`name` (a short-lived v0.9.13 syntax) are no longer supported — use a build definition.

To migrate, move `branches.default` to `builds.default`, add `onPush: true`, and turn every other branch entry into a definition with a `branches` selector of its own.

### Notes on `watcher.fastForwardLocalRefs`

Builds run in worktrees that share the primary checkout's `.git`, so a build tool can read `refs/heads/*` there.
GitTally itself never needs those refs to be current — it builds the commit `refs/remotes/origin/<branch>` points at — but build tools do.
A common case is a check that refuses to run when the local main branch differs from its origin counterpart; without this key it would fail on every build once origin moved on, because nothing would ever advance the local ref.

The fast-forward runs at the end of the poll cycle, after the due branches were enqueued.
That order is required, not cosmetic: a local ref lagging behind origin is exactly how the watcher recognizes new commits, so a ref kept in sync earlier — by this key, a cron job, or a mirroring fetch refspec (`+refs/heads/*:refs/heads/*`) — would silently stop the branch from ever being built.

Only fast-forwards are applied, as a compare-and-swap against the commit just read.
A local branch that diverged from origin or is ahead of it stays untouched, so local work in the primary checkout is never lost.
The branch checked out in the primary checkout is advanced with `git merge --ff-only`, which refuses to overwrite conflicting uncommitted changes; a refusal is logged and the cycle continues.

### Notes on `builds.<name>.docker`

With `docker.enabled`, GitTally shells out to the `docker` CLI; the `docker` command must be on the `PATH`.
When `dockerfile` is set, the image is (re)built whenever the Dockerfile content, its path, or the context path changed.
Staleness is tracked via the image label `org.gittally.build-inputs-sha256`.
A Gradle cache volume `gittally-gradle-<repo-key>` is created per repository and mounted as `GRADLE_USER_HOME`.
The build worktree is bind-mounted into the container; after each command the ownership of `build/` and `.gradle/` is repaired to the host user.
Git works inside the container: the primary repository's `.git` is mounted read-only (so build steps can run read-only git commands like `git log` or `git describe`), with `.git/gittally/` masked by an empty tmpfs so the build can never read the machine config (`git.token`) or the control token.
Note that the rest of `.git` — including `.git/config` — is visible to builds; GitTally never stores credentials there, and neither should you.
The Docker socket is mounted into the container and `DOCKER_HOST`/`TESTCONTAINERS_*` variables are set, so Testcontainers-based builds work inside the container.
All GitTally containers carry `org.hoennig.gittally` labels; stale build containers of the repository are removed before the first Docker build after a restart.

## `.git/gittally/.gittally.yml` (not committed)

```yaml
# Machine- or user-specific overrides and secrets. Keys here win over .gittally.yml.
git:
  account: my-user              # technical username for git HTTPS authentication
  token: glpat-xxxxxxxxxxxxxxxxxxxx # Gitea API token — never commit this
```
