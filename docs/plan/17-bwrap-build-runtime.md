# Step 17: Bubblewrap Build Runtime for Managed Webspaces

Prerequisites: steps 11, 15, 16.
Read `README.md` first.
Motivated by running GitTally on Hostsharing **Managed Webspaces**: no root, no Docker daemon, but `bwrap` (bubblewrap) is available and unprivileged user namespaces are allowed.
Target use case: GitTally builds GitTally itself on a Managed Webspace; builds needing special dependencies get them from a prepared root filesystem instead of the host.

## Precondition Check (run on the target webspace first)

The whole step hinges on one hard precondition: unprivileged user namespaces with uid-0 mapping and read-only root binds must work.
Verify it with a single command line on the target webspace **before** starting implementation:

```bash
bwrap --unshare-user --unshare-pid --die-with-parent --uid 0 --gid 0 \
      --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp \
      sh -c 'id -u && cat /proc/self/uid_map && (touch /usr/ro-test 2>&1 || true)'
```

Expected output:

- `0` — the build runs as root inside the namespace.
- a uid_map like `0 <webspace-uid> 1` — root maps back to the unprivileged webspace user.
- `touch: cannot touch '/usr/ro-test': Read-only file system` — the read-only root bind is enforced.

If this fails (`bwrap` missing, "setting up uid map: Permission denied", or no user namespace support), the approach is dead on that host — record the result in this file either way.

## Goal

A third build runtime behind the `BuildRunner` interface: `BwrapBuildRunner`, selected per branch via config, sandboxing the build in an unprivileged user namespace with a prepared Debian root filesystem.
No root on the host, no Docker daemon, no changes to the native and Docker runtimes.

## Design

### Prepared root filesystem

`debootstrap`/`mmdebstrap` are not available on the webspace, so the rootfs is **not created on the target system**.
It is built once elsewhere (any machine with Docker or root, e.g. a container VM) and distributed as an archive, e.g. `gittally-buildenv-trixie-java21.tar.zst`, containing Debian plus all build dependencies (JDK 21, git, locales, project-specific tools).
GitTally unpacks it on demand (`tar --no-same-owner`) into `.git/gittally/buildenv/<envKey>/rootfs` — **not** into the working tree.
Like the Docker image and the Gradle cache volume, the environment is shared across all branch worktrees and survives worktree pruning; `<envKey>` derives from a hash of the configured archive source, so an environment-version change unpacks a fresh rootfs and stale ones can be pruned.

### Configuration

New `branches.<name>.bwrap` section: `enabled`, `rootfs` (path or URL of the archive), `env` (like `docker.env`).
`bwrap.enabled` and `bwrap.rootfs` join the **pinned sandbox-policy set** (like `docker.enabled`/`docker.network`): a branch must not be able to switch off its sandbox or substitute a foreign rootfs via its committed config.
`docker.enabled` and `bwrap.enabled` are mutually exclusive per branch — reject the config, do not pick silently.
Keep the three config places in sync: `GitTallyConfig`, the `InitCommand` templates, `docs/configuration.md`.

### Invocation

`BwrapBuildRunner` shells out to the `bwrap` CLI (no library, like git and docker):

```
bwrap --unshare-user --unshare-pid --die-with-parent --uid 0 --gid 0 \
      --ro-bind <buildenv>/rootfs / \
      --bind <workspace> <workspace> \
      --bind <buildenv>/home /root \
      --ro-bind /etc/resolv.conf /etc/resolv.conf \
      --proc /proc --dev /dev --tmpfs /tmp \
      --chdir <workspace> \
      /bin/sh -c '<buildCommand>'
```

- The workspace is bound at its **host path**, not at `/workspace`: the worktree's `.git` pointer file contains absolute host paths, and the step-16 git metadata mounts (`--ro-bind` of the primary `.git`, `--tmpfs` over `.git/gittally`, `--bind` of `.git/worktrees/<key>`) port 1:1 — reuse that logic, do not duplicate it.
- `<buildenv>/home` bound as `/root` gives Gradle a persistent `$HOME` (wrapper dists, `.gradle` caches) — the bwrap sibling of the Docker runner's Gradle cache volume.
- `--die-with-parent` plus `--unshare-pid`: cancellation kills the returned `bwrap` process tree and nothing survives — same semantics as the other runtimes.
- No ownership repair is needed: files created as uid 0 inside the namespace are owned by the webspace user on the host.

### Known limitations (document, do not solve here)

- Network stays shared with the host (Gradle needs it); isolation is weaker than Docker's per-container network.
- No Docker inside the sandbox, so no Testcontainers-based tests; build commands must select a Docker-free test subset.
  For GitTally's own build this means `TestcontainersSmokeTest` must become conditional (`enabledIf` docker present) — that change is part of this step.

## ADR

Write ADR 0007: bubblewrap user-namespace sandbox as the third build runtime (options considered: bwrap (chosen), proot/fakechroot (slow, fragile), plain native with hand-installed toolchains (no isolation, host pollution)).

## Tests

- `BwrapBuildRunnerTest`: exact argv assertions (mount set, uid mapping, chdir, env), rootfs unpack-on-demand and env-key change, mocked process runner — mirror `DockerBuildRunnerTest`.
- `DispatchingBuildRunnerTest`: routing for `bwrap.enabled`, config rejection when both runtimes are enabled.
- `ConfigLoader` tests: the pinned set strips `bwrap.enabled`/`bwrap.rootfs` from the worktree layer.

## Acceptance Criteria

- The precondition command line above passes on the target webspace; its output is recorded in this file.
- `./gradlew ktlintFormat` then `./gradlew build` is green — also on a machine without Docker (Testcontainers smoke test skipped, not failed).
- On a Managed Webspace: GitTally (from the runtime bundle) builds a real branch of a repo inside the bwrap sandbox; git commands work in the worktree; `.git/gittally/` is not readable from the build; a write to `/usr` fails.
- Docs updated: `docs/configuration.md` (bwrap section), architecture skill (third runtime), ADR 0007.
