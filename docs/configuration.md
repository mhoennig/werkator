# GitTally Configuration Reference

GitTally is configured via YAML files. Settings are merged from two sources in order — later layers override earlier ones.

## Config File Locations

| Layer                    | Path                       | Committed to Git | Purpose                                      |
|--------------------------|----------------------------|------------------|----------------------------------------------|
| Project config           | `.gittally.yml`            | Yes              | Shared team settings                         |
| Repo installation config | `.git/gittally/.gittally.yml` | No               | Machine- or user-specific overrides, secrets |

The repo install config (`.git/gittally/.gittally.yml`) wins on any key present in both files. Typically used to set `git.token` and `git.account` without committing them.

## Inspect the Effective Config

```bash
java -jar build/libs/gittally-0.1.0-SNAPSHOT.jar config:print         # only explicitly set values
java -jar build/libs/gittally-0.1.0-SNAPSHOT.jar config:print --full  # all values including defaults
```

## `.gittally.yml`

Values shown are the defaults.

```yaml
server:
  # Public base URL of this GitTally installation — used for all links posted to Gitea.
  publicBaseUrl: https://ci.example.org/
  # HTTP port of the `server` subcommand (default 18080, like legacy)
  port: 18080
  # bind address of the `server` subcommand
  bindAddress: 0.0.0.0
  # optional Impressum (legal disclosure) link in the web UI footer; empty hides the link
  impressumUrl: ""

# Gitea integration for fetching commits and posting build statuses.
gitea:
  baseUrl: https://git.example.org   # base URL of the Gitea instance
  owner: my-org                      # repository owner (user or organisation) for Gitea API (e.g. status checks)
  repo: my-repo                      # repository name
  statusContext: GitTally            # label shown on Gitea commit status checks (default: GitTally)

# Build execution.
builds:
  # How many branches may build at the same time.
  # At most one build per branch runs regardless; each branch builds in its own
  # git worktree under .git/gittally/worktrees/, never in the primary checkout.
  # Changing this value requires a restart.
  maxConcurrent: 1

# Build artifact storage and retention.
artifacts:
  # Root directory for stored build artifacts.
  # Empty means the platform default: $XDG_STATE_HOME (or ~/.local/state) plus /gittally/artifacts/<repo-key>,
  # where <repo-key> is the sanitized absolute repository path.
  # A leading ~/ expands to the home directory; a relative path is resolved against the repository.
  rootDir: ""
  # number of builds to keep per branch
  retentionPerBranch: 3

# Controls the branch-polling loop.
watcher:
  # delay between poll cycles; suffixes s (seconds), m (minutes), h (hours), d (days)
  pollInterval: 10s
  # max commit age for new origin branches to be pulled automatically
  newBranchMaxAge: 5d
  # Honor the branches.<name>.requirePullRequest gates (see notes below).
  # Set false for a plain git origin without pull-request refs (no Gitea/GitHub);
  # gated branches then build on new commits like any other branch.
  pullRequestGate: true

# Per-branch build configuration.
# Use "default" as the fallback for all branches not listed explicitly.
# Each entry merges build settings and auto-build scheduling.
branches:
  default:
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
    # Build this branch only while its head commit matches a pull-request head on origin
    # (refs/pull/*/head — read via plain git, no API token needed; see notes below).
    requirePullRequest: false
    autoBuild:
      enabled: false        # whether to rebuild on schedule
      times: ["01:00"]      # UTC times HH:MM for scheduled builds
    # Optional Docker build runtime; when enabled, the clean and build commands
    # run inside a container instead of natively (see notes below).
    docker:
      # run clean/build commands in a Docker container
      enabled: false
      # image for the build container; required when enabled
      image: ""
      # Dockerfile to (re)build the image from when it is missing or stale; empty pulls the image as-is
      dockerfile: ""
      # Docker build context used with dockerfile
      context: "."
      # Docker network mode for the build container; empty = Docker default
      network: ""
      # additional environment variables set inside the build container
      env: {}

  main:
    autoBuild:
      enabled: true

  master:
    autoBuild:
      enabled: true

  release:
    buildCommand: ./gradlew --console=plain --no-daemon --no-build-cache test jacocoReport
    autoBuild:
      enabled: true
      times:
        - "04:00"
```

### Notes on `branches.<name>.requirePullRequest`

The gate applies to all watcher-triggered builds (push-triggered and scheduled auto builds).
A manual `gittally build <branch>` always builds, regardless of this setting.

Detection works without a Gitea API token:
the watcher lists `refs/pull/*/head` on origin via `git ls-remote` and builds a branch only when its head commit equals one of those pull-request head commits.
This ls-remote call is made at most once per poll cycle, and only when a branch requiring a pull request is otherwise due.

Because matching is by commit id, a closed pull request whose head ref still equals the branch head also counts.
Distinguishing open from closed pull requests would require the Gitea API.

To build pull-request branches only, set the key under `branches.default` and override it for permanent branches:

```yaml
branches:
  default:
    requirePullRequest: true
  main:
    requirePullRequest: false
```

Without the `main` override, direct pushes and merges to `main` would never build — merge commits do not match any pull-request head.

A plain git origin (no Gitea/GitHub) serves no `refs/pull/*/head` at all, so gated branches would never build there.
For such origins, disable all gates globally with `watcher.pullRequestGate: false` — typically in the machine-specific `.git/gittally/.gittally.yml`, so the committed configuration keeps the gates for forge-backed environments.

### Notes on `branches.<name>.docker`

With `docker.enabled`, GitTally shells out to the `docker` CLI; the `docker` command must be on the `PATH`.
When `dockerfile` is set, the image is (re)built whenever the Dockerfile content, its path, or the context path changed.
Staleness is tracked via the image label `org.gittally.build-inputs-sha256`.
A Gradle cache volume `gittally-gradle-<repo-key>` is created per repository and mounted as `GRADLE_USER_HOME`.
The build worktree is bind-mounted into the container; after each command the ownership of `build/` and `.gradle/` is repaired to the host user.
The Docker socket is mounted into the container and `DOCKER_HOST`/`TESTCONTAINERS_*` variables are set, so Testcontainers-based builds work inside the container.
All GitTally containers carry `org.hoennig.gittally` labels; stale build containers of the repository are removed before the first Docker build after a restart.

## `.git/gittally/.gittally.yml` (not committed)

```yaml
# Machine- or user-specific overrides and secrets. Keys here win over .gittally.yml.
git:
  account: my-user              # technical username for git HTTPS authentication
  token: glpat-xxxxxxxxxxxxxxxxxxxx # Gitea API token — never commit this
```
