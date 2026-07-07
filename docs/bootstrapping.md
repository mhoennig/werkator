# GitTally Bootstrapping

Bootstrapping prepares a git repository for use with GitTally.
It creates the config files described in [configuration.md](configuration.md).
With `init --systemd` it also generates a systemd user unit for running the server permanently, see [deployment.md](deployment.md).

Run `init` once per repository, from within a checked-out working tree.

## Prerequisites

- Git repository with at least one commit
- A remote named `origin` (used for auto-detection)
- Java runtime available (JRE 21)

## Running `init`

First, in `<gittally-root>`, build the application to generate the executable JAR file:

```bash
./gradlew build
```

Then run `init` using the generated JAR (not the `-plain.jar`):

```bash
java -jar <gittally-root>/build/libs/gittally-0.1.0-SNAPSHOT.jar init
```

`init` performs the following steps in order:

### 1. Detect the Repository Root

GitTally resolves the repository root by running `git rev-parse --show-toplevel`.
If the current directory is not inside a git repository, `init` exits with an error.

### 2. Auto-detect Gitea Connection from `origin`

If `gitea.baseUrl`, `gitea.owner`, and `gitea.repo` are already set in `.gittally.yml`, these values are used.

Otherwise, GitTally inspects the `origin` remote URL and derives the Gitea connection defaults:

| Origin URL form                            | Detected values                        |
|--------------------------------------------|----------------------------------------|
| `https://git.example.org/my-org/my-repo`   | baseUrl, owner, repo                   |
| `git@git.example.org:my-org/my-repo.git`   | baseUrl, owner, repo                   |

The `.git` suffix is stripped from the repo name. The username embedded in HTTPS URLs
(e.g. `https://user@git.example.org/…`) is used as the default `git.account`.

- **`gitea.owner`**: The Gitea user or organization owning the repository. Used for Gitea API operations, such as reporting build status checks.
- **`git.account`**: The technical username used for git HTTPS authentication.

### 3. Create the Repo-Install Config

Creates `.git/gittally/.gittally.yml` (and its parent directory if needed).
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

Creates `.gittally.yml` in the repository root with project-level defaults.

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

Then, you have to configure *gitTally* by amending this config file according to [configuration.md](configuration.md). 

## Output

`init` prints one line per action taken:

```
created .git/gittally/.gittally.yml
created .gittally.yml
```

Or, when files already exist:

```
.git/gittally/.gittally.yml already exists — not overwritten
.gittally.yml already exists — not overwritten
```

## Future: Docker-based Deployment

GitTally is intended to run on Hostsharing Container Server environments, which provide Docker
but no Java runtime. A later development step will add a Docker image distribution where:

- GitTally itself runs as a Docker container (image bundles the JRE + JAR)
- Builds are spawned by mounting the host Docker socket (`/var/run/docker.sock`)
- `init` then optionally generates a `docker-compose.yml`, a secrets env file, and a systemd unit
  that starts the Compose stack at boot

Until then, a Java runtime must be available on the host.

## Next Steps After `init`

1. Open `.git/gittally/.gittally.yml` and set `git.token` and `git.account`.
2. Review `.gittally.yml` and add/adjust any branch build settings.
3. Verify the effective configuration:
   ```bash
   java -jar build/libs/gittally-0.1.0-SNAPSHOT.jar config:print --full
   ```
4. Start the server:
   ```bash
   java -jar build/libs/gittally-0.1.0-SNAPSHOT.jar server
   ```
5. For permanent operation, install the systemd user service described in [deployment.md](deployment.md).

## Example: Test Server with a Fake Build

[examples/setup-gittally-testserver.sh](examples/setup-gittally-testserver.sh) starts a GitTally server against a scratch repository with a fake build — the setup used for the manual UI/API smoke tests during development.
It creates a local bare origin plus a `work` clone, commits a slow fake build script (live log output, demo report artifact) with a `pollInterval: 5s` config, and starts the server on port 18980.
The origin gets a second branch (`feature/demo`), so the Branches view shows more than one entry.
No Gitea, no credentials, no Docker; `INSTALL_DIR`, `SERVER_PORT`, and `BUILD_SECONDS` can be overridden via environment variables.
While the server runs, push empty commits from the `work` clone to trigger builds; a commit message containing `[fail]` makes the build fail, and pushing a new branch exercises the new-origin-branch path.

## Example: Self-Hosting GitTally

[examples/setup-gittally-selfhost.sh](examples/setup-gittally-selfhost.sh) shows the full sequence as a runnable script: it sets up a GitTally instance that watches and builds GitTally itself.
Run it from a working checkout; it builds the JAR, creates a dedicated clone, runs `init`, writes the machine-specific config, and starts the server.
`INSTALL_DIR`, `ORIGIN_URL`, `SERVER_PORT`, `GIT_ACCOUNT`, and `GIT_TOKEN` can be overridden via environment variables.
The script also demonstrates the kick-start trick: resetting the local ref one commit behind origin makes the very first poll build immediately, instead of waiting for the next push.
