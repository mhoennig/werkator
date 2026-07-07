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
    autoBuild:
      enabled: false        # whether to rebuild on schedule
      times: ["01:00"]      # UTC times HH:MM for scheduled builds

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

## `.git/gittally/.gittally.yml` (not committed)

```yaml
# Machine- or user-specific overrides and secrets. Keys here win over .gittally.yml.
git:
  account: my-user              # technical username for git HTTPS authentication
  token: glpat-xxxxxxxxxxxxxxxxxxxx # Gitea API token — never commit this
```
