# GitTally

Lightweight, declarative and highly opinionated software build system (CI/CD).

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
