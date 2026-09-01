# Werkdock

A docker-like sandbox CLI over `bwrap` — filesystem isolation only.
A dock is the enclosed basin in which ships are built: the dock gate controls what passes, the water outside is shared with the whole harbor.
Accordingly, network, uid, `/proc`, `/dev`, and `/tmp` come from the host by contract; that is what makes Werkdock work without root on a Hostsharing Managed Webspace.

Semantics:

- An *image* is a rootfs archive.
- An *instance* is an unpacked, writable directory tree.
- `werkdock run <instance> -- <command>` executes in the sandbox with uid 0 mapped to the calling user.
- `werkdock doctor` checks the host: user-namespace capability, disk and quota headroom.

Status: bootstrap.
The implementation language is Go, decided in [RFC 0001](docs/rfcs/0001-implementation-language.md).
Werkdock grows in this subdirectory of the Werkator repository and moves to its own repository once it stands on its own.
It must stay self-contained: no imports from Werkator code, no Gradle coupling to the Werkator build.
The roadmap is session B of [docs/plan/21-werkdock-extraction-and-webspace-install.md](../docs/plan/21-werkdock-extraction-and-webspace-install.md).
