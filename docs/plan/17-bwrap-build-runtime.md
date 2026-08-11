# Step 17: Running GitTally on a Managed Webspace (bubblewrap builds + web access)

Prerequisites: steps 11, 15, 16.
Read `README.md` first.
Motivated by running GitTally on Hostsharing **Managed Webspaces**: no root, no Docker daemon, but `bwrap` (bubblewrap) is available and unprivileged user namespaces are allowed.
Target use case: GitTally builds GitTally itself on a Managed Webspace; builds needing special dependencies get them from a prepared root filesystem instead of the host.
Projects that need Docker for their own tests (hs.hsadmin.ng with Testcontainers) stay on a container host like vm4006 — the webspace is for Docker-free builds only.

The step covers two halves of the same deployment and is deliberately not split:
the build sandbox (most of this document) and the web access under a domain (last section).
Without the second half the first one only proves that sandboxed builds work somewhere; without the first one GitTally on a webspace would run builds unsandboxed on the host.

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

**Result: passed** on 2026-08-11, Hostsharing Managed Webspace `h68`, user `mih00`:

```
0
         0     102147          1
touch: cannot touch '/usr/ro-test': Read-only file system
```

All three signals as expected — root inside the namespace, mapped back to the unprivileged webspace uid, and the read-only root bind enforced.
So unprivileged user namespaces are available on Hostsharing Managed Webspaces and the step can proceed.
The host runs `bubblewrap 0.8.0` on kernel `6.1.0-52-amd64`.
Every option of the invocation below exists in 0.8.0 (`--die-with-parent` since 0.4.0, the rest is older), so the design stands as written.
What 0.8.0 lacks is overlayfs (`--overlay`, added in 0.9.0): a future "throwaway writable rootfs per build" cannot be built from an overlay here, only from tmpfs mounts over the writable spots.

**The runtime bundle runs there — checked, not assumed.** The webspace has glibc 2.36 (Debian 12), below the dev machine's 2.39, which by ADR 0006's original wording would have ruled the bundle out.
That wording was wrong and has been corrected: the bundle's highest required symbol version is `GLIBC_2.15`, because `jlink` copies Temurin's prebuilt binaries rather than compiling anything.
So no container build and no second build machine are needed for this platform.
The bundle's `java.desktop` module does carry X11, ALSA and freetype dependencies, but only in the AWT libraries, which a headless GitTally never loads — as on vm4006.

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

## Web Access under a Domain (no Docker, no managed nginx)

The managed nginx/TLS container from ADR 0005 is for container hosts without a reverse proxy.
A Managed Webspace does not need it: the platform provides Apache plus Let's Encrypt, and documents the reverse proxy to a self-hosted service.
Three platform-side prerequisites, none of them code:

1. **Book the "eigener Serverdienst" option** — a service user plus one reserved localhost port, requested from `service@hostsharing.net` stating the service user and the number of ports.
   Surcharged on Managed Webspaces (RAM contingent in 128 MB steps), included on Managed Servers.
   The port number is **assigned by Hostsharing** (wiki examples use 34567, 38005/38006), so it goes into `server.port` — GitTally's 18080 is not available by choice.
   Sources: [Individuelle Serverdienste](https://www.hostsharing.net/features/individuelle-serverdienste/), [Apache](https://www.hostsharing.net/features/apache/).
2. **Run the service as a systemd user unit** — mandatory on Managed Webspaces (no `nohup`, no supervisord); lingering needs a valid login shell configured in HSAdmin, and the account's RAM is capped by a slice (`systemctl status pacs-<account>.slice`).
   `gittally init --systemd` already generates the unit and the `gittally.env`, whose `JAVA_OPTS=-Xmx…` is what keeps the JVM inside the slice.
   Source: [Prozessmanagement mit systemd im Userspace](https://wiki.hostsharing.net/index.php/Prozessmanagement_mit_systemd_im_Userspace).
3. **Let's Encrypt** is a domain option ticked in HSAdmin (free, automatic, includes the wildcard subdomain; requires the domain's nameservers to be delegated to Hostsharing), so TLS terminates in the managed Apache.
   Source: [TLS](https://www.hostsharing.net/doc/managed-operations-platform/tls/).

### User model: a dedicated unix user, not the package admin

GitTally runs as its own unix user, e.g. `xyz00-gittally`, with the domain assigned to that same user (`domain.add({set:{name:'…',user:'xyz00-gittally'}})`), so the service, its repository checkout and `~/doms/<domain>/htdocs-ssl/` share one home directory.
That is what every Hostsharing service guide does (`xyz00-chat` for Mattermost, `xyz00-tomcat`, `xyz00-cloud` for Nextcloud) and what their user documentation recommends: a domain *can* run under the package admin, but "aus Sicherheitsgründen empfiehlt es sich aber Domains auf separate Domain-Admins aufzuschalten", so a compromise stays inside one home instead of reaching the whole package.
Here the argument is stronger than usual, because GitTally checks out foreign commits and executes their build scripts — running that as the package admin would undo the sandbox rationale of this very step.
The service user is named when ordering the daemon port anyway.
Sources: [Benutzer](https://www.hostsharing.net/doc/managed-operations-platform/benutzer/), [HSAdmin domain](https://www.hostsharing.net/doc/managed-operations-platform/hsadmin/domain/).

Consequences:

- The package admin is needed **once**: create the user, set its login shell to `/bin/bash` (this is what enables systemd lingering — `loginctl enable-linger` is not called by hand), assign the domain, order port and RAM. Day-to-day operation needs no admin rights.
- The RAM contingent is a **package** slice (`pacs-<account>.slice`), not a per-user quota, so a dedicated user gets no extra memory: a runaway Gradle build can starve everything else in the webspace.
  The unit generated by `init --systemd` therefore needs `MemoryMax` and `TasksMax` on this platform — `SystemdServiceFiles.unitFileContent` currently sets neither, so add them (configurable, empty = unset) as part of this step.
- Unverified, check on the target system: whether the port reservation is technically bound to that uid or merely organisational, and whether a non-admin user may read `systemctl status pacs-<account>.slice`.

The proxy itself is one `.htaccess` in `~/doms/<domain>/htdocs-ssl/`, following Hostsharing's own Mattermost and Tomcat guides:

```apache
DirectoryIndex disabled
RewriteEngine On
RewriteBase /
RewriteRule .* http://127.0.0.1:<assigned-port>%{REQUEST_URI} [proxy]
```

Sources: [Mattermost Installieren](https://wiki.hostsharing.net/index.php/Mattermost_Installieren), [Tomcat Installieren](https://wiki.hostsharing.net/index.php?title=Tomcat_Installieren).

The matching GitTally configuration:

```yaml
server:
  port: <assigned-port>
  bindAddress: 127.0.0.1                   # the default since v0.9.9 — exactly right here
  publicBaseUrl: "https://ci.example.de/"  # used for every link posted to Gitea
  nginx:
    enabled: false                         # the managed nginx container is not used on a webspace
```

**This half needs no code change.** GitTally never reconstructs absolute URLs from the request — everything external comes from `server.publicBaseUrl` and the UI links relatively — so the usual reverse-proxy fix `server.forward-headers-strategy` is not needed.

Two claims could **not** be verified from a Hostsharing primary source; check them on the target webspace rather than relying on them:

- the effective `AllowOverride` value — that `RewriteRule [P]`, `DirectoryIndex` and `RequestHeader` work in user `.htaccess` is evidenced by the wiki guides, but the literal token is undocumented;
- whether an unassigned high port would bind at all — the documented contract is to use the assigned ones.

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
- On the same webspace: the UI answers over HTTPS under the domain through the Apache `.htaccess` proxy, the service survives a logout and a reboot (systemd lingering), and Gitea statuses carry `publicBaseUrl` links that resolve.
- Docs updated: `docs/configuration.md` (bwrap section), architecture skill (third runtime), ADR 0007, and `docs/deployment.md` gains "Hostsharing Managed Webspace" as a third deployment variant — written only once the setup above is verified on a real webspace, not from this plan.
