# Werkdock

A docker-like sandbox CLI over `bwrap` — filesystem isolation only.
A dock is the enclosed basin in which ships are built: the dock gate controls what passes, the water outside is shared with the whole harbor.
Accordingly, network, uid, `/proc`, `/dev`, and `/tmp` come from the host by contract; that is what makes Werkdock work without root on a Hostsharing Managed Webspace.

Semantics — docker-compatible as far as the filesystem-only contract allows (see [RFC 0002](docs/rfcs/0002-docker-compatible-surface.md)):

- An *image* is a rootfs archive; an *instance* is an unpacked, writable directory tree and corresponds to a docker container.
- `werkdock run [flags] IMAGE [CMD...]` creates an instance and executes in the sandbox with uid 0 mapped to the calling user; verbs and flags follow docker, unsupported docker flags fail loudly.
- `werkdock doctor` checks the host: user-namespace capability, disk and quota headroom.
- A daemon speaking the Docker Engine API subset (for Testcontainers) is designed for but deferred.

## Build and Test

```bash
go test ./...                      # all tests; sandbox integration tests skip without bwrap/userns
go vet ./... && gofmt -l .         # quality gates (gofmt must print nothing)
CGO_ENABLED=0 go build .           # one static linux binary, ~3 MB
```

First steps on a host:

```bash
werkdock doctor                    # can this host run sandboxes?
werkdock load -i rootfs.tar.zst    # import a rootfs archive as an image
werkdock run --rm -v /repo:/repo -w /repo IMAGE sh -c './gradlew build'
```

Status: bootstrap.
The implementation language is Go, decided in [RFC 0001](docs/rfcs/0001-implementation-language.md).
Werkdock grows in this subdirectory of the Werkator repository and moves to its own repository once it stands on its own.
It must stay self-contained: no imports from Werkator code, no Gradle coupling to the Werkator build.
The roadmap is session B of [docs/plan/21-werkdock-extraction-and-webspace-install.md](../docs/plan/21-werkdock-extraction-and-webspace-install.md).
