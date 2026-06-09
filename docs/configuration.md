# GitTally Configuration Reference

GitTally is configured via YAML files. Settings are merged from two sources in order — later layers override earlier ones.

## Config File Locations

| Layer                    | Path                       | Committed to Git | Purpose                                      |
|--------------------------|----------------------------|------------------|----------------------------------------------|
| Project config           | `.gittally.yml`            | Yes              | Shared team settings                         |
| Repo installation config | `.git/gittally/config.yml` | No               | Machine- or user-specific overrides, secrets |

The repo install config (`.git/gittally/config.yml`) wins on any key present in both files. Typically used to set `gitea.token` without committing it.

## Inspect the Effective Config

```bash
java -jar gittally.jar config:print         # only explicitly set values
java -jar gittally.jar config:print --full  # all values including defaults
```

## `.gittally.yml`

Values shown are the defaults.

```yaml
server:
  # Public base URL of this GitTally installation — used for all links posted to Gitea.
  publicBaseUrl: https://ci.example.org/

# Gitea integration for fetching commits and posting build statuses.
gitea:
  baseUrl: https://git.example.org   # base URL of the Gitea instance
  owner: my-org                      # repository owner (user or organisation)
  repo: my-repo                      # repository name
  statusContext: GitTally            # label shown on Gitea commit status checks (default: GitTally)

# Build artifact retention.
artifacts:
  # number of builds to keep per branch
  retentionPerBranch: 3

# Controls the branch-polling loop.
watcher:
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

## `.git/gittally/config.yml` (not committed)

```yaml
# Machine- or user-specific overrides. Keys here win over .gittally.yml.
gitea:
  gitUsername: my-user              # git username for HTTPS authentication
  token: glpat-xxxxxxxxxxxxxxxxxxxx # Gitea API token — never commit this
```
