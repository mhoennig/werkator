# Werkator Configuration Reference

Werkator is configured via YAML files. Settings are merged from several sources in order — later layers override earlier ones.

## Config File Locations

| Layer                    | Path                       | Committed to Git | Purpose                                      |
|--------------------------|----------------------------|------------------|----------------------------------------------|
| Project config           | `.werkator.yml`            | Yes              | Shared team settings                         |
| Repo installation config | `.git/werkator/.werkator.yml` | No               | Machine- or user-specific overrides, secrets |
| Branch config            | `.werkator.yml` committed on a branch | Yes  | That branch's build settings and build definitions |

The repo install config (`.git/werkator/.werkator.yml`) wins on any key present in both files. Typically used to set `git.token` and `git.account` without committing them.

### Which Werkator a file is written for

Every configuration file may declare the Werkator it was written for. Without it, a
version that renames or drops a key does not fail — it silently ignores what it no longer
understands, and the effect shows up as a build that does the wrong thing.

```yaml
werkator:
  version:
    since: "0.9.16"   # enforced: an older Werkator refuses to read this file
    below: "2.0"      # your release marker; Werkator decides how strictly to take it
```

There is deliberately **no version of the file format** (no `apiVersion`): no API is
involved — Werkator reads its own configuration — and only one configuration generation is
ever supported. The declaration exists to make an incompatibility nameable, never to run
two parsers.

`since` is a hard floor and covers both directions:

- a newer file on an older Werkator is refused instead of being half-understood;
- a file written *before* a breaking change and read *after* it is refused as well —
  Werkator knows in which version its configuration format last broke, so the message can
  name the change: *"is written for Werkator 1.4.0, but the configuration format changed
  incompatibly in 2.0.0: `builds:` is now `buildSpec:`"*.

`below` is optional and names the first version this file was **not** released for. The
bound is exclusive, so `below: "2.0"` means everything up to 2.0.0. On its own it only
warns — a caution marker nobody maintained must never stop a CI. The refusal above comes
from Werkator's own knowledge of its breaking changes, not from this value. The intended
routine is the one known from IDE plugins: a new version appears, the warning shows up, you
try it (on a test host, or in production with a rollback ready), and then raise `below` and
commit that.

A file that declares nothing is read as before, with a hint in the log — a missing line
must never stop a server either. `werkator init` writes the running version into the
generated config.

How far a violation reaches depends on the file, following the same rule as everything
else here: the machine and project configs abort the start (the message names the file and
the rollback), while an incompatible **branch** config fails only the builds of that
branch. A branch that was cut before a migration must never stop the server or hold up the
branches that are fine.

### The branch layer: a branch describes its own CI

The `.werkator.yml` committed on a branch is applied as a third layer on top of the two
above, giving the precedence **branch > repo install > project**. It takes precedence for
everything that describes how this branch is built: the whole `builds` section — its own
definitions and its overrides of the definitions from the project config, with
`buildCommand`, `cleanCommand`, `artifactDirs`, log file names, and
`docker.image`/`dockerfile`/`context`/`env` and `bwrap.env` inside them. That is how a new configuration is tried out: change it on a branch, and
no other branch's builds are affected.

The branch layer is used in both places where it matters: the watcher reads the committed
config of each origin branch (via `git show`, only when the branch moved) to decide which
of *its* builds are due, and the build itself resolves its settings from the worktree of
the commit being built.

A branch's definitions apply to that branch alone. Their selectors are evaluated for it
only, so a definition committed on one branch can never trigger builds of another — even
when its `branches` selector names one.

A pinned set is always taken from the repo install/project config, because none of it
describes this branch's build.
Which of those two files is expected to carry a key gives the two names used throughout
this documentation.

**host-pinned** — only the machine can know it, and it never belongs in the repository:

- secrets: the whole `git` section;
- the host's own addresses and ports: the whole `server` section.

**master-pinned** — it belongs in the repository, where changing it needs a review, but no
single branch may decide it:

- the repository-side settings: the whole `gitea`, `executor`, and `watcher` sections;
- the trust gate: `requirePullRequest`, and the Gitea status context: `statusContext`;
- the container sandbox policy: `docker.enabled`/`docker.network` and
  `bwrap.enabled`/`bwrap.rootfs` — host-pinned as
  long as only the host's configuration sets them, master-pinned once the committed
  configuration does.

The distinction is documentary.
Werkator applies one rule: every pinned key is stripped from the branch layer, and the
value then resolves from whichever remaining layer sets it.
The names say where a key is meant to live, not how it is enforced.

This keeps a branch from reaching credentials, reporting statuses to another repository,
raising the global concurrency, disabling its own build container, changing its network
mode, or bypassing its own pull-request gate. Everything else is the branch's to decide —
it can already run any command through `buildCommand`.
The pinned settings are stripped wherever they appear, in a build definition as well as in
a legacy `branches` entry. The deprecated `branches` section itself is read from the repo
install/project config only, and only while nothing defines a build at all.

## Inspect the Effective Config

```bash
java -jar build/libs/werkator.jar config:print         # only explicitly set values
java -jar build/libs/werkator.jar config:print --full  # all values including defaults
```

`git.token` is masked as `***` by default, so the output can safely be shared or pasted.
Add `--show-secrets` to print it in clear text.

## `.werkator.yml`

Values shown are the defaults.

```yaml
# The Werkator this file is written for (see the section above).
werkator:
  version:
    since: "0.9.18"   # enforced: older Werkator refuses this file
    below: "2.0"      # optional release marker; warns, does not block

server:
  # Public base URL of this Werkator installation — used for all links posted to Gitea.
  publicBaseUrl: https://ci.example.org/
  # HTTP port of the `server` subcommand (default 18080, like legacy)
  port: 18080
  # bind address of the `server` subcommand; loopback only, because the UI and the API
  # are unauthenticated — set 0.0.0.0 only deliberately (see the note below)
  bindAddress: 127.0.0.1
  # optional Impressum (legal disclosure) link in the web UI footer; empty hides the link
  impressumUrl: ""
  # Resource limits of the systemd user unit generated by `init --systemd`; empty = directive omitted.
  # Needed where the service shares a memory slice, e.g. Hostsharing Managed Webspaces, where a
  # runaway Gradle build would starve everything else in the package.
  systemd:
    memoryMax: ""   # e.g. 1G — written as `MemoryMax=` into the unit
    tasksMax: ""    # e.g. 512 — written as `TasksMax=` into the unit
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
    # name of the managed container; empty = werkator-nginx-<repo-name>
    containerName: ""
    # directory for nginx config, certificates, and logs;
    # empty = $XDG_STATE_HOME (or ~/.local/state) plus /werkator/nginx/<repo-key>
    stateDir: ""
    # e-mail for the Let's Encrypt account; empty registers without one
    letsencryptEmail: ""

# Gitea integration for fetching commits and posting build statuses.
gitea:
  baseUrl: https://git.example.org   # base URL of the Gitea instance
  owner: my-org                      # repository owner (user or organisation) for Gitea API (e.g. status checks)
  repo: my-repo                      # repository name
  statusContext: werkator            # label shown on Gitea commit status checks (default: werkator)

# Build execution settings, enforced for all builds regardless of their trigger
# (watcher, UI restart, CLI build/retry).
executor:
  # How many builds may run at the same time.
  # At most one build per branch runs regardless; each branch builds in its own
  # git worktree under .git/werkator/worktrees/, never in the primary checkout.
  # Changing this value requires a restart.
  maxConcurrent: 1

# Named build definitions (jobs, see notes below); every key names a build.
builds:
  # "default" is the base every other definition inherits its settings from — never its
  # trigger — and, with a trigger of its own, the build of every branch it selects.
  # Without this entry an implicit default build (onPush over all branches) applies;
  # writing it replaces that implicit one, so a default without a trigger is a settings
  # base and nothing else.
  default:
    # When this build runs and for which branches — the only part a definition does NOT
    # inherit from builds.default; everything below the block does.
    trigger:
      onPush: true              # build every new commit of the selected branches
      # branches: ["*", "!master"]   # names or globs; a "!" pattern excludes; default: all
      # atTimes: ["01:00"]           # daily UTC times HH:MM ("??:05" = every hour at :05)
      # activeWithin: 24h            # only branches with commits in the last 24h
    # run before each build
    cleanCommand: rm -rf build
    # shell command for each build
    buildCommand: ./gradlew --console=plain --no-daemon test
    # directories copied as build artifacts; each is archived at its own
    # workspace-relative path, except build/reports, which archives as reports/
    # and is browsed by the artifact page's report index
    artifactDirs:
      - build/reports
      - build/doc
    stdoutLog: build.stdout.log   # filename for captured stdout
    stderrLog: build.stderr.log   # filename for captured stderr
    # Build a selected branch only while its head commit matches a pull-request head on
    # origin (refs/pull/*/head — read via plain git, no API token needed; see notes below).
    # Pinned: a branch cannot set this in its own committed config.
    requirePullRequest: false
    # Gitea check this build reports as; empty uses gitea.statusContext. Two builds of one
    # commit under the same context overwrite each other. Pinned like requirePullRequest.
    statusContext: ""
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

  # Every further job inherits the settings above and brings its own trigger.
  # The nightly rebuild runs the full check instead of the quick on-commit one and is
  # recorded separately as master@pitest:
  pitest:
    trigger:
      atTimes: ["01:00"]
      branches: ["master", "release/*"]
      activeWithin: 24h
    buildCommand: ./gradlew -PfullPitTest --console=plain --no-daemon piTestFull
    artifactDirs: [build/reports, build/libs]
    statusContext: werkator/pitest

# Build artifact storage and retention.
artifacts:
  # Root directory for stored build artifacts.
  # Empty means the platform default: $XDG_STATE_HOME (or ~/.local/state) plus /werkator/artifacts/<repo-key>,
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
Neither the web UI nor the JSON API authenticates read access — which is intended, so build states and artifacts can be linked from anywhere — so Werkator is meant to sit behind the host's reverse proxy rather than on a public interface.
Set `0.0.0.0` only deliberately — for the managed nginx container (which reaches Werkator over the Docker bridge, not over loopback), or when the proxy runs on another host.
Installations created before v0.9.9 have `bindAddress: 0.0.0.0` written into their `.werkator.yml` and keep it; the new default only applies where the key is absent or `init` writes a fresh file.

### Notes on `server.nginx`

With `nginx.enabled`, the `server` subcommand also starts a managed nginx Docker container that serves Werkator over HTTPS (ADR 0005).
This is meant for hosts that provide Docker but no usable reverse proxy (e.g. Hostsharing managed containers); otherwise prefer the reverse-proxy setup in [deployment.md](deployment.md).
Certificates are obtained and renewed via Let's Encrypt (certbot Docker container, webroot mode), so `serverName` must be a public DNS name pointing at the host and `httpPort` must be reachable from the internet as port 80 (or via a port forward).
When `server.publicBaseUrl` is empty and `serverName` is set, it defaults to `https://<serverName>/`.
All nginx/certificate failures are non-fatal warnings; the plain HTTP server keeps running without the proxy.
The container is labelled `org.hoennig.werkator`; stale nginx containers of the repository are removed before each start, and the container is removed on shutdown.
`server.port` must differ from `httpPort` and `httpsPort`.

### Notes on `builds.<name>.requirePullRequest`

The gate applies to all watcher-triggered builds (push-triggered and scheduled auto builds).
A manual `werkator build <branch>` always builds, regardless of this setting.

Detection works without a Gitea API token:
the watcher lists `refs/pull/*/head` on origin via `git ls-remote` and builds a branch only when its head commit equals one of those pull-request head commits.
This ls-remote call is made at most once per poll cycle, and only when a branch requiring a pull request is otherwise due.

Because matching is by commit id, a closed pull request whose head ref still equals the branch head also counts.
Distinguishing open from closed pull requests would require the Gitea API.

To build pull-request branches only, gate the default build and give the permanent branches a build of their own:

```yaml
builds:
  default:
    trigger:
      onPush: true
      branches: ["*", "!main"]
    requirePullRequest: true
  main:
    trigger:
      onPush: true
      branches: ["main"]
    requirePullRequest: false
```

Without that second definition, direct pushes and merges to `main` would never build — merge commits do not match any pull-request head.
The `!main` exclusion keeps the default build off it, so a push is built once instead of by both definitions.

A plain git origin (no Gitea/GitHub) serves no `refs/pull/*/head` at all, so gated branches would never build there.
For such origins, disable all gates globally with `watcher.pullRequestGate: false` — typically in the machine-specific `.git/werkator/.werkator.yml`, so the committed configuration keeps the gates for forge-backed environments.

### Notes on `builds` (build definitions)

Every key of the `builds` section names a build definition (a job) over the branches — ADR 0007.
A build definition has a `trigger` block — when it runs and for which branches — and the settings that say what it does.
The split is structural because `trigger` is the one part never inherited from `builds.default`.
Writing any of its keys outside the block is refused with a message naming the definition: ignoring them would leave the build without a trigger, and a job that silently stops running is worse than a configuration that refuses to load.

Triggers: `onPush: true` builds every new commit of the selected branches; `atTimes: ["HH:MM", …]` rebuilds their heads once per day and slot (UTC).
A slot may also be written as `??:MM` — that minute of every hour, expanded to its 24 slots, so the build runs hourly.
Only the latest due slot of a day triggers, so slots missed while the server was down are skipped instead of piling up, and a slot whose pool is still building is retried on the next poll cycle until it succeeds.
A definition may have both; one with neither never triggers automatically — which is how `builds.default` is written when it is meant as a settings base only.
Werkator logs a warning once when no definition has a trigger at all, because such an instance never builds anything on its own.

Selector: `trigger.branches` lists branch names or glob patterns (`*` matches any characters, also across `/`); empty selects all origin branches.
A pattern prefixed with `!` excludes instead, and an exclusion always wins regardless of order — `["*", "!master"]` is every branch but master.
That is how a branch gets a build of its own without being built by the default one as well.
`activeWithin` (e.g. `24h`) additionally keeps only branches whose origin head commit is younger than the duration — useful to run a nightly deep check over all recently active branches.
Both parts combine as an intersection.

Settings: `buildCommand`, `cleanCommand`, `artifactDirs`, `stdoutLog`/`stderrLog`, `requirePullRequest`, `statusContext`, and `docker` and `bwrap` with all their keys.
A definition carries the complete description of its build; unset keys fall back to `builds.default` and then to Werkator's own defaults.
`requirePullRequest`, `statusContext`, `docker.enabled`, `docker.network`, `bwrap.enabled`, and `bwrap.rootfs` are pinned (master-pinned, see [the branch layer](#the-branch-layer-a-branch-describes-its-own-ci)): they are read from the repo install/project config even when a branch sets them in its own committed config.
Inheritance from `builds.default` covers the settings only — the `trigger` block says when and where *this* build runs and is never inherited.
Definitions are part of the branch layer: a branch may add its own and override those from the project config, for its own builds only.
Because the inheritance is applied after all layers are merged, a build a branch invents still inherits the host's `builds.default` — its sandbox policy included, which is what keeps the pinning effective for a build the host has never heard of.

The implicit `default` build (`onPush: true`, all branches) preserves the behavior without any definitions; defining other builds does not disable it, `builds.default.onPush: false` does.
The `default` build records under the plain branch name; every other build records under `<branch>@<name>` with its own row in the branches view (sorted after its branch), its own `retentionPerBranch` count, latest status, and permanent latest-green artifact link.
The URL key is the sanitized pool name — `master@pitest` is served as `/branches/master_pitest/…`.
The pools live as long as the underlying branch exists on origin.
Restart, `werkator retry`, and the startup recovery re-run a build under its recorded definition, resolving the settings from the current configuration — the job definition is the source of truth, not the historical run.
The builds still run in their branch's worktree, one build per branch at a time.
The Gitea commit status is reported per commit under `gitea.statusContext`, so two builds of the same commit overwrite each other's check — give the second one its own `statusContext` (`werkator/quick`, say), or keep them apart with an exclusion pattern.

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
Werkator itself never needs those refs to be current — it builds the commit `refs/remotes/origin/<branch>` points at — but build tools do.
A common case is a check that refuses to run when the local main branch differs from its origin counterpart; without this key it would fail on every build once origin moved on, because nothing would ever advance the local ref.

The fast-forward runs at the end of the poll cycle, after the due branches were enqueued.
That order is required, not cosmetic: a local ref lagging behind origin is exactly how the watcher recognizes new commits, so a ref kept in sync earlier — by this key, a cron job, or a mirroring fetch refspec (`+refs/heads/*:refs/heads/*`) — would silently stop the branch from ever being built.

Only fast-forwards are applied, as a compare-and-swap against the commit just read.
A local branch that diverged from origin or is ahead of it stays untouched, so local work in the primary checkout is never lost.
The branch checked out in the primary checkout is advanced with `git merge --ff-only`, which refuses to overwrite conflicting uncommitted changes; a refusal is logged and the cycle continues.

### Notes on `builds.<name>.docker`

With `docker.enabled`, Werkator shells out to the `docker` CLI; the `docker` command must be on the `PATH`.
When `dockerfile` is set, the image is (re)built whenever the Dockerfile content, its path, or the context path changed.
Staleness is tracked via the image label `org.werkator.build-inputs-sha256`.
A Gradle cache volume `werkator-gradle-<repo-key>` is created per repository and mounted as `GRADLE_USER_HOME`.
The build worktree is bind-mounted into the container; after each command the ownership of `build/` and `.gradle/` is repaired to the host user.
Git works inside the container: the primary repository's `.git` is mounted read-only (so build steps can run read-only git commands like `git log` or `git describe`), with `.git/werkator/` masked by an empty tmpfs so the build can never read the machine config (`git.token`) or the control token.
Note that the rest of `.git` — including `.git/config` — is visible to builds; Werkator never stores credentials there, and neither should you.
The Docker socket is mounted into the container and `DOCKER_HOST`/`TESTCONTAINERS_*` variables are set, so Testcontainers-based builds work inside the container.
All Werkator containers carry `org.hoennig.werkator` labels; stale build containers of the repository are removed before the first Docker build after a restart.

### Notes on `builds.<name>.bwrap`

With `bwrap.enabled`, Werkator shells out to the `bwrap` CLI (bubblewrap) instead of native execution.
This is the third runtime, for hosts without root and without a Docker daemon (e.g. Hostsharing managed webspaces); see `docs/plan/17-bwrap-build-runtime.md` and ADR 0007.
`bwrap` must be on the `PATH`.

`bwrap.rootfs` names the prepared root filesystem archive — a Debian-base rootfs with the build tools (JDK, git, locales, project-specific tooling) built elsewhere, since `debootstrap` is unavailable on the target.
It is a local path or an `http(s)` URL; a URL is downloaded once.
Build the archive with `tools/build-bwrap-rootfs.sh` on any machine with Docker; verify the host's user-namespace capability first with `tools/werkator-build-prerequisites.sh`.
The archive is unpacked on demand (`tar --no-same-owner`) into `.git/werkator/buildenv/<envKey>/rootfs`, shared across all branch worktrees like the Docker Gradle cache volume; `<envKey>` derives from a hash of the source, so a changed `rootfs` unpacks a fresh environment and stale ones can be pruned.
Per-branch Gradle caches persist in `.git/werkator/buildenv/home`, bound as `/root`.
`bwrap.env` adds environment variables inside the sandbox.
Files created inside the sandbox are owned by the host user, because uid 0 maps back to the unprivileged webspace user.

`docker` and `bwrap` are mutually exclusive per branch: enabling both is rejected at start, not silently picked.
Git works inside the sandbox exactly as inside the Docker container: the primary `.git` is mounted read-only with `.git/werkator/` masked, so builds can run read-only git commands but never reach the machine config or the control token.

## `.git/werkator/.werkator.yml` (not committed)

```yaml
# Machine- or user-specific overrides and secrets. Keys here win over .werkator.yml.
git:
  account: my-user              # technical username for git HTTPS authentication
  token: glpat-xxxxxxxxxxxxxxxxxxxx # Gitea API token — never commit this
```
