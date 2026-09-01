# RFC 0003: Composable Toolchain Mounts

**Status:**
- proposed: 2026-09-01 (as a candidate — comes due when more than one toolchain combination is needed)
- accepted: -
- rejected: -

**Proposal:** Instead of baking every toolchain combination into its own flat image, werkdock composes a sandbox at run time: a slim base image plus per-toolchain artifacts from the store, mounted read-only under their own prefixes.

## Context and Problem Statement

Werkdock images are flat trees without layers (see the Disk Footprint section of the README): every toolchain combination is a full archive, built, uploaded, and unpacked as a whole.
The pain is concrete: adding Go and Node to the JDK build environment meant rebuilding and re-uploading a ~600 MB archive whose JDK half did not change.
Docker solves this with content-addressed layers over overlayfs — which needs either root or an overlay-capable bwrap (0.9+), neither available on the target webspaces today.

## The Key Insight

Overlayfs is only needed when trees must merge *at the same paths*.
Toolchains that live under their own prefix need no merging at all — and the official tarball distributions do exactly that:

- Go unpacks to `/usr/local/go`
- Node unpacks to `/usr/local/node-<version>`
- Temurin JDKs unpack to `/usr/local/jdk-<version>`

So composition is plain bind mounts, available in every bwrap version, no root, no overlayfs:

```
werkdock run --rm --with jdk-21 --with go-1.24 --with node-20 base sh -c '...'
```

## Sketch

- The base image shrinks to what apt must provide (debootstrap minbase, git, ca-certificates, locales — roughly 300 MB unpacked).
- A *toolchain* is a store artifact beside images: an unpacked tarball plus a small manifest naming its mount prefix and the environment it needs (`PATH` entries, `JAVA_HOME`, `GOROOT`, ...).
- `--with NAME` adds a read-only bind of the toolchain at its prefix and applies its manifest environment; order follows the flags, like `-v`.
- Deduplication falls out for free: each toolchain is stored once, every combination costs zero additional disk.

## Limits

- Only tarball-distributed toolchains fit; apt-installed ones spread across `/usr` and cannot be prefix-mounted.
  For JDK, Go, and Node the official tarballs exist; toolchains without one stay in the base image.
- `--with` is a werkdock extension beyond the docker-compatible surface (RFC 0002) — docker has no counterpart.
  It is additive: level-1 compatibility of the remaining CLI is untouched.

## Considered Alternatives

- On-target image building (apt/mmdebstrap on the webspace, unprivileged): technically possible via user namespaces, but slow, network-bound per build, and a relapse into the self-build drift step 21 corrects — build locally, install artifacts.
- Letting package managers fill a persistent home cache (Gradle toolchains, Go modules): works today as a side effect, but unhermetic and network-dependent on cold caches.
- Overlayfs layers or hardlink dedup between image versions: the general solutions, still worthwhile later, but blocked on bwrap 0.9+ (overlay) or more store machinery (dedup) — composition needs neither.

## Decision Outcome

Pending — to be decided when a second toolchain combination is actually needed (for example Werkbaum pinning its own Node version).
Until then the one fat image (RFC 0002 outcome, plan step 21) stays the deliberate choice.
