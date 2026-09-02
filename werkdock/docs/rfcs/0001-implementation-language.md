# RFC 0001: Implementation Language for Werkdock

**Status:**
- proposed: 2026-09-01
- accepted: 2026-09-01
- rejected: -

**Proposal:** Werkdock is implemented in **Go** — as a single static binary, stdlib-only, with the sandbox engine behind an interface so bwrap can later be replaced by native namespaces.

## Context and Problem Statement

Werkdock is a docker-like sandbox CLI over `bwrap`, filesystem isolation only (see [README](../../README.md) and Werkator plan step 21).
Three hard requirements drive the language choice:

1. **Distribution to a Managed Webspace without root** — the tool must arrive and run with no package installation and no runtime dependency on the host.
2. **The work is process and filesystem orchestration** — spawning `bwrap`/`tar`/`zstd` with streamed logs and forwarded signals, assembling mount arguments, `doctor` checks.
3. **Self-contained and testable** — no code sharing and no build coupling with Werkator; the integration is `werkdock run` as a CLI call, like git and docker.

Two further criteria matter in this project:

- **AI-generated code quality** — the tool is developed AI-assisted; languages where generated code is reliably correct and idiomatic reduce review load.
- **Security** — Werkdock assembles mount arguments and uid mappings from user input; language safety and a small supply chain count.

## Considered Options

bash, Python 3, Kotlin Native, Rust, Go.

Scoring: −2 (unsuitable) to +2 (ideal), unweighted sum.

| Criterion | bash | Python 3 | Kotlin Native | Rust | Go |
|---|---:|---:|---:|---:|---:|
| Distribution to webspace (no root) | +2 | +1 | −1 | +2 | +2 |
| Fit for process/FS orchestration | +1 | +2 | 0 | +2 | +2 |
| Testability | −2 | +2 | +1 | +2 | +2 |
| Robustness/maintainability as it grows | −2 | +1 | +1 | +2 | +2 |
| Closeness to the maintainer's stack (Kotlin dev) | 0 | +1 | +2 | −1 | +1 |
| Genre references to learn from | −1 | 0 | −1 | +1 | +2 |
| Future: own namespaces instead of bwrap | −2 | −1 | 0 | +2 | +1 |
| Toolchain/build effort | +2 | +2 | −2 | 0 | +2 |
| AI-generated code quality | −1 | +2 | 0 | +1 | +2 |
| Security | −2 | +1 | +1 | +2 | +2 |
| **Sum** | **−5** | **+11** | **+1** | **+13** | **+18** |

The ranking is robust against re-weighting: Go scores below +1 in no criterion — it wins by absence of weaknesses, not by one outlier.

### bash

Out on principle: the Werkator repository exists because a grown bash CI script became unmaintainable.
A tool with subcommands, image/instance state, and doctor checks starts beyond the bash comfort zone.
AI generates bash fluently but with the classic silent defects (quoting, word splitting, unchecked exit codes), and the missing test story means nobody notices.
Security −2 is earned: injection via word splitting in exactly the kind of code Werkdock writes — user-supplied paths assembled into mount arguments.
The existing scripts serve as specification, not as foundation.

### Python 3

The best "no new compiler" candidate: present on every Debian webspace, the stdlib suffices (unpacking `tar.zst` shells out to `zstd` anyway), excellent testability, excellent AI generation.
Weaknesses: version drift across hosts (3.11/3.13), no static type check at runtime, and the tool runs as a tamperable source file on the host interpreter instead of as a binary.

### Kotlin Native

Loses despite maximum stack closeness, and not narrowly — the weakness sits exactly where Werkdock lives:

- **The stdlib gap hits the tool's core.** Kotlin never had its own system libraries; on the JVM it delegates file, process, and IO work to the JDK. On Native that platform library is gone and only `platform.posix` remains. Werkdock's central operation — spawning processes with log streaming, signal forwarding, and exit codes — means hand-written `fork`/`execvp`/`waitpid` over cinterop.
- **Kotlin Native was built for iOS, not for CLI tools.** The driver was Kotlin Multiplatform (no JVM allowed on iPhone); the kotlinx ecosystem grew what mobile apps need. Mobile apps never spawn child processes, so no official process API exists.
- **AI drifts to the JVM.** The Kotlin training corpus is overwhelmingly JVM/Android; models reliably propose `ProcessBuilder` and `java.nio`, which do not exist on Native.
- **Distribution is build-machine-bound.** Unlike the jlink bundle (which copies Temurin's prebuilt binaries, glibc floor 2.15, measured in Werkator ADR 0006), Kotlin Native compiles locally, so the binary's glibc floor is the build machine's.
- **The expected payoff never materializes.** There is no shared code and no shared build graph with Werkator by design; "same language" buys only developer familiarity — and JVM-library-free Native Kotlin feels more foreign than Go does after a week.

The honest variant of language consistency — Kotlin/JVM plus a jlink bundle like Werkator itself — was not on the ballot and would be disproportionate: a ~66 MB bundle for a sandbox helper copied to foreign webspaces, against one static Go binary.

### Rust

Technically the strongest language for the genre and the best if Werkdock one day opens namespaces itself (direct syscalls, `youki` as a memory-safe sandbox reference).
Price: the steepest learning curve for a Kotlin developer and the slowest progress; AI-generated Rust needs iterations at the borrow checker, which the compiler at least enforces loudly.

### Go

The sweet spot:

- The container world Werkdock imitates is written in Go — docker CLI, podman, runc — so every subproblem has a proven, readable reference.
- One static binary (`CGO_ENABLED=0`) is the perfect webspace distribution; cross-compilation is a `GOOS`/`GOARCH` pair; builds take seconds.
- Testing is built in; `gofmt` knows exactly one style, which makes AI-generated Go above-average correct on the first attempt.
- The stdlib covers everything the tool does (`os/exec`, `os`, `io`, `archive/tar`), keeping the dependency list near zero — the smallest supply chain in the field.
- Coming from Kotlin, Go is productive within days: garbage collector, familiar concepts, deliberately small language.

## The Namespace Future, Concretely

Own namespaces instead of shelling out to `bwrap` are a real option, and Go keeps it open:

- The webspace kernel provably allows unprivileged user namespaces — Debian's `bwrap` has not been setuid since bookworm and uses nothing else.
- Go needs no cgo for it: namespaces are created when spawning the child via `SysProcAttr` (`Cloneflags`, `UidMappings`/`GidMappings`), with the usual re-exec pattern (`werkdock run` starts itself as a hidden init subcommand inside the fresh namespaces, sets up mounts, then execs the payload).
- The concrete payoff: since kernel 5.11, overlayfs mounts are allowed inside a user namespace unprivileged — the webspace runs 6.1, but its `bubblewrap 0.8.0` has no `--overlay` (added in 0.9.0). Own namespace code could provide the throwaway writable layer per build today.
- The counterweight: `bwrap` is hardened, Flatpak-tested code, and if the platform ever adopts an AppArmor userns restriction (as Ubuntu 24.04 did), the distribution's `bwrap` would likely stay permitted while a brought-along binary gets its `clone()` refused.

Consequence for the design, independent of the engine question's outcome: the sandbox engine sits behind an interface from the start — engine 1 is `bwrap` (present, proven, invocation logic exists), engine 2 can later be native namespaces.

## Concrete Proposal

1. **Language**: Go, current stable toolchain, pinned in `go.mod` (`toolchain` directive).
2. **Module**: `werkdock` as its own Go module in this subdirectory — no Gradle involvement, `go build` / `go test` / `go vet` are the whole toolchain.
3. **Dependency policy**: stdlib-only; any third-party dependency needs an RFC.
4. **Distribution**: one static linux/amd64 binary, built with `CGO_ENABLED=0`; other architectures are a build-matrix entry away if ever needed.
5. **Style and quality gates**: `gofmt` (enforced), `go vet`, table-driven tests with the built-in `testing` package.
6. **Architecture**: CLI semantics (`run`, images, instances, `doctor`) decoupled from a sandbox engine interface; `bwrap` is the first engine, native namespaces a possible second.
7. **External processes**: `bwrap`, `tar`, `zstd` are called as CLIs via `os/exec` — the same pattern Werkator uses for git and docker.

## Decision Outcome

Accepted on 2026-09-01: Werkdock is implemented in Go, under the terms of the concrete proposal above.
