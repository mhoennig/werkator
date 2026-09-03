# Werkator Bootstrapping

Bootstrapping prepares a git repository for use with werkator.
It creates the config files described in [configuration.md](configuration.md).
With `init --systemd` it also generates a systemd user unit for running the server permanently, see [deployment.md](deployment.md).

Run `init` once per repository, from within a checked-out working tree.

## Prerequisites

- Git repository with at least one commit
- A remote named `origin` (used for auto-detection)
- Java runtime available (JRE 21)

## Running `init`

First, in `<werkator-root>`, build the application to generate the executable JAR file:

```bash
./gradlew build
```

Then run `init` using the generated JAR (not the `-plain.jar`):

```bash
java -jar <werkator-root>/build/libs/werkator.jar init
```

`init` performs the following steps in order:

### 1. Detect the Repository Root

Werkator resolves the repository root by running `git rev-parse --show-toplevel`.
If the current directory is not inside a git repository, `init` exits with an error.

### 2. Auto-detect Gitea Connection from `origin`

If `gitea.baseUrl`, `gitea.owner`, and `gitea.repo` are already set in `.werkator.yml`, these values are used.

Otherwise, Werkator inspects the `origin` remote URL and derives the Gitea connection defaults:

| Origin URL form                            | Detected values                        |
|--------------------------------------------|----------------------------------------|
| `https://git.example.org/my-org/my-repo`   | baseUrl, owner, repo                   |
| `git@git.example.org:my-org/my-repo.git`   | baseUrl, owner, repo                   |

The `.git` suffix is stripped from the repo name. The username embedded in HTTPS URLs
(e.g. `https://user@git.example.org/…`) is used as the default `git.account`.

- **`gitea.owner`**: The Gitea user or organization owning the repository. Used for Gitea API operations, such as reporting build status checks.
- **`git.account`**: The technical username used for git HTTPS authentication.

### 3. Create the Repo-Install Config

Creates `.git/werkator/.werkator.yml` (and its parent directory if needed).
This file is **never committed** to the repository and is used for all branches,
as long as not overridden by a project config.

If the file already exists, `init` prints a notice and leaves it untouched.

The generated file contains the machine-local secrets with auto-detected values pre-filled:

```yaml
git:
  account: <detected-or-placeholder>
  token:   # paste your Gitea API token here
```

### 4. Create the Branch/Project Config

Creates `.werkator.yml` in the repository root with project-level defaults.

If the file already exists, `init` prints a notice and leaves it untouched.

The generated file is a commented template based on the defaults documented in
[configuration.md](configuration.md) and includes the auto-detected Gitea settings:

```yaml
gitea:
  baseUrl: <detected-or-placeholder>
  owner:   <detected-or-placeholder>
  repo:    <detected-or-placeholder>

...
```

Then, you have to configure *Werkator* by amending this config file according to [configuration.md](configuration.md). 

### 5. Optionally Install an Instance Fragment (`--apply`)

`init --apply FILE` installs a YAML fragment in the configuration schema as the applied instance layer — see [configuration.md](configuration.md#the-applied-instance-fragment-init---apply).
Deployment tooling hands its parameters over this way instead of patching config files; the fragment is validated strictly and replaced wholesale on re-apply.
It runs before `--systemd`, so an applied `server.port` reaches the generated unit and the Apache `.htaccess` (written beside the units when a `publicBaseUrl` is configured, together with the static `werkator-maintenance.html` its `ErrorDocument`s fall back to while the service restarts).

## Output

`init` prints one line per action taken:

```
created .git/werkator/.werkator.yml
created .werkator.yml
```

Or, when files already exist:

```
.git/werkator/.werkator.yml already exists — not overwritten
.werkator.yml already exists — not overwritten
```

## Hosts Without a Java Runtime

Werkator is intended to run on Hostsharing Container Server environments, which provide Docker and git but no Java runtime.
For these hosts, `./gradlew runtimeBundle` builds a self-contained runtime bundle (jlink-trimmed JRE + JAR + launcher) — see [deployment.md](deployment.md) and ADR 0006.
A containerized Werkator runtime was considered and rejected there.

## Next Steps After `init`

1. Open `.git/werkator/.werkator.yml` and set `git.token` and `git.account`.
2. Review `.werkator.yml` and add/adjust any branch build settings.
3. Verify the effective configuration:
   ```bash
   java -jar build/libs/werkator.jar config:print --full
   ```
4. Start the server:
   ```bash
   java -jar build/libs/werkator.jar server
   ```
5. For permanent operation, install the systemd user service described in [deployment.md](deployment.md).

## Example: Test Server with a Fake Build

[examples/setup-werkator-testserver.sh](examples/setup-werkator-testserver.sh) starts a Werkator server against a scratch repository with a fake build — the setup used for the manual UI/API smoke tests during development.
It creates a local bare origin plus a `work` clone, commits a slow fake build script (live log output, demo report artifact) with a `pollInterval: 5s` config, and starts the server on port 18980.
The origin gets a second branch (`feature/demo`), so the Branches view shows more than one entry.
No Gitea, no credentials, no Docker; `INSTALL_DIR`, `SERVER_PORT`, and `BUILD_SECONDS` can be overridden via environment variables.
While the server runs, push empty commits from the `work` clone to trigger builds; a commit message containing `[fail]` makes the build fail, and pushing a new branch exercises the new-origin-branch path.

## Example: Self-Hosting Werkator

[examples/setup-werkator-selfhost.sh](examples/setup-werkator-selfhost.sh) shows the full sequence as a runnable script: it sets up a Werkator instance that watches and builds Werkator itself.
Run it from a working checkout; it builds the JAR, creates a dedicated clone, runs `init`, writes the machine-specific config, and starts the server.
`INSTALL_DIR`, `ORIGIN_URL`, `SERVER_PORT`, `GIT_ACCOUNT`, and `GIT_TOKEN` can be overridden via environment variables.
The script also demonstrates the kick-start trick: resetting the local ref one commit behind origin makes the very first poll build immediately, instead of waiting for the next push.
