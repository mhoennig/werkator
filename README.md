# werkator

Lightweight, declarative and highly opinionated software build system (CI/CD).

## Documentation

- [docs/configuration.md](docs/configuration.md) — configuration reference
- [docs/bootstrapping.md](docs/bootstrapping.md) — initializing a repository with `init`
- [docs/deployment.md](docs/deployment.md) — running werkator as a systemd service behind a reverse proxy
- [docs/migration-from-legacy.md](docs/migration-from-legacy.md) — migrating from the legacy bash script

## Legacy Script

`legacy/werkator` (bash) is **deprecated** and kept only as a behavioral reference for the rewrite.
Do not use it for new installations; see [docs/migration-from-legacy.md](docs/migration-from-legacy.md).

## Developer Setup

Source `.envrc` to add `tools/` to your `PATH`, or install [direnv](#direnv) to have this done automatically on `cd`:

```bash
source .envrc
```

Common tasks:

```bash
./gradlew build          # compile and run all checks
./gradlew test           # run tests
./gradlew ktlintFormat   # auto-format Kotlin sources
./gradlew ktlintCheck    # check formatting (also runs as part of build)

adr-status               # show all architecture decisions at a glance
```



### direnv

[direnv](https://direnv.net/) sources `.envrc` automatically whenever you enter the repository and unloads it when you leave.

```bash
# Ubuntu
sudo apt install direnv

# add to ~/.bashrc or ~/.zshrc
eval "$(direnv hook bash)"   # or: eval "$(direnv hook zsh)"
```

Trust the project's `.envrc` once per clone:

```bash
direnv allow
```

### Tools (`tools/`)

| Command | Description |
|---|---|
| `adr-status` | List all Architecture Decision Records with their status and decision summary |

## Architecture Decision Records

Major technical decisions are documented as ADRs in [`docs/adrs/`](docs/adrs/).

```bash
adr-status   # show all decisions at a glance
```

New ADRs follow the template at [`docs/adrs/0000-00-00.adr-template.md`](docs/adrs/0000-00-00.adr-template.md).
